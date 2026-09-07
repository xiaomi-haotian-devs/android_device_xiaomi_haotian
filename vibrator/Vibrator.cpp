/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "xiaomi-sm8750-vibrator"

#include "Vibrator.h"

#include <android-base/properties.h>
#include <android/binder_status.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <log/log.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <thread>
#include <utility>

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

namespace {

constexpr char kInputDir[] = "/dev/input";
constexpr char kInputPrefix[] = "event";
constexpr char kSysfsInputDir[] = "/sys/class/input";
constexpr char kVibeStateSuffix[] = "/device/default/vibe_state";
constexpr int16_t kInvalidEffect = -1;
constexpr uint16_t kBuzzPeriodMs = 5;
constexpr uint16_t kRamWaveformBank = 0;
constexpr uint16_t kWaveformLong = 0;
constexpr uint16_t kWaveformClick = 2;
constexpr uint16_t kWaveformShort = 3;
constexpr uint16_t kWaveformThud = 4;
constexpr uint16_t kWaveformQuickRise = 6;
constexpr uint16_t kWaveformKeyboardTick = 7;
constexpr uint16_t kWaveformQuickFall = 8;
constexpr int32_t kMaxTimeoutMs = UINT16_MAX;
constexpr int32_t kComposeDelayMaxMs = 1000;
constexpr int32_t kComposeSizeMax = 16;
constexpr int32_t kDoubleClickPulseMs = 40;
constexpr int32_t kDoubleClickPeriodMs = 55;
// Provisional RAM playback estimates from warm-device mailbox traces. These
// exclude hibernation latency; observed completion, not these estimates, owns
// stop.
constexpr int32_t kGestureTickDurationMs = 40;
constexpr int32_t kKeyboardTickDurationMs = 35;
constexpr int32_t kCachedEffectMaxDurationMs = 40;
constexpr size_t kEffectCacheMaxSize = 16;
constexpr int32_t kVibeStateStopSlackMs = 80;
constexpr int32_t kVibeStartTimeoutMs = 100;
constexpr int32_t kVibeStatePollStepMs = 2;
constexpr int kVibeStateHaptic = 1;

constexpr uint8_t kLightGainPct = 40;
constexpr uint8_t kMediumGainPct = 60;
constexpr uint8_t kStrongGainPct = 82;
constexpr uint8_t kGestureTickGainPct = 80;
constexpr uint8_t kKeyboardTickGainPct = 75;

bool shouldCacheWaveform(uint16_t waveformIndex, int32_t timeoutMs) {
  return waveformIndex != kWaveformLong && timeoutMs > 0 &&
         timeoutMs <= kCachedEffectMaxDurationMs;
}

bool testBit(int bit, const unsigned long *array) {
  return (array[bit / (sizeof(unsigned long) * 8)] &
          (1UL << (bit % (sizeof(unsigned long) * 8)))) != 0;
}

bool hasPrefix(const char *value, const char *prefix) {
  return strncmp(value, prefix, strlen(prefix)) == 0;
}

bool isSupportedInputName(const char *name) {
  return strcmp(name, "cs40l26_input") == 0 ||
         strcmp(name, "cs40l26_vibra") == 0 ||
         strcmp(name, "cs40l26_dual_input") == 0;
}

ndk::ScopedAStatus unsupported() {
  return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ndk::ScopedAStatus serviceError(int error) {
  return ndk::ScopedAStatus::fromServiceSpecificError(error < 0 ? -error
                                                                : error);
}

} // namespace

bool Vibrator::CachedEffectKey::operator<(const CachedEffectKey &other) const {
  if (waveformIndex != other.waveformIndex) {
    return waveformIndex < other.waveformIndex;
  }
  if (durationMs != other.durationMs) {
    return durationMs < other.durationMs;
  }
  return gainPct < other.gainPct;
}

Vibrator::Vibrator() {
  std::lock_guard lock(mLock);
  openInputLocked();
  mWorker = std::thread(&Vibrator::workerLoop, this);
}

Vibrator::~Vibrator() {
  {
    std::lock_guard lock(mLock);
    mStopping = true;
    cancelSequenceLocked();
  }
  mCondition.notify_all();
  mWorker.join();
  std::lock_guard lock(mLock);
  eraseEffectLocked();
  eraseCachedEffectsLocked();
  if (mFd >= 0) {
    close(mFd);
    mFd = -1;
  }
}

bool Vibrator::openInputLocked() {
  if (mFd >= 0) {
    return true;
  }

  DIR *dir = opendir(kInputDir);
  if (dir == nullptr) {
    ALOGE("Failed to open %s: %s", kInputDir, strerror(errno));
    return false;
  }

  dirent *entry;
  while ((entry = readdir(dir)) != nullptr) {
    if (!hasPrefix(entry->d_name, kInputPrefix)) {
      continue;
    }

    std::string path = std::string(kInputDir) + "/" + entry->d_name;
    int fd = TEMP_FAILURE_RETRY(open(path.c_str(), O_RDWR | O_CLOEXEC));
    if (fd < 0) {
      ALOGD("Failed to open %s: %s", path.c_str(), strerror(errno));
      continue;
    }

    char name[256] = {};
    if (TEMP_FAILURE_RETRY(ioctl(fd, EVIOCGNAME(sizeof(name)), name)) < 0) {
      ALOGD("Failed to read input name for %s: %s", path.c_str(),
            strerror(errno));
      close(fd);
      continue;
    }

    if (!isSupportedInputName(name)) {
      close(fd);
      continue;
    }

    unsigned long ffBits[(FF_MAX / (sizeof(unsigned long) * 8)) + 1] = {};
    if (TEMP_FAILURE_RETRY(
            ioctl(fd, EVIOCGBIT(EV_FF, sizeof(ffBits)), ffBits)) < 0) {
      ALOGE("Failed to read FF capabilities for %s: %s", path.c_str(),
            strerror(errno));
      close(fd);
      continue;
    }

    if (!testBit(FF_PERIODIC, ffBits) ||
        (!testBit(FF_CUSTOM, ffBits) && !testBit(FF_SINE, ffBits))) {
      ALOGE("%s at %s lacks usable force-feedback waveform support", name,
            path.c_str());
      close(fd);
      continue;
    }

    mFd = fd;
    mInputPath = path;
    mInputName = name;
    mHasCustom = testBit(FF_CUSTOM, ffBits);
    mHasGain = testBit(FF_GAIN, ffBits);
    mHasSine = testBit(FF_SINE, ffBits);
    const char *eventName = strrchr(path.c_str(), '/');
    eventName = eventName == nullptr ? path.c_str() : eventName + 1;
    mVibeStatePath =
        std::string(kSysfsInputDir) + "/" + eventName + kVibeStateSuffix;
    mHasVibeState = access(mVibeStatePath.c_str(), R_OK) == 0;
    ALOGI("Using %s at %s, custom=%d gain=%d sine=%d period=%ums",
          mInputName.c_str(), mInputPath.c_str(), mHasCustom, mHasGain,
          testBit(FF_SINE, ffBits), kBuzzPeriodMs);
    preloadCachedEffectsLocked();
    closedir(dir);
    return true;
  }

  closedir(dir);
  ALOGE("No supported CS40L26 force-feedback input device found");
  return false;
}

int Vibrator::uploadCachedWaveformLocked(const CachedEffectKey &key) {
  auto cached = mEffectCache.find(key);
  if (cached != mEffectCache.end()) {
    return cached->second;
  }

  // Playback stops the preceding effect before requesting a cache entry.
  // Bound kernel FF slots as well as userspace memory for continuously varying
  // scales.
  if (mEffectCache.size() >= kEffectCacheMaxSize) {
    auto victim = mEffectCache.begin();
    if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCRMFF, victim->second)) < 0) {
      return -errno;
    }
    mEffectCache.erase(victim);
  }

  int16_t customData[] = {
      static_cast<int16_t>(kRamWaveformBank),
      static_cast<int16_t>(key.waveformIndex),
  };

  ff_effect effect = {};
  effect.type = FF_PERIODIC;
  effect.id = kInvalidEffect;
  effect.u.periodic.waveform = FF_CUSTOM;
  effect.u.periodic.magnitude = key.gainPct;
  effect.u.periodic.custom_data = customData;
  effect.u.periodic.custom_len = std::size(customData);
  effect.replay.length = static_cast<uint16_t>(key.durationMs);
  effect.replay.delay = 0;

  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCSFF, &effect)) < 0) {
    int error = errno;
    ALOGW("Failed to cache RAM waveform %u/%dms/%u%%: %s", key.waveformIndex,
          key.durationMs, key.gainPct, strerror(error));
    return -error;
  }

  mEffectCache[key] = effect.id;
  ALOGI("Cached RAM waveform %u/%dms/%u%% as FF effect %d", key.waveformIndex,
        key.durationMs, key.gainPct, effect.id);
  return effect.id;
}

