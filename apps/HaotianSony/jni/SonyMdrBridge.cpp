// SPDX-License-Identifier: Apache-2.0
// Android owns RFCOMM establishment. This bridge is used on one SonyController worker only.
#include <jni.h>
#include <mdr-c/Headphones.h>
#include <android-base/unique_fd.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <poll.h>
#include <string>
#include <string_view>
#include <sys/socket.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr size_t kMaxItems = 128;
struct Session {
    android::base::unique_fd fd;
    MDRConnection connection{};
    MDRHeadphones* headphones = nullptr;
    std::string error;
    std::string readbackKey;
    ~Session() { if (headphones) mdrHeadphonesDestroy(headphones); }
};

Session* session(jlong handle) { return reinterpret_cast<Session*>(handle); }

MDRResult socketResult(Session* s, ssize_t count, int* transferred) {
    *transferred = 0;
    if (count > 0) {
        *transferred = static_cast<int>(count);
        return MDR_RESULT_OK;
    }
    if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))
        return MDR_RESULT_INPROGRESS;
    s->error = count == 0 ? "RFCOMM closed" : strerror(errno);
    return MDR_RESULT_ERROR_NO_CONNECTION;
}

bool feature(Session* s, MDRFeature id) {
    MDRFeatureAvailability availability = MDR_AVAILABILITY_UNKNOWN;
    return mdrHeadphonesGetFeature(s->headphones, id, &availability) == MDR_RESULT_OK
            && availability == MDR_AVAILABILITY_AVAILABLE;
}

std::string getText(Session* s, MDRText field, uint32_t index = 0) {
    uint32_t size = 0;
    if (mdrHeadphonesGetText(s->headphones, field, index, nullptr, &size) != MDR_RESULT_OK
            || size == 0 || size > 16384) return {};
    std::vector<char> buffer(size, 0);
    if (mdrHeadphonesGetText(s->headphones, field, index, buffer.data(), &size) != MDR_RESULT_OK)
        return {};
    return std::string(buffer.data(), strnlen(buffer.data(), buffer.size()));
}

// JSON is returned as UTF-8 bytes, not JNI modified UTF-8. Device names can contain emoji.
std::string quote(std::string_view value) {
    constexpr char hex[] = "0123456789abcdef";
    std::string out = "\"";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c < 0x20) {
            out += "\\u00";
            out += hex[c >> 4]; out += hex[c & 15];
        } else out += c;
    }
    return out + "\"";
}
struct Object {
    std::string value = "{";
    bool first = true;
    void raw(std::string_view key, std::string_view data) {
        if (!first) value += ',';
        first = false;
        value += quote(key) + ":" + std::string(data);
    }
    void number(std::string_view key, int64_t data) { raw(key, std::to_string(data)); }
    void text(std::string_view key, std::string_view data) { raw(key, quote(data)); }
    std::string finish() const { return value + '}'; }
};
struct Array {
    std::string value = "[";
    bool first = true;
    void add(const std::string& data) { if (!first) value += ','; first = false; value += data; }
    std::string finish() const { return value + ']'; }
};

template<typename T, typename Getter>
std::vector<T> getList(Session* s, Getter getter) {
    uint32_t count = 0;
    if (getter(s->headphones, nullptr, &count) != MDR_RESULT_OK || count > kMaxItems)
        return {};
    if (!count) return {};
    std::vector<T> result(count);
    if (getter(s->headphones, result.data(), &count) != MDR_RESULT_OK
            || count > result.size()) return {};
    result.resize(count);
    return result;
}

