/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/android/hardware/vibrator/BnVibrator.h>

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

class Vibrator : public BnVibrator {
public:
  Vibrator();
  ~Vibrator() override;

  ndk::ScopedAStatus getCapabilities(int32_t *_aidl_return) override;
  ndk::ScopedAStatus off() override;
  ndk::ScopedAStatus
  on(int32_t timeoutMs,
     const std::shared_ptr<IVibratorCallback> &callback) override;
  ndk::ScopedAStatus perform(Effect effect, EffectStrength strength,
                             const std::shared_ptr<IVibratorCallback> &callback,
                             int32_t *_aidl_return) override;
  ndk::ScopedAStatus
  getSupportedEffects(std::vector<Effect> *_aidl_return) override;
  ndk::ScopedAStatus setAmplitude(float amplitude) override;
  ndk::ScopedAStatus setExternalControl(bool enabled) override;
  ndk::ScopedAStatus getCompositionDelayMax(int32_t *maxDelayMs) override;
  ndk::ScopedAStatus getCompositionSizeMax(int32_t *maxSize) override;
  ndk::ScopedAStatus
  getSupportedPrimitives(std::vector<CompositePrimitive> *supported) override;
  ndk::ScopedAStatus getPrimitiveDuration(CompositePrimitive primitive,
                                          int32_t *durationMs) override;
  ndk::ScopedAStatus
  compose(const std::vector<CompositeEffect> &composite,
          const std::shared_ptr<IVibratorCallback> &callback) override;
  ndk::ScopedAStatus
  getSupportedAlwaysOnEffects(std::vector<Effect> *_aidl_return) override;
  ndk::ScopedAStatus alwaysOnEnable(int32_t id, Effect effect,
                                    EffectStrength strength) override;
  ndk::ScopedAStatus alwaysOnDisable(int32_t id) override;
  ndk::ScopedAStatus getResonantFrequency(float *resonantFreqHz) override;
  ndk::ScopedAStatus getQFactor(float *qFactor) override;
  ndk::ScopedAStatus getFrequencyResolution(float *freqResolutionHz) override;
  ndk::ScopedAStatus getFrequencyMinimum(float *freqMinimumHz) override;
  ndk::ScopedAStatus
  getBandwidthAmplitudeMap(std::vector<float> *_aidl_return) override;
  ndk::ScopedAStatus getPwlePrimitiveDurationMax(int32_t *durationMs) override;
  ndk::ScopedAStatus getPwleCompositionSizeMax(int32_t *maxSize) override;
  ndk::ScopedAStatus
  getSupportedBraking(std::vector<Braking> *supported) override;
  ndk::ScopedAStatus
  composePwle(const std::vector<PrimitivePwle> &composite,
              const std::shared_ptr<IVibratorCallback> &callback) override;

private:
  struct CachedEffectKey {
    uint16_t waveformIndex;
    int32_t durationMs;
    uint8_t gainPct;

    bool operator<(const CachedEffectKey &other) const;
  };

  bool openInputLocked();
  int setGainLocked(uint8_t gainPct);
  int uploadCachedWaveformLocked(const CachedEffectKey &key);
  void preloadCachedEffectsLocked();
  void eraseCachedEffectsLocked();
  int playWaveformLocked(uint16_t waveformIndex, int32_t timeoutMs,
                         uint8_t gainPct);
  int playSineLocked(int32_t timeoutMs, uint8_t level);
  int playHapticLocked(uint16_t waveformIndex, int32_t timeoutMs,
                       uint8_t gainPct);
  int eraseEffectLocked();
  int readVibeState() const;
  struct Pulse {
    int32_t startMs;
    uint16_t waveformIndex;
    int32_t durationMs;
    uint8_t gainPct;
  };
  int playCompositionLocked(const std::vector<Pulse> &pulses,
                            int32_t durationMs);
  void trackPlaybackLocked(int32_t durationMs, int32_t initialDelayMs = 0);
  bool playbackCompleteLocked();
  uint8_t tuneGain(uint8_t fullScaleGain, float scale) const;
  int startSequenceLocked(std::vector<Pulse> pulses, int32_t durationMs,
                          const std::shared_ptr<IVibratorCallback> &callback);
  int cancelSequenceLocked();
  void workerLoop();
  int32_t durationForEffect(Effect effect) const;
  int32_t durationForPrimitive(CompositePrimitive primitive) const;
  uint8_t gainForEffect(Effect effect, EffectStrength strength) const;
  uint8_t gainForPrimitive(CompositePrimitive primitive, float scale) const;
  uint16_t waveformForEffect(Effect effect) const;
  uint16_t waveformForPrimitive(CompositePrimitive primitive) const;
  std::mutex mLock;
  std::condition_variable mCondition;
  std::thread mWorker;
  bool mStopping = false;
  bool mActive = false;
  bool mHardwareSequence = false;
  bool mPlaybackPending = false;
  bool mSawIdle = false;
  bool mSawStart = false;
  bool mOwtRejected = false;
  std::vector<Pulse> mPulses;
  size_t mNextPulse = 0;
  std::chrono::steady_clock::time_point mSequenceStart;
  std::chrono::steady_clock::time_point mSequenceEnd;
  std::chrono::steady_clock::time_point mStartDeadline;
  std::chrono::steady_clock::time_point mPlaybackDeadline;
  std::chrono::steady_clock::time_point mFallbackEnd;
  std::shared_ptr<IVibratorCallback> mCallback;
  int mFd = -1;
  int16_t mCurrentEffect = -1;
  bool mCurrentEffectCached = false;
  std::string mInputPath;
  std::string mInputName;
  std::string mVibeStatePath;
  bool mHasCustom = false;
  bool mHasGain = false;
  bool mHasSine = false;
  bool mHasVibeState = false;
  bool mPreloadedEffects = false;
  std::map<CachedEffectKey, int16_t> mEffectCache;
};

} // namespace vibrator
} // namespace hardware
} // namespace android
} // namespace aidl