void Vibrator::preloadCachedEffectsLocked() {
  if (mPreloadedEffects || !mHasCustom) {
    return;
  }

  mPreloadedEffects = true;
  const std::array<CachedEffectKey, 7> kPreloadEffects = {{
      {kWaveformClick, kGestureTickDurationMs, kLightGainPct},
      {kWaveformClick, kGestureTickDurationMs, kMediumGainPct},
      {kWaveformClick, kGestureTickDurationMs, kStrongGainPct},
      {kWaveformClick, kGestureTickDurationMs, 75},
      {kWaveformClick, kGestureTickDurationMs, kGestureTickGainPct},
      {kWaveformClick, kGestureTickDurationMs, 92},
      {kWaveformKeyboardTick, kKeyboardTickDurationMs, kKeyboardTickGainPct},
  }};

  for (const CachedEffectKey &key : kPreloadEffects) {
    uploadCachedWaveformLocked(key);
  }
}

void Vibrator::eraseCachedEffectsLocked() {
  if (mFd < 0) {
    mEffectCache.clear();
    return;
  }

  for (const auto &cachedEffect : mEffectCache) {
    const CachedEffectKey &key = cachedEffect.first;
    int16_t effect = cachedEffect.second;
    if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCRMFF, effect)) < 0) {
      ALOGW("Failed to erase cached RAM waveform %u/%dms/%u%% effect %d: %s",
            key.waveformIndex, key.durationMs, key.gainPct, effect,
            strerror(errno));
    }
  }

  mEffectCache.clear();
}