std::string snapshot(Session* s) {
    Object root, values;
    Array features, batteries, bands, presets, general, assignments, paired;
    auto* h = s->headphones;
    auto observed = [&](const char* key) { return mdrHeadphonesHasReportedControl(h, key); };
    root.text("model", getText(s, MDR_TEXT_MODEL_NAME));
    root.text("firmware", getText(s, MDR_TEXT_FIRMWARE_VERSION));
    root.text("alert", getText(s, MDR_TEXT_LAST_ALERT));
    root.text("track", getText(s, MDR_TEXT_TRACK_TITLE));
    root.text("artist", getText(s, MDR_TEXT_TRACK_ARTIST));
    MDRModel model{};
    if (mdrHeadphonesGetModel(h, &model) == MDR_RESULT_OK) {
        root.number("protocol", model.protocol_version);
        root.number("codec", model.audio_codec);
    }
    for (uint32_t id = MDR_FEATURE_IDENTITY; id <= MDR_FEATURE_SOURCE_SWITCH_CONTROL; ++id)
        if (feature(s, id)) features.add(std::to_string(id));
    for (const auto& b : getList<MDRBattery>(s, mdrHeadphonesGetBatteries)) {
        if (!b.present || b.level_percent > 100) continue;
        Object item;
        item.number("part", b.part); item.number("level", b.level_percent);
        item.number("charging", b.charging);
        batteries.add(item.finish());
    }
    MDRPlayback playback{};
    if (observed("playback.volume") && mdrHeadphonesGetPlayback(h, &playback) == MDR_RESULT_OK)
        values.number("playback.volume", playback.volume);
    MDRNoiseControl noise{};
    if (observed("noise.mode") && mdrHeadphonesGetNoiseControl(h, &noise) == MDR_RESULT_OK) {
        values.number("noise.mode", noise.mode);
        values.number("noise.ambient", noise.ambient_level);
        values.number("noise.voice", noise.focus_on_voice);
        values.number("noise.button", noise.button_mode);
        values.number("noise.adaptive", noise.adaptive_ambient);
        values.number("noise.sensitivity", noise.adaptive_sensitivity);
    }
    MDRSpeakToChat speak{};
    if (observed("speak.enabled") && mdrHeadphonesGetSpeakToChat(h, &speak) == MDR_RESULT_OK) {
        values.number("speak.enabled", speak.enabled);
        values.number("speak.sensitivity", speak.sensitivity);
        values.number("speak.timeout", speak.timeout);
    }
    MDRListening listening{};
    if (observed("listening.mode") && mdrHeadphonesGetListening(h, &listening) == MDR_RESULT_OK) {
        values.number("listening.mode", listening.mode);
        values.number("listening.room", listening.background_room);
    }
    MDREqualizer eq{};
    if (observed("eq.preset") && mdrHeadphonesGetEqualizer(h, &eq) == MDR_RESULT_OK) {
        values.number("eq.preset", eq.preset); values.number("eq.bass", eq.clear_bass);
        values.number("eq.dsee", eq.dsee_enabled); root.number("dseeType", eq.dsee_type);
    }
    for (int8_t band : getList<int8_t>(s, mdrHeadphonesGetEqualizerBands))
        bands.add(std::to_string(band));
    for (auto preset : getList<MDREqualizerPreset>(s, mdrHeadphonesGetEqualizerPresets))
        if (preset != MDR_EQ_UNKNOWN) presets.add(std::to_string(preset));
    MDRPower power{};
    if (observed("power.timeout") && mdrHeadphonesGetPower(h, &power) == MDR_RESULT_OK) {
        values.number("power.timeout", power.auto_power_off_minutes);
        if (power.wearing_power != MDR_WEARING_POWER_UNAVAILABLE)
            values.number("power.wearing", power.wearing_power);
        values.number("power.pause", power.auto_pause);
        values.number("power.gesture", power.head_gesture);
    }
    MDRVoiceGuidance voice{};
    if (observed("voice.enabled") && mdrHeadphonesGetVoiceGuidance(h, &voice) == MDR_RESULT_OK) {
        values.number("voice.enabled", voice.enabled); values.number("voice.volume", voice.volume);
    }
    MDRConnectionMode connection{};
    if (observed("connection.priority") && mdrHeadphonesGetConnectionMode(h, &connection) == MDR_RESULT_OK)
        values.number("connection.priority", connection.audio_priority);
    MDRPairing pairing{};
    if (observed("pairing.enabled") && mdrHeadphonesGetPairing(h, &pairing) == MDR_RESULT_OK)
        values.number("pairing.enabled", pairing.enabled);
    MDRBoolean switching{};
    if (observed("pairing.switch") && mdrHeadphonesGetSourceSwitchControl(h, &switching) == MDR_RESULT_OK)
        values.number("pairing.switch", switching);
    MDRSafeListening safe{};
    if (feature(s, MDR_FEATURE_SAFE_LISTENING) && mdrHeadphonesGetSafeListening(h, &safe) == MDR_RESULT_OK) {
        if (safe.sound_pressure != 255) root.number("soundPressure", safe.sound_pressure);
        if (observed("safe.state")) values.number("safe.preview", safe.preview);
    }
    for (const auto& info : getList<MDRGeneralSettingInfo>(s, mdrHeadphonesGetGeneralSettingInfo)) {
        if (!observed("general")) break;
        MDRGeneralSetting setting{};
        if (info.type != MDR_GENERAL_SETTING_BOOLEAN ||
                mdrHeadphonesGetGeneralSetting(h, info.index, &setting) != MDR_RESULT_OK) continue;
        Object item;
        item.number("index", info.index); item.number("writable", info.writable);
        item.number("value", setting.boolean_value);
        item.text("title", getText(s, MDR_TEXT_GENERAL_SETTING_SUBJECT, info.index));
        item.text("summary", getText(s, MDR_TEXT_GENERAL_SETTING_SUMMARY, info.index));
        general.add(item.finish());
    }
    for (const auto& control : getList<MDRAssignableControl>(s, mdrHeadphonesGetAssignableControls)) {
        if (!observed("assign")) break;
        std::array<MDRAssignableAction, 32> options{};
        uint32_t count = options.size();
        if (mdrHeadphonesGetAssignableControlActions(h, control.location, options.data(), &count)
                != MDR_RESULT_OK || count > options.size()) continue;
        Object item;
        Array allowed;
        for (uint32_t i = 0; i < count; ++i) allowed.add(std::to_string(options[i]));
        item.number("location", control.location); item.number("type", control.type);
        item.number("value", control.action); item.raw("options", allowed.finish());
        assignments.add(item.finish());
    }
    for (const auto& device : getList<MDRPairedDevice>(s, mdrHeadphonesGetPairedDevices)) {
        Object item;
        item.text("id", std::string_view(device.macAddress, strnlen(device.macAddress, 18)));
        item.text("name", std::string_view(device.name, strnlen(device.name, 128)));
        item.number("connected", device.connected); item.number("playing", device.playback_device);
        paired.add(item.finish());
    }
    root.raw("features", features.finish()); root.raw("values", values.finish());
    root.raw("batteries", batteries.finish()); root.raw("bands", bands.finish());
    root.raw("general", general.finish()); root.raw("assignments", assignments.finish());
    root.raw("paired", paired.finish());
    root.raw("eqPresets", presets.finish());
    return root.finish();
}

