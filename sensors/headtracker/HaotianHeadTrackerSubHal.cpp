/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

#include <V2_1/SubHal.h>

#include <android/hardware/sensors/2.1/types.h>
#include <cutils/sockets.h>
#include <hardware/sensors.h>
#include <log/log.h>
#include <utils/SystemClock.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <poll.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <string>
#include <vector>

namespace android::hardware::sensors::V2_1::implementation {

using V1_0::MetaDataEventType;
using V1_0::OperationMode;
using V1_0::RateLevel;
using V1_0::Result;
using V1_0::SensorFlagBits;
using V1_0::SharedMemInfo;

constexpr char kSocketName[] = "haotian_headtracker_v2";
constexpr uint32_t kMagic = 0x48545033;  // "HTP3"
constexpr uint32_t kProtocolVersion = 2;
constexpr int32_t kMetaHandle = 1;
constexpr int32_t kFirstHeadTrackerHandle = 2;
constexpr int32_t kLastHeadTrackerHandle = 0x00ffffff;
constexpr int64_t kMinPeriodNs = 20'000'000;
constexpr int64_t kMaxPeriodNs = 100'000'000;
// Android's pose controller declares a tracker stale after 120 ms. AirPods AACP motion packets
// are bursty and have measured, valid gaps of roughly 260 ms, so bridge only short transport gaps
// with the last orientation. A real disconnect still becomes stale promptly after this window.
constexpr int64_t kPoseHoldNs = 350'000'000;
constexpr size_t kHeaderSize = 64;
constexpr size_t kRecordSize = 64;
constexpr uint32_t kCapacity = 128;
constexpr size_t kRegionSize = kHeaderSize + kRecordSize * kCapacity;
constexpr float kPi = 3.14159265358979323846f;

static int32_t initialHeadTrackerHandle() {
    constexpr uint64_t kHandleCount =
            static_cast<uint64_t>(kLastHeadTrackerHandle - kFirstHeadTrackerHandle) + 1;
    // A fresh Sensors multi-HAL process can outlive neither its own monotonic clock nor a boot,
    // while SensorService can survive a multi-HAL restart. Seeding the process-local sequence from
    // uptime avoids immediately colliding with a stale handle left in the surviving framework.
    return kFirstHeadTrackerHandle + static_cast<int32_t>(
            static_cast<uint64_t>(::android::elapsedRealtimeNano()) % kHandleCount);
}

struct alignas(8) SharedHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t recordSize;
    uint32_t capacity;
    uint8_t uuid[16];
    uint64_t producerSequence;
    uint64_t consumerSequence;
    int64_t consumerReadTimestampNanos;
    int64_t consumerPostTimestampNanos;
};

struct alignas(8) SharedRecord {
    uint64_t sequence;
    int64_t timestampNanos;
    float qx;
    float qy;
    float qz;
    float qw;
    float vx;
    float vy;
    float vz;
    float confidence;
    int32_t discontinuity;
    uint8_t reserved[12];
};

static_assert(sizeof(SharedHeader) == kHeaderSize);
static_assert(sizeof(SharedRecord) == kRecordSize);

static uint64_t loadAcquire(const uint64_t* value) {
    return __atomic_load_n(value, __ATOMIC_ACQUIRE);
}

static void storeRelease(uint64_t* value, uint64_t update) {
    __atomic_store_n(value, update, __ATOMIC_RELEASE);
}

static SensorInfo makeMetaSensor() {
    SensorInfo info{};
    info.sensorHandle = kMetaHandle;
    info.name = "Haotian dynamic head tracker manager";
    info.vendor = "Xiaomi haotian";
    info.version = 1;
    info.type = SensorType::DYNAMIC_SENSOR_META;
    info.typeAsString = SENSOR_STRING_TYPE_DYNAMIC_SENSOR_META;
    info.maxRange = 1.0f;
    info.resolution = 1.0f;
    info.power = 0.000001f;
    info.flags = static_cast<uint32_t>(SensorFlagBits::SPECIAL_REPORTING_MODE)
            | static_cast<uint32_t>(SensorFlagBits::WAKE_UP);
    return info;
}