int Vibrator::eraseEffectLocked() {
  if (mFd < 0 || mCurrentEffect == kInvalidEffect) {
    mCurrentEffect = kInvalidEffect;
    mCurrentEffectCached = false;
    return 0;
  }

  int effect = mCurrentEffect;
  bool cached = mCurrentEffectCached;

  input_event stop = {};
  stop.type = EV_FF;
  stop.code = effect;
  stop.value = 0;
  ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &stop, sizeof(stop)));
  if (written != static_cast<ssize_t>(sizeof(stop))) {
    int error = written < 0 ? errno : EIO;
    ALOGW("Failed to stop FF effect %d before cleanup: %s", effect,
          strerror(error));
    return -error;
  }

  if (cached) {
    mCurrentEffect = kInvalidEffect;
    mCurrentEffectCached = false;
    return 0;
  }

  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCRMFF, effect)) < 0) {
    int error = errno;
    ALOGE("Failed to erase FF effect %d: %s", effect, strerror(error));
    return -error;
  }

  mCurrentEffect = kInvalidEffect;
  mCurrentEffectCached = false;
  return 0;
}

int Vibrator::setGainLocked(uint8_t gainPct) {
  if (!mHasGain) {
    return 0;
  }

  input_event gain = {};
  gain.type = EV_FF;
  gain.code = FF_GAIN;
  gain.value = gainPct;

  ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &gain, sizeof(gain)));
  if (written != static_cast<ssize_t>(sizeof(gain))) {
    int error = written < 0 ? errno : EIO;
    ALOGE("Failed to set FF_GAIN %u%%: %s", gainPct, strerror(error));
    return -error;
  }

  return 0;
}

int Vibrator::playWaveformLocked(uint16_t waveformIndex, int32_t timeoutMs,
                                 uint8_t gainPct) {
  if (timeoutMs <= 0 || timeoutMs > kMaxTimeoutMs) {
    return -EINVAL;
  }

  if (!openInputLocked()) {
    return -ENODEV;
  }

  if (!mHasCustom) {
    return -ENOTSUP;
  }

  int ret = eraseEffectLocked();
  if (ret != 0) {
    return ret;
  }

  ret = setGainLocked(gainPct);
  if (ret != 0) {
    return ret;
  }

  if (shouldCacheWaveform(waveformIndex, timeoutMs)) {
    CachedEffectKey key = {waveformIndex, timeoutMs, gainPct};
    int effect = uploadCachedWaveformLocked(key);
    if (effect < 0) {
      return effect;
    }

    mCurrentEffect = effect;
    mCurrentEffectCached = true;

    input_event play = {};
    play.type = EV_FF;
    play.code = mCurrentEffect;
    play.value = 1;

    ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &play, sizeof(play)));
    if (written != static_cast<ssize_t>(sizeof(play))) {
      int error = written < 0 ? errno : EIO;
      ALOGE("Failed to start cached RAM waveform %u effect %d: %s",
            waveformIndex, mCurrentEffect, strerror(error));
      mCurrentEffect = kInvalidEffect;
      mCurrentEffectCached = false;
      return -error;
    }

    return 0;
  }

  int16_t customData[] = {
      static_cast<int16_t>(kRamWaveformBank),
      static_cast<int16_t>(waveformIndex),
  };

  ff_effect effect = {};
  effect.type = FF_PERIODIC;
  effect.id = kInvalidEffect;
  effect.u.periodic.waveform = FF_CUSTOM;
  effect.u.periodic.magnitude = gainPct;
  effect.u.periodic.custom_data = customData;
  effect.u.periodic.custom_len = std::size(customData);
  effect.replay.length = static_cast<uint16_t>(timeoutMs);
  effect.replay.delay = 0;

  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCSFF, &effect)) < 0) {
    int error = errno;
    ALOGE("Failed to upload RAM waveform %u: %s", waveformIndex,
          strerror(error));
    return -error;
  }

  mCurrentEffect = effect.id;
  mCurrentEffectCached = false;

  input_event play = {};
  play.type = EV_FF;
  play.code = mCurrentEffect;
  play.value = 1;

  ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &play, sizeof(play)));
  if (written != static_cast<ssize_t>(sizeof(play))) {
    int error = written < 0 ? errno : EIO;
    ALOGE("Failed to start RAM waveform %u effect %d: %s", waveformIndex,
          mCurrentEffect, strerror(error));
    eraseEffectLocked();
    return -error;
  }

  return 0;
}

