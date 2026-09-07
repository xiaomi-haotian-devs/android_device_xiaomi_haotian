#define LOG_TAG "BootAnimOverlay"

#include "embedded_assets.h"

#include <android/bitmap.h>
#include <android/imagedecoder.h>
#include <android/native_window.h>
#include <android-base/properties.h>
#include <binder/ProcessState.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include <sys/random.h>
#include <sys/resource.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>
#include <ui/DisplayMode.h>
#include <ui/LayerStack.h>
#include <ui/PixelFormat.h>
#include <utils/Errors.h>
#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/ThreadDefs.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace bootanim_overlay {
namespace {

// AOSP bootanimation uses 0x40000000 on the primary boot layer stack.
// Keep this one just above it. If an OEM changes BootAnimation's layer,
// change this constant to remain higher than the OEM value.
constexpr int32_t kOverlayLayer = 0x40000001;
constexpr std::chrono::milliseconds kFrameInterval(1000);
constexpr const char* kBootAnimStateProperty = "init.svc.bootanim";

std::atomic<bool> gRunning{true};
std::mutex gStopMutex;
std::condition_variable gStopCv;

void requestStop() {
    {
        std::lock_guard<std::mutex> lock(gStopMutex);
        gRunning.store(false, std::memory_order_release);
    }
    gStopCv.notify_all();
}

bool waitOrStop(std::chrono::milliseconds duration) {
    std::unique_lock<std::mutex> lock(gStopMutex);
    return gStopCv.wait_for(lock, duration, [] {
        return !gRunning.load(std::memory_order_acquire);
    });
}

struct PropertySnapshot {
    std::string value;
    uint32_t serial = 0;
};

void propertyReadCallback(void* cookie, const char*, const char* value, uint32_t serial) {
    auto* snapshot = static_cast<PropertySnapshot*>(cookie);
    snapshot->value = value ? value : "";
    snapshot->serial = serial;
}

void monitorBootAnimationState() {
    const prop_info* pi = nullptr;

    // The service is started by an init property trigger when bootanim becomes
    // "running", so this property should already exist. Retry defensively.
    while (gRunning.load(std::memory_order_acquire) && pi == nullptr) {
        pi = __system_property_find(kBootAnimStateProperty);
        if (pi == nullptr) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
    }

    if (pi == nullptr) {
        return;
    }

    while (gRunning.load(std::memory_order_acquire)) {
        PropertySnapshot snapshot;
        __system_property_read_callback(pi, propertyReadCallback, &snapshot);

        // IMPORTANT: do not exit on service.bootanim.exit=1 and do not exit on
        // "stopping". "stopped" is published by init only after the bootanim
        // service process has actually exited/reaped, including any ending part.
        if (snapshot.value == "stopped") {
            ALOGI("bootanimation service is actually stopped; removing overlay");
            requestStop();
            return;
        }

        uint32_t newSerial = snapshot.serial;
        const timespec timeout{1, 0};
        (void)__system_property_wait(pi, snapshot.serial, &newSerial, &timeout);
    }
}

class BootAnimStateWatcher {
public:
    BootAnimStateWatcher() : mThread(monitorBootAnimationState) {}

    ~BootAnimStateWatcher() {
        // Also makes every early-return path safe: no detached thread survives
        // into process/static teardown. __system_property_wait has a 1 s timeout.
        requestStop();
        if (mThread.joinable()) {
            mThread.join();
        }
    }

    BootAnimStateWatcher(const BootAnimStateWatcher&) = delete;
    BootAnimStateWatcher& operator=(const BootAnimStateWatcher&) = delete;

private:
    std::thread mThread;
};

struct Image {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> rgbaPremul;
};

bool decodeEmbeddedPng(const EmbeddedPng& asset, Image* out) {
    if (out == nullptr || asset.data == nullptr || asset.size == 0) {
        return false;
    }

    AImageDecoder* decoder = nullptr;
    int result = AImageDecoder_createFromBuffer(asset.data, asset.size, &decoder);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS || decoder == nullptr) {
        ALOGE("AImageDecoder_createFromBuffer failed for %s: %d", asset.name, result);
        return false;
    }

    std::unique_ptr<AImageDecoder, decltype(&AImageDecoder_delete)> decoderHolder(
            decoder, AImageDecoder_delete);

