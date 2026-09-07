/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.media.Spatializer;
import android.media.audiofx.AudioEffect;
import android.provider.Settings;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

final class AudioEffectController {
    static final String PREFERENCES = "haotian_audio";
    static final String KEY_IMPLEMENTATION = "effect_implementation";
    static final String KEY_SPATIAL = "spatial_enabled";
    static final String KEY_HEAD_TRACKING = "head_tracking_enabled";
    static final String KEY_DOLBY_PROFILE = "dolby_profile";
    static final String KEY_DOLBY_EQ = "dolby_eq_preset";
    static final String KEY_MISOUND_PROFILE = "misound_profile";
    static final String KEY_MISOUND_SURROUND = "misound_virtual_surround";
    static final String KEY_MISOUND_EQ = "misound_eq_preset";
    static final String KEY_DOLBY_BAND_PREFIX = "dolby_band_";
    static final String KEY_MISOUND_BAND_PREFIX = "misound_band_";

    static final String IMPLEMENTATION_DOLBY = AudioDeviceProfile.IMPLEMENTATION_DOLBY;
    static final String IMPLEMENTATION_MISOUND = AudioDeviceProfile.IMPLEMENTATION_MISOUND;
    static final String IMPLEMENTATION_NONE = AudioDeviceProfile.IMPLEMENTATION_NONE;

    private static final String TAG = "HaotianAudio";
    private static final UUID EFFECT_TYPE_NULL =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");
    private static final UUID DOLBY_UUID =
            UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");
    private static final UUID MISOUND_UUID =
            UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b");

    private static final int DOLBY_COMMAND = 5;
    private static final int DOLBY_PARAM_ENABLE = 0;
    private static final int DOLBY_PARAM_PROFILE = 0x0a000000;
    private static final int DOLBY_PROFILE_PARAM = 0x01000000;
    private static final int DOLBY_GEQ_ENABLE = 0x6a;
    private static final int DOLBY_GEQ_GAINS = 0x6e;
    private static final int MISOUND_PARAM_EQ_BAND = 0x02;
    private static final int MISOUND_PARAM_HEARING_ENABLE = 0x09;
    private static final int MISOUND_PARAM_HEARING_LEFT = 0x0a;
    private static final int MISOUND_PARAM_HEARING_RIGHT = 0x0b;
    private static final int MISOUND_PARAM_HEARING_LEFT_SAVE = 0x0c;
    private static final int MISOUND_PARAM_HEARING_RIGHT_SAVE = 0x0d;
    private static final int MISOUND_PARAM_HEADSET_TYPE = 0x01;
    private static final int MISOUND_PARAM_SCENARIO = 0x0f;
    private static final int MISOUND_PARAM_SURROUND = 0x14;
    private static final int MISOUND_PARAM_SOUND_ID_GAIN = 0x17;
    private static final int MISOUND_PARAM_SOUND_ID_ENABLE = 0x18;
    private static final int MISOUND_PARAM_ENABLE = 0x19;
    private static final int MISOUND_PARAM_EAR_SCAN_ENABLE = 0x1a;
    private static final int MISOUND_PARAM_EAR_SCAN_VALUE = 0x1b;

    private static final int PERSONAL_BAND_COUNT = 6;
    private static final int EAR_SCAN_COEFFICIENT_COUNT = 1024;