int Vibrator::playSineLocked(int32_t timeoutMs, uint8_t level) {
  if (timeoutMs <= 0 || timeoutMs > kMaxTimeoutMs) {
    return -EINVAL;
  }

  if (!openInputLocked()) {
    return -ENODEV;
  }
  if (!mHasSine) {
    return -ENOTSUP;
  }

  int ret = eraseEffectLocked();
  if (ret != 0) {
    return ret;
  }

  ret = setGainLocked(level);
  if (ret != 0) {
    return ret;
  }

  ff_effect effect = {};
  effect.type = FF_PERIODIC;
  effect.id = kInvalidEffect;
  effect.u.periodic.waveform = FF_SINE;
  effect.u.periodic.period = kBuzzPeriodMs;
  effect.u.periodic.magnitude = level;
  effect.replay.length = static_cast<uint16_t>(timeoutMs);
  effect.replay.delay = 0;

  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCSFF, &effect)) < 0) {
    int error = errno;
    ALOGE("Failed to upload FF_SINE effect: %s", strerror(error));
    return -error;
  }

  mCurrentEffect = effect.id;
  mCurrentEffectCached = false;

  input_event play = {};
  play.type = EV_FF;
  play.code = mCurrentEffect;
  play.value = 1;

  ssize_t written = TEMP_FAILURE_RETRY(write(mFd, &play, sizeof(play)));
  if (written != static_cast<ssize_t>(sizeof(play))) {
    int error = written < 0 ? errno : EIO;
    ALOGE("Failed to start FF effect %d: %s", mCurrentEffect, strerror(error));
    eraseEffectLocked();
    return -error;
  }

  return 0;
}

int Vibrator::playHapticLocked(uint16_t waveformIndex, int32_t timeoutMs,
                               uint8_t gainPct) {
  int ret = playWaveformLocked(waveformIndex, timeoutMs, gainPct);
  if (ret == 0 || ret == -ENODEV) {
    return ret;
  }

  ALOGW("Falling back to FF_SINE for waveform %u after error: %s",
        waveformIndex, strerror(-ret));
  return playSineLocked(timeoutMs, gainPct);
}

int Vibrator::readVibeState() const {
  if (!mHasVibeState || mVibeStatePath.empty()) {
    return -ENODEV;
  }

  int fd =
      TEMP_FAILURE_RETRY(open(mVibeStatePath.c_str(), O_RDONLY | O_CLOEXEC));
  if (fd < 0) {
    return -errno;
  }

  char value[16] = {};
  ssize_t bytes = TEMP_FAILURE_RETRY(read(fd, value, sizeof(value) - 1));
  int error = bytes < 0 ? errno : 0;
  close(fd);
  if (bytes <= 0) {
    return error == 0 ? -EIO : -error;
  }

  char *end = nullptr;
  long state = strtol(value, &end, 10);
  if (end == value) {
    return -EINVAL;
  }

  return static_cast<int>(state);
}

int Vibrator::cancelSequenceLocked() {
  mActive = false;
  mPulses.clear();
  mNextPulse = 0;
  mCallback.reset();
  mPlaybackPending = false;
  mHardwareSequence = false;
  mCondition.notify_all();
  return eraseEffectLocked();
}

void Vibrator::trackPlaybackLocked(int32_t durationMs, int32_t initialDelayMs) {
  const auto now = std::chrono::steady_clock::now();
  mPlaybackPending = true;
  mSawStart = false;
  mStartDeadline =
      now + std::chrono::milliseconds(initialDelayMs + kVibeStartTimeoutMs);
  mFallbackEnd = mStartDeadline + std::chrono::milliseconds(durationMs);
  mPlaybackDeadline =
      mFallbackEnd + std::chrono::milliseconds(kVibeStateStopSlackMs);
}

bool Vibrator::playbackCompleteLocked() {
  if (!mPlaybackPending)
    return true;
  const auto now = std::chrono::steady_clock::now();
  const int state = readVibeState();
  if (state == 0)
    mSawIdle = true;
  if (state == kVibeStateHaptic && mSawIdle)
    mSawStart = true;
  if (mSawStart && state == 0) {
    mPlaybackPending = false;
    return true;
  }
  // An idle value without a witnessed start is not a completion. Allow the
  // asynchronous driver to wake and play before the timed fallback can stop it.
  if ((!mSawStart && now >= mFallbackEnd) || now >= mPlaybackDeadline) {
    ALOGW("Haptic completion timeout: started=%d state=%d", mSawStart, state);
    mPlaybackPending = false;
    return true;
  }
  return false;
}