    result = AImageDecoder_setAndroidBitmapFormat(decoder, ANDROID_BITMAP_FORMAT_RGBA_8888);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        ALOGE("forcing RGBA_8888 failed for %s: %d", asset.name, result);
        return false;
    }

    // Keep AImageDecoder's default premultiplied-alpha output. SurfaceFlinger
    // expects premultiplied RGBA unless eNonPremultiplied is explicitly set.
    const AImageDecoderHeaderInfo* info = AImageDecoder_getHeaderInfo(decoder);
    const int width = AImageDecoderHeaderInfo_getWidth(info);
    const int height = AImageDecoderHeaderInfo_getHeight(info);
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
        ALOGE("invalid PNG dimensions for %s: %dx%d", asset.name, width, height);
        return false;
    }

    const size_t stride = AImageDecoder_getMinimumStride(decoder);
    const size_t rowBytes = static_cast<size_t>(width) * 4u;
    if (stride < rowBytes || static_cast<size_t>(height) > SIZE_MAX / stride) {
        ALOGE("invalid decoded stride for %s", asset.name);
        return false;
    }

    std::vector<uint8_t> decoded(stride * static_cast<size_t>(height));
    result = AImageDecoder_decodeImage(decoder, decoded.data(), stride, decoded.size());
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        ALOGE("AImageDecoder_decodeImage failed for %s: %d", asset.name, result);
        return false;
    }

    out->width = width;
    out->height = height;
    out->rgbaPremul.resize(rowBytes * static_cast<size_t>(height));
    for (int y = 0; y < height; ++y) {
        std::memcpy(out->rgbaPremul.data() + static_cast<size_t>(y) * rowBytes,
                    decoded.data() + static_cast<size_t>(y) * stride, rowBytes);
    }
    return true;
}

struct Group {
    int id = 0;
    std::vector<const EmbeddedPng*> frames;
};

std::vector<Group> buildGroups() {
    std::map<int, std::vector<const EmbeddedPng*>> grouped;
    for (size_t i = 0; i < kEmbeddedPngCount; ++i) {
        grouped[kEmbeddedPngs[i].group_id].push_back(&kEmbeddedPngs[i]);
    }

    std::vector<Group> groups;
    groups.reserve(grouped.size());
    for (auto& [groupId, frames] : grouped) {
        std::sort(frames.begin(), frames.end(), [](const EmbeddedPng* a, const EmbeddedPng* b) {
            if (a->frame_index != b->frame_index) return a->frame_index < b->frame_index;
            return std::strcmp(a->name, b->name) < 0;
        });
        groups.push_back(Group{groupId, std::move(frames)});
    }
    return groups;
}

uint32_t bootRandom32() {
    uint32_t value = 0;
    const ssize_t n = getrandom(&value, sizeof(value), GRND_NONBLOCK);
    if (n == static_cast<ssize_t>(sizeof(value))) {
        return value;
    }

    // Non-blocking fallback: enough entropy for choosing a visual group, and
    // never delays early boot waiting for CRNG initialization.
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    uint64_t mixed = static_cast<uint64_t>(ts.tv_sec) * 1000000007ULL;
    mixed ^= static_cast<uint64_t>(ts.tv_nsec);
    mixed ^= static_cast<uint64_t>(getpid()) << 32;
    mixed ^= reinterpret_cast<uintptr_t>(&value);
    mixed ^= mixed >> 33;
    mixed *= 0xff51afd7ed558ccdULL;
    mixed ^= mixed >> 33;
    return static_cast<uint32_t>(mixed ^ (mixed >> 32));
}

class OverlayRenderer {
public:
    bool init(int canvasWidth, int canvasHeight) {
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            return false;
        }

        mClient = new android::SurfaceComposerClient();
        if (mClient->initCheck() != android::NO_ERROR) {
            ALOGE("SurfaceComposerClient init failed: %d", mClient->initCheck());
            return false;
        }

        const std::vector<android::PhysicalDisplayId> displayIds =
                android::SurfaceComposerClient::getPhysicalDisplayIds();
        if (displayIds.empty()) {
            ALOGE("no physical display available");
            return false;
        }

        const android::sp<android::IBinder> displayToken =
                android::SurfaceComposerClient::getPhysicalDisplayToken(displayIds.front());
        if (displayToken == nullptr) {
            ALOGE("failed to get primary physical display token");
            return false;
        }