static SensorInfo makeHeadTrackerSensor(int32_t handle) {
    SensorInfo info{};
    info.sensorHandle = handle;
    info.name = "AirPods Pro 3 head tracker";
    info.vendor = "Apple via haotian AACP";
    info.version = 0x01000000;  // SpatializerHelper selects v1 for Classic A2DP.
    info.type = static_cast<SensorType>(SENSOR_TYPE_HEAD_TRACKER);
    info.typeAsString = SENSOR_STRING_TYPE_HEAD_TRACKER;
    info.maxRange = kPi;
    info.resolution = 1.0e-6f;
    info.power = 0.01f;
    info.minDelay = static_cast<int32_t>(kMinPeriodNs / 1000);
    info.maxDelay = static_cast<int32_t>(kMaxPeriodNs / 1000);
    info.flags = static_cast<uint32_t>(SensorFlagBits::CONTINUOUS_MODE);
    return info;
}

class HaotianHeadTrackerSubHal final : public ISensorsSubHal {
  public:
    HaotianHeadTrackerSubHal()
        : mMetaSensor(makeMetaSensor()),
          mNextHeadTrackerHandle(initialHeadTrackerHandle()) {}

    ~HaotianHeadTrackerSubHal() override {
        mStopping.store(true);
        mCondition.notify_all();
        int socketFd;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            socketFd = mSocket;
        }
        if (socketFd >= 0) shutdown(socketFd, SHUT_RDWR);
        if (mConnectionThread.joinable()) mConnectionThread.join();
        if (mSampleThread.joinable()) mSampleThread.join();
        clearRegion();
    }

    Return<Result> initialize(const sp<IHalProxyCallback>& callback) override {
        bool reannounce;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            const bool callbackChanged = mCallback != callback;
            mCallback = callback;
            mActive = false;
            mLastSequence = 0;
            resetSamplingStateLocked();
            reannounce = mConnected && callbackChanged;
        }
        bool expected = false;
        if (mStarted.compare_exchange_strong(expected, true)) {
            mConnectionThread = std::thread(&HaotianHeadTrackerSubHal::connectionLoop, this);
            mSampleThread = std::thread(&HaotianHeadTrackerSubHal::sampleLoop, this);
        } else if (reannounce) {
            // initialize() is called again when the framework side restarts while this passthrough
            // HAL process survives. Re-publish any still-connected dynamic sensor to the new
            // callback before it is activated again.
            announceConnected();
        }
        return Result::OK;
    }

    const std::string getName() override { return "Haotian-AirPods-HeadTracker"; }

    Return<void> getSensorsList_2_1(getSensorsList_2_1_cb callback) override {
        callback(std::vector<SensorInfo>{mMetaSensor});
        return Void();
    }

    Return<Result> setOperationMode(OperationMode mode) override {
        return mode == OperationMode::NORMAL ? Result::OK : Result::BAD_VALUE;
    }

    Return<Result> activate(int32_t handle, bool enabled) override {
        if (handle == kMetaHandle) return Result::OK;
        std::lock_guard<std::mutex> lock(mMutex);
        if (!mConnected || handle != mHeadTrackerHandle) return Result::BAD_VALUE;
        mActive = enabled;
        if (enabled && mHeader != nullptr) {
            mLastSequence = loadAcquire(&mHeader->producerSequence);
            resetSamplingStateLocked();
        } else if (!enabled) {
            resetSamplingStateLocked();
        }
        mCondition.notify_all();
        return Result::OK;
    }

    Return<Result> batch(int32_t handle, int64_t samplingPeriodNs,
                         int64_t /*maxReportLatencyNs*/) override {
        if (handle == kMetaHandle) return Result::OK;
        std::lock_guard<std::mutex> lock(mMutex);
        if (!mConnected || handle != mHeadTrackerHandle) return Result::BAD_VALUE;
        mSamplingPeriodNs = std::clamp(samplingPeriodNs, kMinPeriodNs, kMaxPeriodNs);
        mCondition.notify_all();
        return Result::OK;
    }

    Return<Result> flush(int32_t handle) override {
        sp<IHalProxyCallback> callback;
        int32_t sensorHandle;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            if (!mConnected || handle != mHeadTrackerHandle || !mActive) return Result::BAD_VALUE;
            callback = mCallback;
            sensorHandle = mHeadTrackerHandle;
        }
        if (callback == nullptr) return Result::INVALID_OPERATION;
        Event event{};
        event.sensorHandle = sensorHandle;
        event.sensorType = SensorType::META_DATA;
        event.u.meta.what = MetaDataEventType::META_DATA_FLUSH_COMPLETE;
        callback->postEvents({event}, callback->createScopedWakelock(false));
        return Result::OK;
    }

    Return<Result> injectSensorData_2_1(const Event&) override { return Result::INVALID_OPERATION; }

    Return<void> registerDirectChannel(const SharedMemInfo&, registerDirectChannel_cb callback) override {
        callback(Result::INVALID_OPERATION, -1);
        return Void();
    }

    Return<Result> unregisterDirectChannel(int32_t) override { return Result::INVALID_OPERATION; }

    Return<void> configDirectReport(int32_t, int32_t, RateLevel,
                                    configDirectReport_cb callback) override {
        callback(Result::INVALID_OPERATION, 0);
        return Void();
    }

    Return<void> debug(const hidl_handle& handle, const hidl_vec<hidl_string>&) override {
        if (handle.getNativeHandle() == nullptr || handle->numFds < 1) return Void();
        std::lock_guard<std::mutex> lock(mMutex);
        dprintf(handle->data[0], "Haotian AirPods head tracker: connected=%d active=%d seq=%llu\n",
                mConnected, mActive, static_cast<unsigned long long>(mLastSequence));
        return Void();
    }

  private:
    void connectionLoop() {
        while (!mStopping.load()) {
            int fd = socket_local_client(kSocketName, ANDROID_SOCKET_NAMESPACE_ABSTRACT, SOCK_STREAM);
            if (fd < 0) {
                std::this_thread::sleep_for(std::chrono::seconds(1));
                continue;
            }
            {
                std::lock_guard<std::mutex> lock(mMutex);
                mSocket = fd;
            }
            if (!receiveRegion(fd)) {
                close(fd);
                {
                    std::lock_guard<std::mutex> lock(mMutex);
                    if (mSocket == fd) mSocket = -1;
                }
                std::this_thread::sleep_for(std::chrono::seconds(1));
                continue;
            }
            announceConnected();
            pollfd descriptor{fd, POLLIN | POLLHUP | POLLERR, 0};
            while (!mStopping.load()) {
                int result = poll(&descriptor, 1, 500);
                if (result < 0 && errno == EINTR) continue;
                if (result < 0 || (result > 0 && (descriptor.revents & (POLLHUP | POLLERR)))) break;
                if (result > 0 && (descriptor.revents & POLLIN)) {
                    char ignored[16];
                    if (read(fd, ignored, sizeof(ignored)) <= 0) break;
                }
            }
            announceDisconnected();
            close(fd);
            {
                std::lock_guard<std::mutex> lock(mMutex);
                if (mSocket == fd) mSocket = -1;
            }
            clearRegion();
        }
    }

    bool receiveRegion(int socketFd) {
        std::array<uint8_t, 16> handshake{};
        std::array<char, CMSG_SPACE(sizeof(int))> control{};
        iovec iov{handshake.data(), handshake.size()};
        msghdr message{};
        message.msg_iov = &iov;
        message.msg_iovlen = 1;
        message.msg_control = control.data();
        message.msg_controllen = control.size();
        ssize_t count = TEMP_FAILURE_RETRY(recvmsg(socketFd, &message, MSG_WAITALL));
        if (count != static_cast<ssize_t>(handshake.size())) return false;

        uint32_t magic, version, size;
        memcpy(&magic, handshake.data(), sizeof(magic));
        memcpy(&version, handshake.data() + 4, sizeof(version));
        memcpy(&size, handshake.data() + 8, sizeof(size));
        if (magic != kMagic || version != kProtocolVersion || size != kRegionSize) return false;

        int regionFd = -1;
        for (cmsghdr* cmsg = CMSG_FIRSTHDR(&message); cmsg != nullptr;
             cmsg = CMSG_NXTHDR(&message, cmsg)) {
            if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS
                    && cmsg->cmsg_len >= CMSG_LEN(sizeof(int))) {
                memcpy(&regionFd, CMSG_DATA(cmsg), sizeof(regionFd));
                break;
            }
        }
        if (regionFd < 0) return false;
        struct stat status{};
        if (fstat(regionFd, &status) != 0 || status.st_size != static_cast<off_t>(kRegionSize)) {
            close(regionFd);
            return false;
        }
        void* mapping = mmap(nullptr, kRegionSize, PROT_READ | PROT_WRITE, MAP_SHARED, regionFd, 0);
        close(regionFd);
        if (mapping == MAP_FAILED) return false;

        auto* header = static_cast<SharedHeader*>(mapping);
        if (header->magic != kMagic || header->version != kProtocolVersion
                || header->recordSize != kRecordSize || header->capacity != kCapacity
                || !isBluetoothUuid(header->uuid)) {
            munmap(mapping, kRegionSize);
            return false;
        }
        std::lock_guard<std::mutex> lock(mMutex);
        mMapping = mapping;
        mHeader = header;
        memcpy(mUuid.data(), header->uuid, mUuid.size());
        mLastSequence = loadAcquire(&header->producerSequence);
        mTransportDiscontinuities = 0;
        resetSamplingStateLocked();
        return true;
    }

    void clearRegion() {
        void* mapping;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mapping = mMapping;
            mMapping = nullptr;
            mHeader = nullptr;
            mConnected = false;
            mActive = false;
            mHeadTrackerHandle = 0;
            resetSamplingStateLocked();
            mCondition.notify_all();
        }
        if (mapping != nullptr) munmap(mapping, kRegionSize);
    }

    void announceConnected() {
        sp<IHalProxyCallback> callback;
        SensorInfo headSensor;
        int32_t sensorHandle;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            if (!mConnected) {
                mConnected = true;
                mHeadTrackerHandle = allocateHeadTrackerHandleLocked();
            }
            sensorHandle = mHeadTrackerHandle;
            headSensor = makeHeadTrackerSensor(sensorHandle);
            callback = mCallback;
        }
        if (callback == nullptr) return;
        hidl_vec<SensorInfo> sensors;
        sensors.resize(1);
        sensors[0] = headSensor;
        callback->onDynamicSensorsConnected_2_1(sensors);
        std::this_thread::sleep_for(std::chrono::seconds(1));

        Event event{};
        event.timestamp = ::android::elapsedRealtimeNano();
        event.sensorHandle = kMetaHandle;
        event.sensorType = SensorType::DYNAMIC_SENSOR_META;
        event.u.dynamic.connected = true;
        event.u.dynamic.sensorHandle = sensorHandle;
        memcpy(event.u.dynamic.uuid.data(), mUuid.data(), mUuid.size());
        callback->postEvents({event}, callback->createScopedWakelock(true));
    }

    void announceDisconnected() {
        sp<IHalProxyCallback> callback;
        int32_t sensorHandle;
        {
            std::lock_guard<std::mutex> lock(mMutex);
            if (!mConnected) return;
            sensorHandle = mHeadTrackerHandle;
            mConnected = false;
            mActive = false;
            callback = mCallback;
            mCondition.notify_all();
        }
        if (callback == nullptr) return;
        hidl_vec<int32_t> handles;
        handles.resize(1);
        handles[0] = sensorHandle;
        callback->onDynamicSensorsDisconnected(handles);
        // As with connect, let the framework consume the dynamic callback before the FMQ meta
        // event. This mirrors the platform dynamic_sensor sub-HAL ordering workaround.
        std::this_thread::sleep_for(std::chrono::seconds(1));

        Event event{};
        event.timestamp = ::android::elapsedRealtimeNano();
        event.sensorHandle = kMetaHandle;
        event.sensorType = SensorType::DYNAMIC_SENSOR_META;
        event.u.dynamic.connected = false;
        event.u.dynamic.sensorHandle = sensorHandle;
        callback->postEvents({event}, callback->createScopedWakelock(true));
        // The disconnect callback deliberately does not remove SensorDevice's entry. It is
        // removed only after the FMQ meta event above is consumed. Do not allow the next producer
        // to race a new callback ahead of that cleanup.
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    void sampleLoop() {
        while (!mStopping.load()) {
            SharedRecord record{};
            sp<IHalProxyCallback> callback;
            int32_t transportDiscontinuities = 0;
            int32_t sensorHandle = 0;
            bool postRecord = false;
            bool realProducerRecord = false;
            uint64_t consumedSequence = 0;
            int64_t consumerReadNanos = 0;
            {
                std::unique_lock<std::mutex> lock(mMutex);
                if (!mConnected || !mActive || mHeader == nullptr) {
                    mCondition.wait(lock, [this] {
                        return mStopping.load() || (mConnected && mActive && mHeader != nullptr);
                    });
                } else {
                    mCondition.wait_for(lock, std::chrono::milliseconds(5));
                }
                if (mStopping.load()) return;
                if (!mConnected || !mActive || mHeader == nullptr) continue;
                int64_t now = ::android::elapsedRealtimeNano();
                uint64_t producer = loadAcquire(&mHeader->producerSequence);
                if (producer != 0 && producer != mLastSequence) {
                    if (producer > mLastSequence + kCapacity) mTransportDiscontinuities++;
                    auto* records = reinterpret_cast<SharedRecord*>(
                            static_cast<uint8_t*>(mMapping) + kHeaderSize);
                    SharedRecord* source = &records[producer % kCapacity];
                    uint64_t before = loadAcquire(&source->sequence);
                    if (before != producer) continue;
                    record = *source;
                    if (loadAcquire(&source->sequence) != before) continue;
                    mLastSequence = producer;

                    if (!isRecordValid(record, now)
                            || record.timestampNanos <= mLastRawTimestamp) {
                        continue;
                    }
                    mLastRawTimestamp = record.timestampNanos;
                    mLastRecord = record;
                    mHasLastRecord = true;
                    realProducerRecord = true;
                    consumedSequence = producer;
                    consumerReadNanos = ::android::elapsedRealtimeNano();

                    // A synthetic hold event may have been posted just before this packet arrived.
                    // Keep its newer orientation for the next tick without publishing timestamps
                    // out of order or exceeding the sampling rate requested by SensorService.
                    if (mLastPostedTimestamp != 0
                            && record.timestampNanos - mLastPostedTimestamp
                                    < mSamplingPeriodNs) {
                        continue;
                    }
                    mLastPostedTimestamp = record.timestampNanos;
                    postRecord = true;
                } else if (mHasLastRecord
                        && now - mLastRecord.timestampNanos <= kPoseHoldNs
                        && (mLastPostedTimestamp == 0
                            || now - mLastPostedTimestamp >= mSamplingPeriodNs)) {
                    record = mLastRecord;
                    record.timestampNanos = now;
                    // Do not extrapolate indefinitely from a packet's angular velocity. Freezing
                    // for a short missing burst is inaudible; integrating stale velocity is not.
                    record.vx = 0.0f;
                    record.vy = 0.0f;
                    record.vz = 0.0f;
                    mLastPostedTimestamp = now;
                    postRecord = true;
                }
                if (!postRecord) continue;
                callback = mCallback;
                sensorHandle = mHeadTrackerHandle;
                transportDiscontinuities = mTransportDiscontinuities;
            }
            if (callback != nullptr) {
                postPose(callback, sensorHandle, record, transportDiscontinuities);
                if (realProducerRecord) {
                    acknowledgeConsumer(consumedSequence, consumerReadNanos,
                            ::android::elapsedRealtimeNano());
                }
            }
        }
    }

    static bool isRecordValid(const SharedRecord& record, int64_t now) {
        if (record.timestampNanos <= 0 || record.timestampNanos > now + 1'000'000'000
                || now - record.timestampNanos > 2'000'000'000
                || !std::isfinite(record.confidence)
                || record.confidence < 0.0f || record.confidence > 1.0f
                || record.discontinuity < 0 || record.discontinuity > 255) {
            return false;
        }
        float norm = std::sqrt(record.qx * record.qx + record.qy * record.qy
                + record.qz * record.qz + record.qw * record.qw);
        return std::isfinite(norm) && norm >= 0.5f && norm <= 1.5f
                && std::isfinite(record.vx) && std::isfinite(record.vy)
                && std::isfinite(record.vz);
    }

    void postPose(const sp<IHalProxyCallback>& callback, int32_t sensorHandle,
                  const SharedRecord& record, int32_t transportDiscontinuities) {
        int64_t now = ::android::elapsedRealtimeNano();
        if (!isRecordValid(record, now)) return;
        float norm = std::sqrt(record.qx * record.qx + record.qy * record.qy
                + record.qz * record.qz + record.qw * record.qw);
        float x = -record.qx / norm;  // conjugate: reference/world -> head
        float y = -record.qy / norm;
        float z = -record.qz / norm;
        float w = record.qw / norm;
        if (w < 0.0f) {
            x = -x;
            y = -y;
            z = -z;
            w = -w;
        }
        float sinHalf = std::sqrt(x * x + y * y + z * z);
        float angle = 2.0f * std::atan2(sinHalf, std::max(0.0f, w));
        float scale = sinHalf > 1.0e-7f ? angle / sinHalf : 2.0f;

        Event event{};
        event.timestamp = record.timestampNanos;
        event.sensorHandle = sensorHandle;
        event.sensorType = static_cast<SensorType>(SENSOR_TYPE_HEAD_TRACKER);
        event.u.data[0] = x * scale;
        event.u.data[1] = y * scale;
        event.u.data[2] = z * scale;
        event.u.data[3] = record.vx;
        event.u.data[4] = record.vy;
        event.u.data[5] = record.vz;
        event.u.data[6] = static_cast<float>(
                (record.discontinuity + transportDiscontinuities) & 0xff);
        callback->postEvents({event}, callback->createScopedWakelock(false));
    }

    void acknowledgeConsumer(uint64_t sequence, int64_t readNanos, int64_t postNanos) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (!mConnected || mHeader == nullptr || sequence == 0
                || sequence > loadAcquire(&mHeader->producerSequence)) {
            return;
        }
        mHeader->consumerReadTimestampNanos = readNanos;
        mHeader->consumerPostTimestampNanos = postNanos;
        storeRelease(&mHeader->consumerSequence, sequence);
    }

    void resetSamplingStateLocked() {
        mLastRawTimestamp = 0;
        mLastPostedTimestamp = 0;
        mLastRecord = {};
        mHasLastRecord = false;
    }

    int32_t allocateHeadTrackerHandleLocked() {
        const int32_t handle = mNextHeadTrackerHandle;
        // SensorService intentionally never reuses a dynamic handle during its lifetime. The
        // 24-bit local range is translated by multihal and is effectively inexhaustible here.
        mNextHeadTrackerHandle = mNextHeadTrackerHandle == kLastHeadTrackerHandle
                ? kFirstHeadTrackerHandle : mNextHeadTrackerHandle + 1;
        return handle;
    }

    static bool isBluetoothUuid(const uint8_t* uuid) {
        for (size_t i = 0; i < 8; ++i) {
            if (uuid[i] != 0) return false;
        }
        if (uuid[8] != 0x42 || uuid[9] != 0x54) return false;
        bool nonzero = false;
        bool notBroadcast = false;
        for (size_t i = 10; i < 16; ++i) {
            nonzero |= uuid[i] != 0;
            notBroadcast |= uuid[i] != 0xff;
        }
        return nonzero && notBroadcast;
    }

    const SensorInfo mMetaSensor;
    std::atomic<bool> mStarted{false};
    std::atomic<bool> mStopping{false};
    std::thread mConnectionThread;
    std::thread mSampleThread;
    std::mutex mMutex;
    std::condition_variable mCondition;
    sp<IHalProxyCallback> mCallback;
    int mSocket = -1;
    void* mMapping = nullptr;
    SharedHeader* mHeader = nullptr;
    std::array<uint8_t, 16> mUuid{};
    bool mConnected = false;
    bool mActive = false;
    int32_t mHeadTrackerHandle = 0;
    int32_t mNextHeadTrackerHandle;
    int64_t mSamplingPeriodNs = kMinPeriodNs;
    int64_t mLastRawTimestamp = 0;
    int64_t mLastPostedTimestamp = 0;
    SharedRecord mLastRecord{};
    bool mHasLastRecord = false;
    uint64_t mLastSequence = 0;
    int32_t mTransportDiscontinuities = 0;
};

}  // namespace android::hardware::sensors::V2_1::implementation

using android::hardware::sensors::V2_1::implementation::HaotianHeadTrackerSubHal;
using android::hardware::sensors::V2_1::implementation::ISensorsSubHal;

extern "C" ISensorsSubHal* sensorsHalGetSubHal_2_1(uint32_t* version) {
    static HaotianHeadTrackerSubHal subHal;
    *version = SUB_HAL_2_1_VERSION;
    return &subHal;
}