int Vibrator::playCompositionLocked(const std::vector<Pulse> &pulses,
                                    int32_t durationMs) {
  if (!mHasCustom || !mHasGain || mOwtRejected || pulses.empty())
    return -ENOTSUP;
  // CS40L26 composite OWT: 24-bit big-endian DSP words in 32-bit containers.
  // Header = padding, section count, repeat. Each section = amplitude, RAM
  // index, repeat, flags, 16-bit post-delay. No private Xiaomi effect IDs.
  const size_t count = pulses.size() + (pulses.front().startMs > 0 ? 1 : 0);
  std::vector<int16_t> data((4 + count * 8) / sizeof(int16_t), 0);
  auto *bytes = reinterpret_cast<uint8_t *>(data.data());
  bytes[2] = static_cast<uint8_t>(count);
  size_t offset = 4;
  auto append = [&](uint8_t gain, uint16_t wave, int32_t delay) {
    bytes[offset + 1] = gain;
    bytes[offset + 2] = static_cast<uint8_t>(wave);
    bytes[offset + 6] = static_cast<uint8_t>(delay >> 8);
    bytes[offset + 7] = static_cast<uint8_t>(delay);
    offset += 8;
  };
  if (pulses.front().startMs > 0)
    append(0, 0, pulses.front().startMs);
  for (size_t i = 0; i < pulses.size(); ++i) {
    const auto &pulse = pulses[i];
    const int32_t nextStart =
        i + 1 < pulses.size() ? pulses[i + 1].startMs : durationMs;
    append(pulse.gainPct, pulse.waveformIndex,
           nextStart - pulse.startMs - pulse.durationMs);
  }
  ff_effect effect = {};
  effect.type = FF_PERIODIC;
  effect.id = kInvalidEffect;
  effect.u.periodic.waveform = FF_CUSTOM;
  effect.u.periodic.custom_data = data.data();
  effect.u.periodic.custom_len = data.size();
  // Firmware owns section durations. A nonzero replay length can truncate OWT.
  effect.replay.length = 0;
  if (TEMP_FAILURE_RETRY(ioctl(mFd, EVIOCSFF, &effect)) < 0) {
    const int error = errno;
    if (error == EINVAL || error == ENOTSUP)
      mOwtRejected = true;
    return -error;
  }
  mCurrentEffect = effect.id;
  mCurrentEffectCached = false;
  // Per-section gains already contain calibration. Do not scale them twice.
  int ret = setGainLocked(100);
  if (ret != 0)
    return ret;
  input_event play = {};
  play.type = EV_FF;
  play.code = effect.id;
  play.value = 1;
  const auto written = TEMP_FAILURE_RETRY(write(mFd, &play, sizeof(play)));
  return written == static_cast<ssize_t>(sizeof(play))
             ? 0
             : -(written < 0 ? errno : EIO);
}

int Vibrator::startSequenceLocked(
    std::vector<Pulse> pulses, int32_t durationMs,
    const std::shared_ptr<IVibratorCallback> &callback) {
  int ret = cancelSequenceLocked();
  if (ret != 0) {
    return ret;
  }
  if (!pulses.empty() && !openInputLocked())
    return -ENODEV;
  mSawIdle = readVibeState() == 0;
  if (pulses.size() > 1) {
    ret = playCompositionLocked(pulses, durationMs);
    if (ret == 0) {
      mHardwareSequence = true;
      trackPlaybackLocked(durationMs + static_cast<int32_t>(pulses.size()) * 50,
                          pulses.front().startMs);
      mSequenceStart = std::chrono::steady_clock::now();
      mSequenceEnd = mSequenceStart + std::chrono::milliseconds(durationMs);
      mPulses = std::move(pulses);
      mNextPulse = mPulses.size();
      mCallback = callback;
      mActive = true;
      mCondition.notify_all();
      return 0;
    }
    ALOGW("OWT composition unavailable (%s); using serialized playback",
          strerror(-ret));
    ret = eraseEffectLocked();
    if (ret != 0)
      return ret;
  }
  // Return errors for the first immediate pulse to the Binder caller.
  size_t nextPulse = 0;
  if (!pulses.empty() && pulses.front().startMs == 0) {
    const auto &pulse = pulses.front();
    ret =
        playHapticLocked(pulse.waveformIndex, pulse.durationMs, pulse.gainPct);
    if (ret != 0) {
      eraseEffectLocked();
      return ret;
    }
    nextPulse = 1;
    trackPlaybackLocked(pulse.durationMs);
  }
  mSequenceStart = std::chrono::steady_clock::now();
  mSequenceEnd = mSequenceStart + std::chrono::milliseconds(durationMs);
  mPulses = std::move(pulses);
  mNextPulse = nextPulse;
  mCallback = callback;
  mActive = true;
  mCondition.notify_all();
  return 0;
}