// Range and feature checks happen before the first Set call. Each partial edit starts with the
// complete reported structure, preserving other channels, modes and unexposed fields.
MDRResult setValue(Session* s, std::string_view key, int value, std::string_view target) {
    auto* h = s->headphones;
    if (!mdrHeadphonesIsInitialized(h) || !mdrHeadphonesIsReady(h)) return MDR_RESULT_INPROGRESS;
    const std::string controlKey(key);
    if (!mdrHeadphonesHasReportedControl(h, controlKey.c_str())) return MDR_RESULT_INPROGRESS;
    auto supported = [&](MDRFeature f, int lo, int hi) {
        return feature(s, f) && value >= lo && value <= hi;
    };
    if (key == "playback.volume" && supported(MDR_FEATURE_PLAYBACK_VOLUME, 0, 30)) {
        MDRPlayback v{};
        if (mdrHeadphonesGetPlayback(h, &v) != MDR_RESULT_OK) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        v.volume = value;
        return mdrHeadphonesSetPlayback(h, &v);
    }
    if (key == "playback.command" && supported(MDR_FEATURE_PLAYBACK_CONTROL, 1, 4)) {
        MDRPlaybackCommand v{static_cast<MDRPlaybackAction>(value)};
        return mdrHeadphonesPlayback(h, &v);
    }
    if (key.starts_with("noise.")) {
        MDRNoiseControl n{};
        if (mdrHeadphonesGetNoiseControl(h, &n) != MDR_RESULT_OK) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        if (key == "noise.mode" && supported(MDR_FEATURE_NOISE_CANCELLING, 0, 2)) {
            if (value == 2 && !feature(s, MDR_FEATURE_AMBIENT_SOUND)) return MDR_RESULT_ERROR_NOT_SUPPORTED;
            n.mode = value;
        } else if (key == "noise.ambient" && supported(MDR_FEATURE_AMBIENT_SOUND, 0, 20)) {
            n.ambient_level = value;
        } else if (key == "noise.voice" && supported(MDR_FEATURE_AMBIENT_SOUND, 0, 1)) n.focus_on_voice = value;
        else if (key == "noise.button" && supported(MDR_FEATURE_NOISE_CONTROL_BUTTON, 1, 4)) n.button_mode = value;
        else if (key == "noise.adaptive" && supported(MDR_FEATURE_ADAPTIVE_AMBIENT_SOUND, 0, 1)) n.adaptive_ambient = value;
        else if (key == "noise.sensitivity" && supported(MDR_FEATURE_ADAPTIVE_AMBIENT_SOUND, 1, 3)) n.adaptive_sensitivity = value;
        else return MDR_RESULT_ERROR_NOT_SUPPORTED;
        return mdrHeadphonesSetNoiseControl(h, &n);
    }
    if (key.starts_with("speak.")) {
        if (!feature(s, MDR_FEATURE_SPEAK_TO_CHAT)) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        MDRSpeakToChat v{};
        if (mdrHeadphonesGetSpeakToChat(h, &v) != MDR_RESULT_OK) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        if (key == "speak.enabled" && value >= 0 && value <= 1) v.enabled = value;
        else if (key == "speak.sensitivity" && value >= 1 && value <= 3) v.sensitivity = value;
        else if (key == "speak.timeout" && value >= 1 && value <= 4) v.timeout = value;
        else return MDR_RESULT_ERROR_INVALID_ARGUMENT;
        return mdrHeadphonesSetSpeakToChat(h, &v);
    }
    if (key.starts_with("listening.")) {
        if (!feature(s, MDR_FEATURE_LISTENING_MODE)) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        MDRListening v{};
        if (mdrHeadphonesGetListening(h, &v) != MDR_RESULT_OK) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        if (key == "listening.mode" && value >= 0 && value <= 2) v.mode = value;
        else if (key == "listening.room" && value >= 1 && value <= 3) v.background_room = value;
        else return MDR_RESULT_ERROR_INVALID_ARGUMENT;
        // Selecting background playback requires a room. Preserve a known room; use medium on
        // the first explicit selection if this firmware has never reported one.
        if (v.mode == MDR_LISTENING_BACKGROUND_MUSIC && v.background_room == MDR_ROOM_UNKNOWN)
            v.background_room = MDR_ROOM_MEDIUM;
        return mdrHeadphonesSetListening(h, &v);
    }
    if (key == "eq.band") {
        if (!feature(s, MDR_FEATURE_EQUALIZER)) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        auto bands = getList<int8_t>(s, mdrHeadphonesGetEqualizerBands);
        if ((bands.size() != 5 && bands.size() != 10) || target.size() != 1
                || target[0] < '0' || target[0] > '9') return MDR_RESULT_ERROR_INVALID_ARGUMENT;
        size_t index = target[0] - '0';
        int limit = bands.size() == 5 ? 10 : 6;
        if (index >= bands.size() || value < -limit || value > limit) return MDR_RESULT_ERROR_INVALID_ARGUMENT;
        bands[index] = value;
        return mdrHeadphonesSetEqualizerBands(h, bands.data(), bands.size());
    }
    if (key.starts_with("eq.")) {
        MDREqualizer v{};
        if (mdrHeadphonesGetEqualizer(h, &v) != MDR_RESULT_OK) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        if (key == "eq.preset" && supported(MDR_FEATURE_EQUALIZER, 0, 29)) {
            auto presets = getList<MDREqualizerPreset>(s, mdrHeadphonesGetEqualizerPresets);
            if (std::find(presets.begin(), presets.end(), static_cast<MDREqualizerPreset>(value))
                    == presets.end()) return MDR_RESULT_ERROR_NOT_SUPPORTED;
            v.preset = value;
        }
        else if (key == "eq.bass" && supported(MDR_FEATURE_EQUALIZER, -10, 10)) v.clear_bass = value;
        else if (key == "eq.dsee" && supported(MDR_FEATURE_DSEE, 0, 1)) v.dsee_enabled = value;
        else return MDR_RESULT_ERROR_NOT_SUPPORTED;
        return mdrHeadphonesSetEqualizer(h, &v);
    }
    if (key.starts_with("power.")) {
        MDRPower v{};
        if (mdrHeadphonesGetPower(h, &v) != MDR_RESULT_OK) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        // Never retain a one-shot shutdown from an old report while editing another setting.
        v.shutdown_requested = MDR_FALSE;
        if (key == "power.timeout" && supported(MDR_FEATURE_AUTO_POWER_OFF, 0, 180)) v.auto_power_off_minutes = value;
        else if (key == "power.wearing" && supported(MDR_FEATURE_WEARING_DETECTION, 1, 2)) v.wearing_power = value;
        else if (key == "power.pause" && supported(MDR_FEATURE_AUTO_PAUSE, 0, 1)) v.auto_pause = value;
        else if (key == "power.gesture" && supported(MDR_FEATURE_HEAD_GESTURE, 0, 1)) v.head_gesture = value;
        else if (key == "power.shutdown" && supported(MDR_FEATURE_SHUTDOWN, 1, 1)) v.shutdown_requested = MDR_TRUE;
        else return MDR_RESULT_ERROR_NOT_SUPPORTED;
        return mdrHeadphonesSetPower(h, &v);
    }
    if (key.starts_with("voice.")) {
        MDRVoiceGuidance v{};
        if (mdrHeadphonesGetVoiceGuidance(h, &v) != MDR_RESULT_OK) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        if (key == "voice.enabled" && supported(MDR_FEATURE_VOICE_GUIDANCE, 0, 1)) v.enabled = value;
        else if (key == "voice.volume" && supported(MDR_FEATURE_VOICE_GUIDANCE_VOLUME, -2, 2)) v.volume = value;
        else return MDR_RESULT_ERROR_NOT_SUPPORTED;
        return mdrHeadphonesSetVoiceGuidance(h, &v);
    }
    if (key == "connection.priority" && supported(MDR_FEATURE_CONNECTION_MODE, 1, 2)) {
        MDRConnectionMode v{static_cast<MDRAudioPriority>(value)};
        return mdrHeadphonesSetConnectionMode(h, &v);
    }
    if (key == "pairing.enabled" && supported(MDR_FEATURE_PAIRING_MODE, 0, 1)) {
        MDRPairing v{static_cast<MDRBoolean>(value)};
        return mdrHeadphonesSetPairing(h, &v);
    }
    if (key == "pairing.switch" && supported(MDR_FEATURE_SOURCE_SWITCH_CONTROL, 0, 1))
        return mdrHeadphonesSetSourceSwitchControl(h, value);
    if (key == "pairing.device" && supported(MDR_FEATURE_PAIRED_DEVICE_MANAGEMENT, 1, 4)) {
        if (target.size() != 17) return MDR_RESULT_ERROR_INVALID_ARGUMENT;
        const auto devices = getList<MDRPairedDevice>(s, mdrHeadphonesGetPairedDevices);
        if (std::none_of(devices.begin(), devices.end(), [&](const auto& d) {
                return target == std::string_view(d.macAddress, strnlen(d.macAddress, 18));
            })) return MDR_RESULT_ERROR_NOT_FOUND;
        MDRPairedDeviceAction v{static_cast<MDRPairedDeviceCommand>(value), target.data(),
                static_cast<uint32_t>(target.size())};
        return mdrHeadphonesSetPairedDevice(h, &v);
    }
    if (key == "general" && supported(MDR_FEATURE_GENERAL_SETTINGS, 0, 1)) {
        auto settings = getList<MDRGeneralSettingInfo>(s, mdrHeadphonesGetGeneralSettingInfo);
        for (const auto& info : settings) {
            if (target != std::to_string(info.index) || !info.writable
                    || info.type != MDR_GENERAL_SETTING_BOOLEAN) continue;
            MDRGeneralSetting v{info.index, static_cast<MDRBoolean>(value)};
            return mdrHeadphonesSetGeneralSetting(h, &v);
        }
        return MDR_RESULT_ERROR_NOT_SUPPORTED;
    }
    if (key == "assign" && supported(MDR_FEATURE_ASSIGNABLE_CONTROLS, 0, 10)) {
        auto controls = getList<MDRAssignableControl>(s, mdrHeadphonesGetAssignableControls);
        for (auto& control : controls) {
            if (target != std::to_string(control.location)) continue;
            std::array<MDRAssignableAction, 32> options{};
            uint32_t count = options.size();
            if (mdrHeadphonesGetAssignableControlActions(h, control.location, options.data(), &count)
                    != MDR_RESULT_OK || count > options.size()) return MDR_RESULT_ERROR_NOT_SUPPORTED;
            if (std::find(options.begin(), options.begin() + count, value) == options.begin() + count)
                return MDR_RESULT_ERROR_INVALID_ARGUMENT;
            control.action = value;
            return mdrHeadphonesSetAssignableControls(h, controls.data(), controls.size());
        }
        return MDR_RESULT_ERROR_NOT_FOUND;
    }
    if (key == "safe.preview" && supported(MDR_FEATURE_SAFE_LISTENING, 0, 1)) {
        MDRSafeListening v{};
        if (mdrHeadphonesGetSafeListening(h, &v) != MDR_RESULT_OK) return MDR_RESULT_ERROR_NOT_SUPPORTED;
        v.preview = value;
        return mdrHeadphonesSetSafeListening(h, &v);
    }
    return MDR_RESULT_ERROR_NOT_SUPPORTED;
}