    private static final float[][] DOLBY_PRESETS = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {4, 1, -2, -0.25f, 0, -2, 0, -2, 0.5f, 4},
        {0, 0, 0, -1, -1, -3, -0.5f, 0, 0, 0},
        {-2, -0.5f, -5, -1, 0, 0, -0.5f, -3, -0.5f, 0},
        {0, 0, 0, 0, 0.5f, 3, 1, 6, 2, 6},
        {3, 0, -3, -0.5f, -0.5f, -3, -0.5f, 0, 0, 2},
        {2, 2, -6, -2, 3, 1, 0, 1, 0, 2},
        {3, 1, -1, 0, -0.5f, -3, -0.5f, 0, 0, 0},
        {2, 0, 0, -1.25f, -1, -4, 0, 0, 0, 0},
    };
    private static final String[] DOLBY_PRESET_NAMES = {
        "custom", "rock", "jazz", "pop", "classical", "hip_hop", "blues",
        "electronic", "metal"
    };

    private static final float[][] MISOUND_PRESETS = {
        {0, 0, 0, 0, 0, 0, 0},
        {4, 2, -2, 0, -2, -2, 4},
        {0, 0, 0, -2, -3, 0, 0},
        {0, -3, -5, 0, 0, -3, 0},
        {-3, -3, -3, -3, 0, 3, 3},
        {3, 3, -3, 0, -3, 0, 2},
        {2, 4, -6, 4, 0, 1, 2},
        {3, 3, -1, 0, -3, 0, 0},
        {0, 0, -2, -2, 2, 2, 0},
        {0, 4, 2, 0, -2, -2, 4},
        {2, 0, 0, -2, -4, 0, 0},
    };
    private static final String[] MISOUND_PRESET_NAMES = {
        "flat", "rock", "jazz", "pop", "classical", "hip_hop", "blues",
        "electronic", "country", "dance", "metal"
    };

    private final Context context;
    private final Spatializer spatializer;
    private final HeadTrackingProvider headTrackingProvider;

    private AudioEffect dolby;
    private AudioEffect miSound;
    private AudioDeviceProfile appliedProfile;
    private volatile boolean effectsAvailable;

    AudioEffectController(Context context) {
        this.context = context.createDeviceProtectedStorageContext();
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        spatializer = audioManager != null ? audioManager.getSpatializer() : null;
        headTrackingProvider = new FrameworkHeadTrackingProvider(spatializer);
    }

    synchronized boolean apply(AudioDeviceProfile requestedProfile) {
        AudioDeviceProfile profile = new AudioDeviceProfile(requestedProfile);
        effectsAvailable = applyLocked(profile);
        return effectsAvailable;
    }

    boolean ensureSpatialDeviceEnabled(OutputDeviceManager.ActiveOutput output,
            AudioDeviceProfile profile) {
        if (!isSpatialEnabled(profile)) return true;
        if (spatializer == null || output == null) return false;
        try {
            // Global enable and the per-device SA flag are independent. A global
            // setEnabled(true) cannot repair a disabled device after rerouting.
            for (AudioDeviceAttributes device : spatializer.getCompatibleAudioDevices()) {
                if (device.getType() == output.type
                        && device.getAddress().equalsIgnoreCase(output.address)) {
                    return true;
                }
            }
            spatializer.addCompatibleAudioDevice(new AudioDeviceAttributes(
                    AudioDeviceAttributes.ROLE_OUTPUT, output.type, output.address));
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not enable spatial audio for current output", exception);
            return false;
        }
    }

    synchronized boolean areEffectsAvailable() {
        if (!effectsAvailable || appliedProfile == null || dolby == null || miSound == null) {
            return false;
        }
        try {
            return dolby.hasControl() && miSound.hasControl()
                    && isImplementationStateCurrent(appliedProfile.implementation)
                    && isSpatialStateCurrent(isSpatialEnabled(appliedProfile));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not verify live audio-effect state", exception);
            return false;
        }
    }

    boolean isHeadTrackerAvailable() {
        return headTrackingProvider.isTrackerAvailable();
    }

    /** Associates the dynamic tracker with the active Bluetooth output and applies its profile. */
    boolean initializeHeadTracking(AudioOutputIdentity output, int requestedMode) {
        return initializeHeadTracking(output, requestedMode, false);
    }

    /**
     * Restores the native pose controller after its spatializer output has been reconfigured.
     *
     * AudioService deliberately preserves the Java-side desired mode and per-device enabled bit
     * while the native Spatializer swaps output effects. A normal state comparison therefore
     * cannot tell that the new native pose controller has not seen those values yet. The forced
     * path resends the same mode and, when sensor discovery is still converging, asks AudioService
     * to initialize sensors again. It does not recreate either global audio effect.
     */
    boolean restoreHeadTracking(AudioOutputIdentity output, int requestedMode) {
        return initializeHeadTracking(output, requestedMode, true);
    }

    private boolean initializeHeadTracking(AudioOutputIdentity output, int requestedMode,
            boolean forceNativeRestore) {
        if (spatializer == null || output == null || !output.isBluetoothClassicAudio()) {
            return false;
        }
        String address = output.getBluetoothIdentityAddress();
        if (address.isEmpty()) return false;
        AudioDeviceAttributes device = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT, output.deviceType, address);
        try {
            if (!spatializer.hasHeadTracker(device)) return false;
            if (requestedMode != AudioDeviceProfile.HEAD_TRACKING_DISABLED) {
                boolean trackerEnabled = spatializer.isHeadTrackerEnabled(device);
                if (!trackerEnabled
                        || (forceNativeRestore && !spatializer.isHeadTrackerAvailable())) {
                    // Calling this with an already-enabled device while the tracker is unavailable
                    // makes SpatializerHelper post sensor initialization again.
                    spatializer.setHeadTrackerEnabled(true, device);
                    Log.i(TAG, (trackerEnabled ? "Reinitialized" : "Enabled")
                            + " head tracker for " + output.displayName);
                }
            }
            int desiredMode = frameworkHeadTrackingMode(requestedMode);
            if (forceNativeRestore || spatializer.getDesiredHeadTrackingMode() != desiredMode) {
                // SpatializerHelper forwards this call to native even when its cached desired mode
                // already has the same value. That is required after a native output/effect swap.
                spatializer.setDesiredHeadTrackingMode(desiredMode);
            }
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not initialize Bluetooth head tracking", exception);
            return false;
        }
    }

    synchronized void onAudioServerDown() {
        releaseEffectsLocked();
        effectsAvailable = false;
    }

    synchronized void release() {
        releaseEffectsLocked();
        effectsAvailable = false;
    }

    private boolean applyLocked(AudioDeviceProfile profile) {
        try {
            boolean effectsRecreated = ensureEffectsLocked();
            AudioDeviceProfile previous = effectsRecreated ? null : appliedProfile;
            String implementation = profile.implementation;
            boolean spatialEnabled = profile.spatialEnabled
                    && !IMPLEMENTATION_NONE.equals(implementation);
            boolean implementationNeedsApply = previous == null
                    || !Objects.equals(previous.implementation, implementation)
                    || !isImplementationStateCurrent(implementation);
            boolean spatialNeedsApply = !isSpatialStateCurrent(spatialEnabled);
            if (previous != null && !implementationNeedsApply && !spatialNeedsApply
                    && hasSameAudioState(previous, profile)) {
                appliedProfile = new AudioDeviceProfile(profile);
                return true;
            }

            if (implementationNeedsApply && IMPLEMENTATION_DOLBY.equals(implementation)) {
                setMiSoundEnabledLocked(false);
                setDolbyEnabledLocked(true);
                Settings.Global.putString(context.getContentResolver(),
                        "effect_implementer", IMPLEMENTATION_DOLBY);
            } else if (implementationNeedsApply
                    && IMPLEMENTATION_MISOUND.equals(implementation)) {
                setDolbyEnabledLocked(false);
                setMiSoundEnabledLocked(true);
                Settings.Global.putString(context.getContentResolver(),
                        "effect_implementer", IMPLEMENTATION_MISOUND);
            } else if (implementationNeedsApply) {
                setDolbyEnabledLocked(false);
                setMiSoundEnabledLocked(false);
                Settings.Global.putString(context.getContentResolver(),
                        "effect_implementer", IMPLEMENTATION_NONE);
                spatialEnabled = false;
            }

            // Spatializer.setEnabled(false) releases the native spatial effect. Recreate it
            // before applying the global Dolby state when the user turns immersive sound back on.
            setSpatialEnabledLocked(spatialEnabled, previous);

            AudioDeviceProfile parameterBaseline = implementationNeedsApply ? null : previous;
            if (IMPLEMENTATION_DOLBY.equals(implementation)) {
                applyDolbyLocked(profile, parameterBaseline);
            } else if (IMPLEMENTATION_MISOUND.equals(implementation)) {
                applyMiSoundLocked(profile, parameterBaseline);
            }
            // Desired head-tracking mode is declarative; native Spatializer already gates sensor
            // use on its level and active tracks. Preserve the user's mode while immersive sound
            // is off so an off -> on toggle does not perform an unnecessary DISABLED -> WORLD
            // round trip in addition to rebuilding the spatializer output.
            setHeadTrackingModeLocked(profile.headTrackingMode);
            appliedProfile = new AudioDeviceProfile(profile);
            return true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to apply audio effects", exception);
            releaseEffectsLocked();
            return false;
        }
    }

    private boolean ensureEffectsLocked() {
        boolean recreated = false;
        if (dolby == null || !dolby.hasControl()) {
            if (dolby != null) dolby.release();
            dolby = new AudioEffect(EFFECT_TYPE_NULL, DOLBY_UUID, 0, 0);
            recreated = true;
        }
        if (miSound == null || !miSound.hasControl()) {
            if (miSound != null) miSound.release();
            miSound = new AudioEffect(AudioEffect.EFFECT_TYPE_NULL, MISOUND_UUID, 0, 0);
            recreated = true;
        }
        if (!dolby.hasControl() || !miSound.hasControl()) {
            throw new IllegalStateException("Global effects do not have control");
        }
        return recreated;
    }

    private void releaseEffectsLocked() {
        appliedProfile = null;
        if (dolby != null) {
            dolby.release();
            dolby = null;
        }
        if (miSound != null) {
            miSound.release();
            miSound = null;
        }
    }

    private void setDolbyEnabledLocked(boolean enabled) {
        checkStatus(dolby.setParameter(DOLBY_COMMAND,
                intsToBytes(DOLBY_PARAM_ENABLE, 1, enabled ? 1 : 0)));
        if (dolby.getEnabled() != enabled) checkStatus(dolby.setEnabled(enabled));
        if (dolby.getEnabled() != enabled) {
            throw new IllegalStateException("Dolby enable state did not change");
        }
    }

    private void setMiSoundEnabledLocked(boolean enabled) {
        checkStatus(miSound.setParameter(MISOUND_PARAM_ENABLE, enabled ? 1 : 0));
        if (miSound.getEnabled() != enabled) checkStatus(miSound.setEnabled(enabled));
        if (miSound.getEnabled() != enabled) {
            throw new IllegalStateException("MiSound enable state did not change");
        }
    }

    private void applyDolbyLocked(AudioDeviceProfile requestedProfile,
            AudioDeviceProfile previousProfile) {
        int profile = requestedProfile.dolbyProfile;
        boolean profileChanged = previousProfile == null
                || previousProfile.dolbyProfile != profile;
        int[] gains = expandDolbyBands(selectedDolbyBands(requestedProfile));
        boolean equalizerChanged = profileChanged || previousProfile == null
                || !Arrays.equals(gains,
                        expandDolbyBands(selectedDolbyBands(previousProfile)));
        if (profileChanged || equalizerChanged) {
            // Re-select the target profile before changing its GEQ. The vendor effect may
            // rebuild its active context when the output or media session changes while the
            // Java handle and our cached profile remain alive.
            checkStatus(dolby.setParameter(DOLBY_COMMAND,
                    intsToBytes(DOLBY_PARAM_PROFILE, 1, profile)));
        }
        if (equalizerChanged) {
            // The selected Dolby profile is the only active one. Updating all nine profiles on
            // every slider movement caused a burst of effect commands and audible dropouts.
            setDolbyProfileParameter(profile, DOLBY_GEQ_ENABLE, new int[] {1});
            setDolbyProfileParameter(profile, DOLBY_GEQ_GAINS, gains);
        }
        // Do not mirror these settings through Spatializer.setEffectParameter(). Xiaomi's Dolby
        // implementation already propagates the global DAP profile to miSpatializer through
        // DolbyManagerService. The previously invented 0x100/0x101 keys are not part of the
        // stock vendor contract: MiSpatializerContext reports them as unknown and destroys the
        // newly-created spatial effect, which makes an off -> on toggle roll back.
    }

    private void applyMiSoundLocked(AudioDeviceProfile requestedProfile,
            AudioDeviceProfile previousProfile) {
        int profile = requestedProfile.miSoundProfile;
        if (previousProfile == null || previousProfile.miSoundProfile != profile) {
            checkStatus(miSound.setParameter(MISOUND_PARAM_SCENARIO, profile));
        }
        if (previousProfile == null
                || previousProfile.miSoundSurround != requestedProfile.miSoundSurround) {
            checkStatus(miSound.setParameter(MISOUND_PARAM_SURROUND,
                    requestedProfile.miSoundSurround ? 1 : 0));
        }

        float[] bands = selectedMiSoundBands(requestedProfile);
        float[] previousBands = previousProfile == null
                ? null : selectedMiSoundBands(previousProfile);
        for (int band = 0; band < bands.length; band++) {
            if (previousBands != null
                    && Float.compare(previousBands[band], bands[band]) == 0) {
                continue;
            }
            byte[] value = Float.toString(bands[band]).getBytes(StandardCharsets.US_ASCII);
            checkStatus(miSound.setParameter(
                    new int[] {MISOUND_PARAM_EQ_BAND, band}, value));
        }

        applyHeadsetModelLocked(requestedProfile, previousProfile);
        applyHearingCompensationLocked(requestedProfile, previousProfile);
        applySoundIdLocked(requestedProfile, previousProfile);
        applyEarScanLocked(requestedProfile, previousProfile);
    }

    private void applyHeadsetModelLocked(AudioDeviceProfile requestedProfile,
            AudioDeviceProfile previousProfile) {
        if (previousProfile == null
                || previousProfile.headsetModelId != requestedProfile.headsetModelId) {
            checkStatus(miSound.setParameter(MISOUND_PARAM_HEADSET_TYPE,
                    requestedProfile.headsetModelId));
        }
    }

    private void applyHearingCompensationLocked(AudioDeviceProfile requestedProfile,
            AudioDeviceProfile previousProfile) {
        boolean enabled = hasHearingProfile(requestedProfile);
        boolean wasEnabled = previousProfile != null && hasHearingProfile(previousProfile);
        float[] left = hearingBands(requestedProfile.hearingLeftGains);
        float[] right = hearingBands(requestedProfile.hearingRightGains);
        boolean valuesChanged = previousProfile == null
                || !Arrays.equals(left, hearingBands(previousProfile.hearingLeftGains))
                || !Arrays.equals(right, hearingBands(previousProfile.hearingRightGains));

        // Parameters 12/13 stage bands 1-5 without committing. Parameters 10/11 write band 6
        // and atomically commit the complete six-band channel, matching Xiaomi's MiSound wrapper.
        if (enabled && (valuesChanged || !wasEnabled)) {
            setHearingChannelLocked(MISOUND_PARAM_HEARING_LEFT_SAVE,
                    MISOUND_PARAM_HEARING_LEFT, left);
            setHearingChannelLocked(MISOUND_PARAM_HEARING_RIGHT_SAVE,
                    MISOUND_PARAM_HEARING_RIGHT, right);
        }
        if (previousProfile == null || wasEnabled != enabled) {
            checkStatus(miSound.setParameter(MISOUND_PARAM_HEARING_ENABLE, enabled ? 1 : 0));
        }
    }

    private void setHearingChannelLocked(int stagingParameter, int commitParameter,
            float[] gains) {
        for (int band = 0; band < PERSONAL_BAND_COUNT; band++) {
            int parameter = band == PERSONAL_BAND_COUNT - 1
                    ? commitParameter : stagingParameter;
            checkStatus(miSound.setParameter(new int[] {parameter, band + 1},
                    Float.toString(gains[band]).getBytes(StandardCharsets.US_ASCII)));
        }
    }

    private void applySoundIdLocked(AudioDeviceProfile requestedProfile,
            AudioDeviceProfile previousProfile) {
        boolean enabled = hasSoundIdProfile(requestedProfile);
        boolean wasEnabled = previousProfile != null && hasSoundIdProfile(previousProfile);
        float[] gains = soundIdBands(requestedProfile.soundIdGains);
        boolean valuesChanged = previousProfile == null
                || !Arrays.equals(gains, soundIdBands(previousProfile.soundIdGains));
        if (enabled && (valuesChanged || !wasEnabled)) {
            // Xiaomi's MiSound wrapper sends SoundID gains as one ASCII float per band.
            for (int band = 0; band < gains.length; band++) {
                checkStatus(miSound.setParameter(
                        new int[] {MISOUND_PARAM_SOUND_ID_GAIN, band},
                        Float.toString(gains[band]).getBytes(StandardCharsets.US_ASCII)));
            }
        }
        if (previousProfile == null || wasEnabled != enabled) {
            checkStatus(miSound.setParameter(MISOUND_PARAM_SOUND_ID_ENABLE, enabled ? 1 : 0));
        }
    }

    private void applyEarScanLocked(AudioDeviceProfile requestedProfile,
            AudioDeviceProfile previousProfile) {
        boolean enabled = hasEarScanProfile(requestedProfile);
        boolean wasEnabled = previousProfile != null && hasEarScanProfile(previousProfile);
        float[] coefficients = requestedProfile.earScanFilterValues;
        boolean valuesChanged = previousProfile == null
                || !Arrays.equals(coefficients, previousProfile.earScanFilterValues);
        if (enabled && (valuesChanged || !wasEnabled)) {
            checkStatus(miSound.setParameter(MISOUND_PARAM_EAR_SCAN_VALUE,
                    floatsToBytes(coefficients)));
        }
        if (previousProfile == null || wasEnabled != enabled) {
            checkStatus(miSound.setParameter(MISOUND_PARAM_EAR_SCAN_ENABLE, enabled ? 1 : 0));
        }
    }

    private void setSpatialEnabledLocked(boolean enabled, AudioDeviceProfile previousProfile) {
        if (spatializer == null
                || spatializer.getImmersiveAudioLevel()
                        == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            if (enabled) throw new IllegalStateException("Spatializer is unavailable");
            return;
        }
        boolean previouslyEnabled = previousProfile != null
                && isSpatialEnabled(previousProfile);
        if (previousProfile == null || previouslyEnabled != enabled
                || spatializer.isEnabled() != enabled) {
            spatializer.setEnabled(enabled);
        }
        if (spatializer.isEnabled() != enabled) {
            throw new IllegalStateException("Spatializer enable state did not change");
        }
        if (previousProfile == null || previouslyEnabled != enabled) {
            Settings.Global.putInt(context.getContentResolver(),
                    "last_spatial_status", enabled ? 1 : 0);
        }
    }

    private void setHeadTrackingModeLocked(int requestedMode) {
        if (spatializer == null
                || spatializer.getImmersiveAudioLevel()
                        == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            return;
        }
        int desiredMode = frameworkHeadTrackingMode(requestedMode);
        if (spatializer.getDesiredHeadTrackingMode() != desiredMode) {
            spatializer.setDesiredHeadTrackingMode(desiredMode);
        }
        if (spatializer.getDesiredHeadTrackingMode() != desiredMode) {
            throw new IllegalStateException("Head-tracking mode did not change");
        }
    }

    private static int frameworkHeadTrackingMode(int requestedMode) {
        if (requestedMode == AudioDeviceProfile.HEAD_TRACKING_RELATIVE_DEVICE) {
            return Spatializer.HEAD_TRACKING_MODE_RELATIVE_DEVICE;
        }
        if (requestedMode == AudioDeviceProfile.HEAD_TRACKING_RELATIVE_WORLD) {
            return Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD;
        }
        return Spatializer.HEAD_TRACKING_MODE_DISABLED;
    }

    private static boolean hasSameAudioState(AudioDeviceProfile first,
            AudioDeviceProfile second) {
        if (!Objects.equals(first.implementation, second.implementation)
                || isSpatialEnabled(first) != isSpatialEnabled(second)
                || first.headTrackingMode != second.headTrackingMode) {
            return false;
        }
        if (IMPLEMENTATION_DOLBY.equals(second.implementation)) {
            return first.dolbyProfile == second.dolbyProfile
                    && Arrays.equals(expandDolbyBands(selectedDolbyBands(first)),
                            expandDolbyBands(selectedDolbyBands(second)));
        }
        if (IMPLEMENTATION_MISOUND.equals(second.implementation)) {
            return first.miSoundProfile == second.miSoundProfile
                    && first.miSoundSurround == second.miSoundSurround
                    && Arrays.equals(selectedMiSoundBands(first),
                            selectedMiSoundBands(second))
                    && first.headsetModelId == second.headsetModelId
                    && first.soundIdEnabled == second.soundIdEnabled
                    && Arrays.equals(first.soundIdGains, second.soundIdGains)
                    && first.hearingProfileEnabled == second.hearingProfileEnabled
                    && Arrays.equals(first.hearingLeftGains, second.hearingLeftGains)
                    && Arrays.equals(first.hearingRightGains, second.hearingRightGains)
                    && first.earScanEnabled == second.earScanEnabled
                    && Arrays.equals(first.earScanFilterValues,
                            second.earScanFilterValues);
        }
        return true;
    }

    private static boolean isSpatialEnabled(AudioDeviceProfile profile) {
        return profile.spatialEnabled && !IMPLEMENTATION_NONE.equals(profile.implementation);
    }

    private boolean isImplementationStateCurrent(String implementation) {
        if (IMPLEMENTATION_DOLBY.equals(implementation)) {
            return dolby.getEnabled() && !miSound.getEnabled();
        }
        if (IMPLEMENTATION_MISOUND.equals(implementation)) {
            return !dolby.getEnabled() && miSound.getEnabled();
        }
        return !dolby.getEnabled() && !miSound.getEnabled();
    }

    private boolean isSpatialStateCurrent(boolean enabled) {
        if (spatializer == null
                || spatializer.getImmersiveAudioLevel()
                        == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            return !enabled;
        }
        return spatializer.isEnabled() == enabled;
    }

    private void setDolbyProfileParameter(int profile, int parameter, int[] values) {
        int[] payload = new int[values.length + 4];
        payload[0] = DOLBY_PROFILE_PARAM;
        payload[1] = values.length + 1;
        payload[2] = profile;
        payload[3] = parameter;
        System.arraycopy(values, 0, payload, 4, values.length);
        checkStatus(dolby.setParameter(DOLBY_COMMAND, intsToBytes(payload)));
    }

    private static float[] selectedDolbyBands(AudioDeviceProfile profile) {
        String preset = profile.dolbyEqPreset;
        int presetIndex = indexOf(DOLBY_PRESET_NAMES, preset);
        if (presetIndex > 0) return DOLBY_PRESETS[presetIndex].clone();
        return AudioDeviceProfile.sized(profile.dolbyBands, 10);
    }

    private static float[] selectedMiSoundBands(AudioDeviceProfile profile) {
        String preset = profile.miSoundEqPreset;
        int presetIndex = indexOf(MISOUND_PRESET_NAMES, preset);
        if (presetIndex >= 0) return MISOUND_PRESETS[presetIndex].clone();
        return AudioDeviceProfile.sized(profile.miSoundBands, 7);
    }

    private static int[] expandDolbyBands(float[] bands) {
        if (bands.length != 10) throw new IllegalArgumentException("Dolby GEQ needs 10 bands");
        int[] expanded = new int[20];
        expanded[0] = Math.round(bands[0] * 16f);
        expanded[1] = Math.round(((bands[0] + bands[1]) / 2f) * 16f);
        for (int band = 1; band < 9; band++) {
            expanded[band * 2 - 1] =
                    Math.round(((bands[band - 1] + bands[band]) / 2f) * 16f);
            expanded[band * 2] = Math.round(bands[band] * 16f);
            expanded[band * 2 + 1] =
                    Math.round(((bands[band] + bands[band + 1]) / 2f) * 16f);
        }
        expanded[17] = Math.round(((bands[8] + bands[9]) / 2f) * 16f);
        expanded[18] = Math.round(bands[9] * 16f);
        expanded[19] = expanded[18];

        int average = Arrays.stream(expanded).sum() / expanded.length;
        for (int i = 0; i < expanded.length; i++) expanded[i] -= average;
        return expanded;
    }

    private static byte[] intsToBytes(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }

    private static byte[] floatsToBytes(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }

    private static boolean hasSoundIdProfile(AudioDeviceProfile profile) {
        return profile.soundIdEnabled && profile.soundIdGains.length == PERSONAL_BAND_COUNT;
    }

    private static float[] soundIdBands(float[] gains) {
        return gains != null && gains.length == PERSONAL_BAND_COUNT
                ? gains : new float[PERSONAL_BAND_COUNT];
    }

    private static boolean hasHearingProfile(AudioDeviceProfile profile) {
        return profile.hearingProfileEnabled
                && profile.hearingLeftGains.length == PERSONAL_BAND_COUNT
                && profile.hearingRightGains.length == PERSONAL_BAND_COUNT;
    }

    private static float[] hearingBands(float[] gains) {
        return gains != null && gains.length == PERSONAL_BAND_COUNT
                ? gains : new float[PERSONAL_BAND_COUNT];
    }

    private static boolean hasEarScanProfile(AudioDeviceProfile profile) {
        return profile.earScanEnabled
                && profile.earScanFilterValues.length == EAR_SCAN_COEFFICIENT_COUNT;
    }

    private static int indexOf(String[] values, String requested) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(requested)) return i;
        }
        return -1;
    }

    private static void checkStatus(int status) {
        if (status >= 0) return;
        if (status == AudioEffect.ERROR_BAD_VALUE) {
            throw new IllegalArgumentException("Bad audio effect parameter");
        }
        if (status == AudioEffect.ERROR_INVALID_OPERATION) {
            throw new UnsupportedOperationException("Unsupported audio effect operation");
        }
        throw new IllegalStateException("Audio effect error " + status);
    }
}