void Vibrator::workerLoop() {
  using namespace std::chrono;
  std::unique_lock lock(mLock);
  while (!mStopping) {
    if (!mActive) {
      mCondition.wait(lock, [this] { return mStopping || mActive; });
      continue;
    }
    if (mPlaybackPending) {
      if (!playbackCompleteLocked()) {
        mCondition.wait_for(lock, milliseconds(kVibeStatePollStepMs));
        continue;
      }
      // Software fallback follows actual completion, then the requested pause.
      // Never interrupt a still-starting pulse to meet an estimated deadline.
      if (!mHardwareSequence && mNextPulse > 0) {
        const auto &previous = mPulses[mNextPulse - 1];
        const auto nominalEnd =
            mSequenceStart +
            milliseconds(previous.startMs + previous.durationMs);
        const auto actualEnd = steady_clock::now();
        if (actualEnd > nominalEnd) {
          const auto drift = actualEnd - nominalEnd;
          mSequenceStart += drift;
          mSequenceEnd += drift;
        }
      }
    }
    auto deadline = mSequenceEnd;
    if (mNextPulse < mPulses.size()) {
      deadline = mSequenceStart + milliseconds(mPulses[mNextPulse].startMs);
    }
    if (steady_clock::now() < deadline) {
      // Every wakeup re-evaluates the current sequence, including cancellation.
      mCondition.wait_until(lock, deadline);
      continue;
    }
    if (mNextPulse < mPulses.size()) {
      const auto pulse = mPulses[mNextPulse++];
      mSawIdle = readVibeState() == 0;
      int ret = playHapticLocked(pulse.waveformIndex, pulse.durationMs,
                                 pulse.gainPct);
      if (ret != 0) {
        ALOGE("Failed to play composed pulse: %s", strerror(-ret));
        // Terminate the failed sequence and release the framework's completion
        // wait.
        mNextPulse = mPulses.size();
        mSequenceEnd = steady_clock::now();
        eraseEffectLocked();
      } else {
        trackPlaybackLocked(pulse.durationMs);
      }
      continue;
    }
    auto callback = mCallback;
    int ret = cancelSequenceLocked();
    if (ret != 0) {
      ALOGE("Failed to clean up completed haptic effect: %s", strerror(-ret));
    }
    // Cleanup precedes notification: a re-entrant client may start the next
    // effect.
    lock.unlock();
    if (callback != nullptr && !callback->onComplete().isOk()) {
      ALOGE("Failed to notify vibration completion");
    }
    lock.lock();
  }
}

int32_t Vibrator::durationForEffect(Effect effect) const {
  switch (effect) {
  case Effect::CLICK:
    return 40;
  case Effect::TICK:
  case Effect::TEXTURE_TICK:
    return kGestureTickDurationMs;
  case Effect::DOUBLE_CLICK:
    return kDoubleClickPeriodMs + kDoubleClickPulseMs;
  case Effect::THUD:
    return 40;
  case Effect::POP:
    return 40;
  case Effect::HEAVY_CLICK:
    return 40;
  default:
    return 0;
  }
}

uint16_t Vibrator::waveformForEffect(Effect effect) const {
  switch (effect) {
  case Effect::CLICK:
  case Effect::DOUBLE_CLICK:
  case Effect::HEAVY_CLICK:
    return kWaveformClick;
  case Effect::TICK:
  case Effect::TEXTURE_TICK:
    return kWaveformClick;
  case Effect::THUD:
    return kWaveformThud;
  case Effect::POP:
    return kWaveformQuickFall;
  default:
    return kWaveformShort;
  }
}

uint8_t Vibrator::gainForEffect(Effect effect, EffectStrength strength) const {
  if (effect == Effect::TICK || effect == Effect::TEXTURE_TICK) {
    switch (strength) {
    case EffectStrength::LIGHT:
      return tuneGain(75, 1.0f);
    case EffectStrength::MEDIUM:
      return tuneGain(kGestureTickGainPct, 1.0f);
    case EffectStrength::STRONG:
      return tuneGain(92, 1.0f);
    default:
      return 0;
    }
  }

  int gain;
  switch (strength) {
  case EffectStrength::LIGHT:
    gain = kLightGainPct;
    break;
  case EffectStrength::MEDIUM:
    gain = kMediumGainPct;
    break;
  case EffectStrength::STRONG:
    gain = kStrongGainPct;
    break;
  default:
    return 0;
  }

  switch (effect) {
  case Effect::TICK:
  case Effect::TEXTURE_TICK:
    gain -= 5;
    break;
  case Effect::POP:
    gain -= 5;
    break;
  case Effect::HEAVY_CLICK:
  case Effect::THUD:
    gain += 8;
    break;
  default:
    break;
  }

  if (gain < 25) {
    gain = 25;
  } else if (gain > 92) {
    gain = 92;
  }

  return tuneGain(static_cast<uint8_t>(gain), 1.0f);
}

int32_t Vibrator::durationForPrimitive(CompositePrimitive primitive) const {
  switch (primitive) {
  case CompositePrimitive::NOOP:
    return 0;
  case CompositePrimitive::CLICK:
    return 40;
  case CompositePrimitive::THUD:
    return 40;
  case CompositePrimitive::QUICK_RISE:
    return 30;
  case CompositePrimitive::QUICK_FALL:
    return 40;
  case CompositePrimitive::LIGHT_TICK:
    return kGestureTickDurationMs;
  case CompositePrimitive::LOW_TICK:
    return kKeyboardTickDurationMs;
  default:
    return 0;
  }
}