jbyteArray bytes(JNIEnv* env, const std::string& value) {
    auto result = env->NewByteArray(value.size());
    if (result) env->SetByteArrayRegion(result, 0, value.size(),
            reinterpret_cast<const jbyte*>(value.data()));
    return result;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_xiaomi_haotian_sony_SonyNative_create(JNIEnv*, jclass, jint fd) {
    auto s = std::make_unique<Session>();
    s->fd.reset(fcntl(fd, F_DUPFD_CLOEXEC, 0));
    if (s->fd.get() < 0) return 0;
    int flags = fcntl(s->fd.get(), F_GETFL);
    if (flags < 0 || fcntl(s->fd.get(), F_SETFL, flags | O_NONBLOCK) < 0) return 0;
    s->connection.user = s.get();
    s->connection.connect = [](void*, const char*, const char*) -> MDRResult {
        return MDR_RESULT_ERROR_NOT_SUPPORTED; // An already connected Android socket is required.
    };
    s->connection.disconnect = [](void* user) { static_cast<Session*>(user)->fd.reset(); };
    s->connection.recv = [](void* user, char* dst, int count, int* received) -> MDRResult {
        auto* s = static_cast<Session*>(user);
        return socketResult(s, recv(s->fd.get(), dst, count, MSG_DONTWAIT), received);
    };
    s->connection.send = [](void* user, const char* src, int count, int* sent) -> MDRResult {
        auto* s = static_cast<Session*>(user);
        return socketResult(s, send(s->fd.get(), src, count, MSG_DONTWAIT | MSG_NOSIGNAL), sent);
    };
    s->connection.poll = [](void* user, int timeout) -> MDRResult {
        auto* s = static_cast<Session*>(user);
        pollfd p{s->fd.get(), POLLIN | POLLOUT, 0};
        int result = poll(&p, 1, timeout);
        if (result < 0 && errno == EINTR) return MDR_RESULT_ERROR_TIMEOUT;
        if (result < 0 || (p.revents & (POLLERR | POLLHUP | POLLNVAL))) {
            s->error = "RFCOMM disconnected";
            return MDR_RESULT_ERROR_NO_CONNECTION;
        }
        return result > 0 ? MDR_RESULT_OK : MDR_RESULT_ERROR_TIMEOUT;
    };
    s->connection.getLastError = [](void* user) { return static_cast<Session*>(user)->error.c_str(); };
    if (mdrHeadphonesCreate(MDR_ABI_VERSION, &s->connection, MDR_PROTOCOL_V2, &s->headphones)
            != MDR_RESULT_OK) return 0;
    return reinterpret_cast<jlong>(s.release());
}

extern "C" JNIEXPORT void JNICALL
Java_org_xiaomi_haotian_sony_SonyNative_destroy(JNIEnv*, jclass, jlong handle) {
    delete session(handle);
}
extern "C" JNIEXPORT jint JNICALL
Java_org_xiaomi_haotian_sony_SonyNative_poll(JNIEnv*, jclass, jlong handle) {
    auto* s = session(handle);
    if (!s) return -static_cast<jint>(MDR_RESULT_ERROR_NO_CONNECTION);
    MDREvent event = MDR_EVENT_NONE;
    MDRResult result = mdrHeadphonesPoll(s->headphones, &event);
    return result == MDR_RESULT_OK ? static_cast<jint>(event) : -static_cast<jint>(result);
}
extern "C" JNIEXPORT jint JNICALL
Java_org_xiaomi_haotian_sony_SonyNative_request(JNIEnv*, jclass, jlong handle, jint operation) {
    auto* s = session(handle);
    if (!s) return MDR_RESULT_ERROR_NO_CONNECTION;
    switch (operation) {
        case 0: return mdrHeadphonesRequestInit(s->headphones);
        case 1: return mdrHeadphonesRequestSync(s->headphones);
        case 2: return mdrHeadphonesRequestCommit(s->headphones);
        case 3: return mdrHeadphonesRequestReadback(s->headphones, s->readbackKey.c_str());
        default: return MDR_RESULT_ERROR_INVALID_ARGUMENT;
    }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_xiaomi_haotian_sony_SonyNative_ready(JNIEnv*, jclass, jlong handle) {
    auto* s = session(handle);
    return s && mdrHeadphonesIsInitialized(s->headphones) && mdrHeadphonesIsReady(s->headphones);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_xiaomi_haotian_sony_SonyNative_snapshot(JNIEnv* env, jclass, jlong handle) {
    auto* s = session(handle);
    return bytes(env, s ? snapshot(s) : "{}");
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_xiaomi_haotian_sony_SonyNative_error(JNIEnv* env, jclass, jlong handle) {
    auto* s = session(handle);
    return bytes(env, !s ? "No session" :
            !s->error.empty() ? s->error : getText(s, MDR_TEXT_LAST_ERROR));
}
extern "C" JNIEXPORT jint JNICALL
Java_org_xiaomi_haotian_sony_SonyNative_set(JNIEnv* env, jclass, jlong handle, jstring key,
        jint value, jstring target) {
    auto* s = session(handle);
    if (!s || !key || !target) return MDR_RESULT_ERROR_INVALID_ARGUMENT;
    const char* k = env->GetStringUTFChars(key, nullptr);
    if (!k) return MDR_RESULT_ERROR_GENERAL;
    const char* t = env->GetStringUTFChars(target, nullptr);
    if (!t) { env->ReleaseStringUTFChars(key, k); return MDR_RESULT_ERROR_GENERAL; }
    MDRResult result = setValue(s, k, value, t);
    if (result == MDR_RESULT_OK) s->readbackKey = k;
    env->ReleaseStringUTFChars(target, t);
    env->ReleaseStringUTFChars(key, k);
    return result;
}