        android::ui::DisplayMode displayMode;
        const android::status_t modeStatus =
                android::SurfaceComposerClient::getActiveDisplayMode(displayToken, &displayMode);
        if (modeStatus != android::NO_ERROR) {
            ALOGE("getActiveDisplayMode failed: %d", modeStatus);
            return false;
        }

        int logicalWidth = displayMode.resolution.width;
        int logicalHeight = displayMode.resolution.height;
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            ALOGE("invalid display dimensions");
            return false;
        }

        // Match this branch's BootAnimation::limitSurfaceSize before rotation.
        const float aspectRatio = static_cast<float>(logicalWidth) / logicalHeight;
        const int maxWidth = android::base::GetIntProperty("ro.surface_flinger.max_graphics_width", 0);
        const int maxHeight = android::base::GetIntProperty("ro.surface_flinger.max_graphics_height", 0);
        if (maxWidth > 0 && logicalWidth > maxWidth) {
            logicalWidth = maxWidth;
            logicalHeight = maxWidth / aspectRatio;
        }
        if (maxHeight > 0 && logicalHeight > maxHeight) {
            logicalHeight = maxHeight;
            logicalWidth = maxHeight * aspectRatio;
        }

        // Match BootAnimation's logical orientation handling so "bottom-center"
        // remains aligned with the boot animation's coordinate system.
        const std::string perDisplayProp = "ro.bootanim.set_orientation_" +
                std::to_string(displayIds.front().value);
        std::string orientation = android::base::GetProperty(perDisplayProp, "");
        if (orientation.empty()) {
            orientation = android::base::GetProperty(
                    "ro.bootanim.set_orientation_logical_0", "");
        }
        if (orientation == "ORIENTATION_90" || orientation == "ORIENTATION_270") {
            std::swap(logicalWidth, logicalHeight);
        }

        const int x = (logicalWidth - canvasWidth) / 2;
        const int y = logicalHeight - canvasHeight;

        mControl = mClient->createSurface(
                android::String8("BootAnimOverlay"),
                static_cast<uint32_t>(canvasWidth),
                static_cast<uint32_t>(canvasHeight),
                android::PIXEL_FORMAT_RGBA_8888,
                android::ISurfaceComposerClient::eHidden);
        if (mControl == nullptr || !mControl->isValid()) {
            ALOGE("failed to create overlay SurfaceControl");
            return false;
        }

        mSurface = mControl->getSurface();
        if (mSurface == nullptr) {
            ALOGE("failed to get overlay Surface");
            return false;
        }

        android::SurfaceComposerClient::Transaction transaction;
        transaction.setLayerStack(mControl, android::ui::LayerStack::fromValue(0));
        const android::status_t transactionStatus = transaction.setLayer(mControl, kOverlayLayer)
                .setPosition(mControl, static_cast<float>(x), static_cast<float>(y))
                .apply();
        if (transactionStatus != android::NO_ERROR) {
            ALOGE("overlay transaction failed: %d", transactionStatus);
            return false;
        }

        ALOGI("overlay surface %dx%d at (%d,%d), display logical=%dx%d, layer=0x%x",
              canvasWidth, canvasHeight, x, y, logicalWidth, logicalHeight, kOverlayLayer);
        return true;
    }

    bool draw(const Image& image, int canvasWidth, int canvasHeight) {
        if (mSurface == nullptr || image.width <= 0 || image.height <= 0) {
            return false;
        }

        ANativeWindow_Buffer buffer{};
        android::status_t status = mSurface->lock(&buffer, nullptr);
        if (status != android::NO_ERROR) {
            ALOGE("Surface::lock failed: %d", status);
            return false;
        }

        if (buffer.bits == nullptr || buffer.format != android::PIXEL_FORMAT_RGBA_8888) {
            ALOGE("unexpected surface buffer: bits=%p format=%d", buffer.bits, buffer.format);
            (void)mSurface->unlockAndPost();
            return false;
        }

        auto* dst = static_cast<uint8_t*>(buffer.bits);
        const size_t dstRowBytes = static_cast<size_t>(buffer.stride) * 4u;
        std::memset(dst, 0, dstRowBytes * static_cast<size_t>(buffer.height));

        // Frames with different dimensions are bottom-centered inside the
        // selected group's maximum canvas. This prevents vertical jumping.
        const int offsetX = std::max(0, (canvasWidth - image.width) / 2);
        const int offsetY = std::max(0, canvasHeight - image.height);
        const size_t srcRowBytes = static_cast<size_t>(image.width) * 4u;

        const int copyWidth = std::min(image.width, buffer.width - offsetX);
        const int copyHeight = std::min(image.height, buffer.height - offsetY);
        if (copyWidth > 0 && copyHeight > 0) {
            const size_t copyBytes = static_cast<size_t>(copyWidth) * 4u;
            for (int row = 0; row < copyHeight; ++row) {
                std::memcpy(dst + static_cast<size_t>(offsetY + row) * dstRowBytes +
                                    static_cast<size_t>(offsetX) * 4u,
                            image.rgbaPremul.data() + static_cast<size_t>(row) * srcRowBytes,
                            copyBytes);
            }
        }

        status = mSurface->unlockAndPost();
        if (status != android::NO_ERROR) {
            ALOGE("Surface::unlockAndPost failed: %d", status);
            return false;
        }
        return true;
    }

    bool show() {
        if (mControl != nullptr && !mShown) {
            const android::status_t status =
                    android::SurfaceComposerClient::Transaction().show(mControl).apply();
            if (status != android::NO_ERROR) {
                ALOGE("show transaction failed: %d", status);
                return false;
            }
            mShown = true;
        }
        return mShown;
    }

    ~OverlayRenderer() {
        if (mControl != nullptr) {
            android::SurfaceComposerClient::Transaction().hide(mControl).apply();
        }
        mSurface.clear();
        mControl.clear();
        mClient.clear();
    }