uint16_t Vibrator::waveformForPrimitive(CompositePrimitive primitive) const {
  switch (primitive) {
  case CompositePrimitive::CLICK:
    return kWaveformClick;
  case CompositePrimitive::THUD:
    return kWaveformThud;
  case CompositePrimitive::QUICK_RISE:
    return kWaveformQuickRise;
  case CompositePrimitive::QUICK_FALL:
    return kWaveformQuickFall;
  case CompositePrimitive::LIGHT_TICK:
    return kWaveformClick;
  case CompositePrimitive::LOW_TICK:
    return kWaveformKeyboardTick;
  default:
    return kWaveformShort;
  }
}

uint8_t Vibrator::gainForPrimitive(CompositePrimitive primitive,
                                   float scale) const {
  if (scale <= 0.0f || primitive == CompositePrimitive::NOOP) {
    return 0;
  }

  // Preserve tuned full-scale gains while allowing AOSP's entire scale range.
  // LIGHT_TICK is the HAL name for framework PRIMITIVE_TICK (both ID 7).
  int fullScaleGain = kStrongGainPct;
  switch (primitive) {
  case CompositePrimitive::LIGHT_TICK:
    fullScaleGain = kGestureTickGainPct;
    break;
  case CompositePrimitive::LOW_TICK:
    fullScaleGain = kKeyboardTickGainPct;
    break;
  case CompositePrimitive::THUD:
    fullScaleGain += 8;
    break;
  default:
    break;
  }

  return tuneGain(static_cast<uint8_t>(fullScaleGain), scale);
}

uint8_t Vibrator::tuneGain(uint8_t fullScaleGain, float scale) const {
  if (scale <= 0.0f)
    return 0;
  const std::string style = ::android::base::GetProperty(
      "persist.sys.haotian.haptics.style", "balanced");
  const bool enhance = ::android::base::GetBoolProperty(
      "persist.sys.haotian.haptics.enhance", true);
  const float multiplier = style == "soft"    ? 0.8f
                           : style == "crisp" ? 1.1f
                                              : 1.0f;
  // Continuous, monotonic compression preserves silence and ramp ordering.
  // Unlike a fixed minimum gain, this does not flatten all quiet primitives.
  const float response =
      std::pow(std::clamp(scale, 0.0f, 1.0f), enhance ? 0.5f : 1.0f);
  return static_cast<uint8_t>(
      std::clamp(std::lround(fullScaleGain * multiplier * response), 1L, 92L));
}

ndk::ScopedAStatus Vibrator::getCapabilities(int32_t *_aidl_return) {
  std::lock_guard lock(mLock);
  openInputLocked();
  *_aidl_return = IVibrator::CAP_ON_CALLBACK | IVibrator::CAP_PERFORM_CALLBACK |
                  IVibrator::CAP_COMPOSE_EFFECTS;
  if (mHasGain) {
    *_aidl_return |= IVibrator::CAP_AMPLITUDE_CONTROL;
  }
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::off() {
  std::lock_guard lock(mLock);
  int ret = cancelSequenceLocked();
  return ret == 0 ? ndk::ScopedAStatus::ok() : serviceError(ret);
}

ndk::ScopedAStatus
Vibrator::on(int32_t timeoutMs,
             const std::shared_ptr<IVibratorCallback> &callback) {
  if (timeoutMs <= 0 || timeoutMs > kMaxTimeoutMs) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
  }
  std::lock_guard lock(mLock);
  int ret = startSequenceLocked(
      {{0, kWaveformLong, timeoutMs, tuneGain(kStrongGainPct, 1.0f)}},
      timeoutMs, callback);
  return ret == 0 ? ndk::ScopedAStatus::ok() : serviceError(ret);
}

ndk::ScopedAStatus
Vibrator::perform(Effect effect, EffectStrength strength,
                  const std::shared_ptr<IVibratorCallback> &callback,
                  int32_t *_aidl_return) {
  int32_t durationMs = durationForEffect(effect);
  uint16_t waveformIndex = waveformForEffect(effect);
  uint8_t gainPct = gainForEffect(effect, strength);
  if (durationMs == 0 || gainPct == 0) {
    return unsupported();
  }

  std::vector<Pulse> pulses = {
      {0, waveformIndex,
       effect == Effect::DOUBLE_CLICK ? kDoubleClickPulseMs : durationMs,
       gainPct}};
  if (effect == Effect::DOUBLE_CLICK) {
    pulses.push_back(
        {kDoubleClickPeriodMs, waveformIndex, kDoubleClickPulseMs, gainPct});
  }
  std::lock_guard lock(mLock);
  int ret = startSequenceLocked(std::move(pulses), durationMs, callback);
  if (ret != 0) {
    *_aidl_return = 0;
    return serviceError(ret);
  }
  *_aidl_return = durationMs;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::getSupportedEffects(std::vector<Effect> *_aidl_return) {
  *_aidl_return = {
      Effect::CLICK, Effect::DOUBLE_CLICK, Effect::TICK,        Effect::THUD,
      Effect::POP,   Effect::HEAVY_CLICK,  Effect::TEXTURE_TICK};
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setAmplitude(float amplitude) {
  if (!std::isfinite(amplitude) || amplitude <= 0.0f || amplitude > 1.0f) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
  }
  std::lock_guard lock(mLock);
  if (!openInputLocked()) {
    return serviceError(ENODEV);
  }
  if (!mHasGain) {
    return unsupported();
  }
  // This Xiaomi CS40L26 driver accepts percent gains, not evdev's usual 0xffff.
  // Match on() at amplitude=1, including the selected device-wide profile.
  const auto gain = tuneGain(kStrongGainPct, amplitude);
  int ret = setGainLocked(gain);
  return ret == 0 ? ndk::ScopedAStatus::ok() : serviceError(ret);
}

ndk::ScopedAStatus Vibrator::setExternalControl(bool /*enabled*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getCompositionDelayMax(int32_t *maxDelayMs) {
  *maxDelayMs = kComposeDelayMaxMs;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompositionSizeMax(int32_t *maxSize) {
  *maxSize = kComposeSizeMax;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::getSupportedPrimitives(std::vector<CompositePrimitive> *supported) {
  *supported = {CompositePrimitive::NOOP,       CompositePrimitive::CLICK,
                CompositePrimitive::THUD,       CompositePrimitive::QUICK_RISE,
                CompositePrimitive::QUICK_FALL, CompositePrimitive::LIGHT_TICK,
                CompositePrimitive::LOW_TICK};
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDuration(CompositePrimitive primitive,
                                                  int32_t *durationMs) {
  int32_t duration = durationForPrimitive(primitive);
  if (primitive != CompositePrimitive::NOOP && duration == 0) {
    return unsupported();
  }

  *durationMs = duration;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::compose(const std::vector<CompositeEffect> &composite,
                  const std::shared_ptr<IVibratorCallback> &callback) {
  if (composite.empty() || composite.size() > kComposeSizeMax) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
  }

  int32_t totalDurationMs = 0;
  std::vector<Pulse> pulses;
  pulses.reserve(composite.size());
  for (const CompositeEffect &effect : composite) {
    if (effect.delayMs < 0 || effect.delayMs > kComposeDelayMaxMs ||
        !std::isfinite(effect.scale) || effect.scale < 0.0f ||
        effect.scale > 1.0f) {
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    int32_t primitiveDuration = durationForPrimitive(effect.primitive);
    if (effect.primitive != CompositePrimitive::NOOP &&
        primitiveDuration == 0) {
      return unsupported();
    }

    totalDurationMs += effect.delayMs;
    uint8_t gainPct = gainForPrimitive(effect.primitive, effect.scale);
    if (gainPct > 0) {
      pulses.push_back({totalDurationMs, waveformForPrimitive(effect.primitive),
                        primitiveDuration, gainPct});
    }
    totalDurationMs += primitiveDuration;
  }
  std::lock_guard lock(mLock);
  int ret = startSequenceLocked(std::move(pulses), totalDurationMs, callback);
  return ret == 0 ? ndk::ScopedAStatus::ok() : serviceError(ret);
}

ndk::ScopedAStatus
Vibrator::getSupportedAlwaysOnEffects(std::vector<Effect> *_aidl_return) {
  _aidl_return->clear();
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::alwaysOnEnable(int32_t /*id*/, Effect /*effect*/,
                                            EffectStrength /*strength*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::alwaysOnDisable(int32_t /*id*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getResonantFrequency(float * /*resonantFreqHz*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getQFactor(float * /*qFactor*/) {
  return unsupported();
}

ndk::ScopedAStatus
Vibrator::getFrequencyResolution(float * /*freqResolutionHz*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getFrequencyMinimum(float * /*freqMinimumHz*/) {
  return unsupported();
}

ndk::ScopedAStatus
Vibrator::getBandwidthAmplitudeMap(std::vector<float> * /*_aidl_return*/) {
  return unsupported();
}

ndk::ScopedAStatus
Vibrator::getPwlePrimitiveDurationMax(int32_t * /*durationMs*/) {
  return unsupported();
}

ndk::ScopedAStatus Vibrator::getPwleCompositionSizeMax(int32_t * /*maxSize*/) {
  return unsupported();
}

ndk::ScopedAStatus
Vibrator::getSupportedBraking(std::vector<Braking> *supported) {
  supported->clear();
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus
Vibrator::composePwle(const std::vector<PrimitivePwle> & /*composite*/,
                      const std::shared_ptr<IVibratorCallback> & /*callback*/) {
  return unsupported();
}

} // namespace vibrator
} // namespace hardware
} // namespace android
} // namespace aidl