private:
    android::sp<android::SurfaceComposerClient> mClient;
    android::sp<android::SurfaceControl> mControl;
    android::sp<android::Surface> mSurface;
    bool mShown = false;
};

}  // namespace

int run() {
    // Do not show the boot-only overlay during a shutdown animation.
    if (android::base::GetBoolProperty("sys.boot_completed", false) ||
        !android::base::GetProperty("sys.powerctl", "").empty()) {
        ALOGI("post-boot or shutdown invocation; boot overlay disabled");
        return 0;
    }

    setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_DISPLAY);
    android::sp<android::ProcessState> proc(android::ProcessState::self());
    proc->startThreadPool();

    // The init trigger starts us when bootanim enters "running". Keep an
    // in-process watcher as a second guard, but only treat the real "stopped"
    // state as completion.
    BootAnimStateWatcher bootStateWatcher;

    std::vector<Group> groups = buildGroups();
    if (groups.empty()) {
        ALOGE("no embedded PNG groups");
        return 1;
    }

    const size_t selectedIndex = static_cast<size_t>(bootRandom32()) % groups.size();
    const Group& selected = groups[selectedIndex];
    ALOGI("selected boot PNG group %d (%zu frame(s), %zu group(s) total)",
          selected.id, selected.frames.size(), groups.size());

    std::vector<Image> images;
    images.reserve(selected.frames.size());
    int canvasWidth = 0;
    int canvasHeight = 0;
    for (const EmbeddedPng* asset : selected.frames) {
        if (!gRunning.load(std::memory_order_acquire)) {
            return 0;
        }
        Image image;
        if (!decodeEmbeddedPng(*asset, &image)) {
            ALOGE("failed to decode selected asset %s", asset->name);
            return 2;
        }
        canvasWidth = std::max(canvasWidth, image.width);
        canvasHeight = std::max(canvasHeight, image.height);
        images.push_back(std::move(image));
    }

    OverlayRenderer renderer;
    if (!gRunning.load(std::memory_order_acquire)) {
        return 0;
    }
    if (!renderer.init(canvasWidth, canvasHeight)) {
        return 3;
    }

    if (!renderer.draw(images.front(), canvasWidth, canvasHeight)) {
        return 4;
    }
    if (!gRunning.load(std::memory_order_acquire)) {
        return 0;
    }
    if (!renderer.show()) {
        return 5;
    }

    if (images.size() == 1) {
        // Static group: hold the one PNG until bootanimation itself really exits.
        while (gRunning.load(std::memory_order_acquire)) {
            (void)waitOrStop(std::chrono::seconds(1));
        }
        return 0;
    }

    size_t frame = 1;
    while (gRunning.load(std::memory_order_acquire)) {
        if (waitOrStop(kFrameInterval)) {
            break;
        }
        if (!renderer.draw(images[frame], canvasWidth, canvasHeight)) {
            break;
        }
        frame = (frame + 1) % images.size();
    }

    return 0;
}

}  // namespace bootanim_overlay

int main() {
    return bootanim_overlay::run();
}
