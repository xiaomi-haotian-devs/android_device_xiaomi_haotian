/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

/** Device-protected, transactional storage for per-output desired state. */
final class AudioProfileStore {
    private static final String PROFILE_PREFIX = "output_profile.";

    private final SharedPreferences preferences;

    AudioProfileStore(Context context) {
        preferences = context.createDeviceProtectedStorageContext().getSharedPreferences(
                AudioEffectController.PREFERENCES, Context.MODE_PRIVATE);
    }

    synchronized AudioDeviceProfile load(String deviceKey, String displayName) {
        String safeKey = nonEmpty(deviceKey, OutputDeviceManager.SPEAKER_KEY);
        String prefix = prefix(safeKey);
        if (!preferences.contains(prefix + "schema")) {
            return fromLegacyPreferences(safeKey, displayName);
        }

        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.schemaVersion = preferences.getInt(prefix + "schema",
                AudioDeviceProfile.SCHEMA_VERSION);
        profile.revision = preferences.getLong(prefix + "revision", 0);
        profile.deviceKey = safeKey;
        profile.displayName = preferences.getString(prefix + "display_name",
                nonEmpty(displayName, safeKey));
        profile.implementation = preferences.getString(prefix + "implementation",
                AudioEffectController.IMPLEMENTATION_DOLBY);
        profile.spatialEnabled = preferences.getBoolean(prefix + "spatial", false);
        profile.headTrackingMode = preferences.getInt(prefix + "head_tracking_mode",
                AudioDeviceProfile.HEAD_TRACKING_DISABLED);
        profile.dolbyProfile = preferences.getInt(prefix + "dolby_profile", 0);
        profile.dolbyEqPreset = preferences.getString(prefix + "dolby_eq", "custom");
        profile.dolbyBands = decodeFloats(preferences.getString(prefix + "dolby_bands", ""), 10);
        profile.miSoundProfile = preferences.getInt(prefix + "misound_profile", 4);
        profile.miSoundSurround = preferences.getBoolean(prefix + "misound_surround", false);
        profile.miSoundEqPreset = preferences.getString(prefix + "misound_eq", "custom");
        profile.miSoundBands = decodeFloats(
                preferences.getString(prefix + "misound_bands", ""), 7);
        profile.soundIdProfileId = preferences.getString(prefix + "sound_id_profile", "");
        profile.soundIdEnabled = preferences.getBoolean(prefix + "sound_id_enabled", false);
        profile.soundIdGains = decodeFloats(
                preferences.getString(prefix + "sound_id_gains", ""), -1);
        profile.hearingProfileEnabled = preferences.getBoolean(
                prefix + "hearing_enabled", false);
        profile.hearingLeftGains = decodeFloats(
                preferences.getString(prefix + "hearing_left", ""), -1);
        profile.hearingRightGains = decodeFloats(
                preferences.getString(prefix + "hearing_right", ""), -1);
        profile.earScanEnabled = preferences.getBoolean(prefix + "ear_scan_enabled", false);
        profile.earScanFilterValues = decodeFloats(
                preferences.getString(prefix + "ear_scan_values", ""), -1);
        profile.headsetModelId = preferences.getInt(prefix + "headset_model_id", 0);
        profile.headsetModelName = preferences.getString(prefix + "headset_model_name", "");
        return sanitize(profile, safeKey, displayName);
    }

    synchronized AudioDeviceProfile load(String deviceKey) {
        return load(deviceKey, deviceKey);
    }

    synchronized AudioDeviceProfile save(AudioDeviceProfile requested) {
        AudioDeviceProfile profile = sanitize(requested,
                requested != null ? requested.deviceKey : null,
                requested != null ? requested.displayName : null);
        String prefix = prefix(profile.deviceKey);
        profile.schemaVersion = AudioDeviceProfile.SCHEMA_VERSION;
        profile.revision = Math.max(profile.revision + 1, System.currentTimeMillis());

        boolean committed = preferences.edit()
                .putInt(prefix + "schema", profile.schemaVersion)
                .putLong(prefix + "revision", profile.revision)
                .putString(prefix + "display_name", profile.displayName)
                .putString(prefix + "implementation", profile.implementation)
                .putBoolean(prefix + "spatial", profile.spatialEnabled)
                .putInt(prefix + "head_tracking_mode", profile.headTrackingMode)
                .putInt(prefix + "dolby_profile", profile.dolbyProfile)
                .putString(prefix + "dolby_eq", profile.dolbyEqPreset)
                .putString(prefix + "dolby_bands", encodeFloats(profile.dolbyBands))
                .putInt(prefix + "misound_profile", profile.miSoundProfile)
                .putBoolean(prefix + "misound_surround", profile.miSoundSurround)
                .putString(prefix + "misound_eq", profile.miSoundEqPreset)
                .putString(prefix + "misound_bands", encodeFloats(profile.miSoundBands))
                .putString(prefix + "sound_id_profile", profile.soundIdProfileId)
                .putBoolean(prefix + "sound_id_enabled", profile.soundIdEnabled)
                .putString(prefix + "sound_id_gains", encodeFloats(profile.soundIdGains))
                .putBoolean(prefix + "hearing_enabled", profile.hearingProfileEnabled)
                .putString(prefix + "hearing_left", encodeFloats(profile.hearingLeftGains))
                .putString(prefix + "hearing_right", encodeFloats(profile.hearingRightGains))
                .putBoolean(prefix + "ear_scan_enabled", profile.earScanEnabled)
                .putString(prefix + "ear_scan_values",
                        encodeFloats(profile.earScanFilterValues))
                .putInt(prefix + "headset_model_id", profile.headsetModelId)
                .putString(prefix + "headset_model_name", profile.headsetModelName)
                .commit();
        if (!committed) throw new IllegalStateException("Could not persist audio profile");
        return new AudioDeviceProfile(profile);
    }

    private AudioDeviceProfile fromLegacyPreferences(String deviceKey, String displayName) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.deviceKey = deviceKey;
        profile.displayName = nonEmpty(displayName, deviceKey);
        profile.implementation = preferences.getString(AudioEffectController.KEY_IMPLEMENTATION,
                AudioEffectController.IMPLEMENTATION_DOLBY);
        profile.spatialEnabled = preferences.getBoolean(AudioEffectController.KEY_SPATIAL, false);
        profile.dolbyProfile = parseInt(preferences.getString(
                AudioEffectController.KEY_DOLBY_PROFILE, "0"), 0);
        profile.dolbyEqPreset = preferences.getString(AudioEffectController.KEY_DOLBY_EQ,
                "custom");
        for (int band = 0; band < profile.dolbyBands.length; band++) {
            profile.dolbyBands[band] = preferences.getInt(
                    AudioEffectController.KEY_DOLBY_BAND_PREFIX + band, 0) / 4f;
        }
        profile.miSoundProfile = parseInt(preferences.getString(
                AudioEffectController.KEY_MISOUND_PROFILE, "4"), 4);
        profile.miSoundSurround = preferences.getBoolean(
                AudioEffectController.KEY_MISOUND_SURROUND, false);
        profile.miSoundEqPreset = preferences.getString(AudioEffectController.KEY_MISOUND_EQ,
                "custom");
        for (int band = 0; band < profile.miSoundBands.length; band++) {
            profile.miSoundBands[band] = preferences.getInt(
                    AudioEffectController.KEY_MISOUND_BAND_PREFIX + band, 0);
        }
        return profile;
    }

    private static AudioDeviceProfile sanitize(AudioDeviceProfile requested,
            String fallbackKey, String fallbackName) {
        AudioDeviceProfile profile = requested == null
                ? new AudioDeviceProfile() : new AudioDeviceProfile(requested);
        profile.deviceKey = nonEmpty(profile.deviceKey,
                nonEmpty(fallbackKey, OutputDeviceManager.SPEAKER_KEY));
        profile.displayName = nonEmpty(profile.displayName,
                nonEmpty(fallbackName, profile.deviceKey));
        if (!AudioEffectController.IMPLEMENTATION_DOLBY.equals(profile.implementation)
                && !AudioEffectController.IMPLEMENTATION_MISOUND.equals(profile.implementation)
                && !AudioEffectController.IMPLEMENTATION_NONE.equals(profile.implementation)) {
            profile.implementation = AudioEffectController.IMPLEMENTATION_DOLBY;
        }
        profile.dolbyProfile = clamp(profile.dolbyProfile, 0, 8);
        profile.miSoundProfile = clamp(profile.miSoundProfile, 1, 4);
        profile.headTrackingMode = clamp(profile.headTrackingMode,
                AudioDeviceProfile.HEAD_TRACKING_DISABLED,
                AudioDeviceProfile.HEAD_TRACKING_RELATIVE_WORLD);
        if (AudioEffectController.IMPLEMENTATION_NONE.equals(profile.implementation)) {
            profile.spatialEnabled = false;
        }
        if (!profile.spatialEnabled) {
            profile.headTrackingMode = AudioDeviceProfile.HEAD_TRACKING_DISABLED;
        }
        profile.dolbyBands = clamp(AudioDeviceProfile.sized(profile.dolbyBands, 10), -6f, 6f);
        profile.miSoundBands = clamp(AudioDeviceProfile.sized(profile.miSoundBands, 7), -6f, 6f);
        profile.soundIdProfileId = nonNull(profile.soundIdProfileId);
        profile.soundIdGains = boundedExactCopy(profile.soundIdGains,
                AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT, -24f, 24f);
        profile.hearingLeftGains = boundedExactCopy(profile.hearingLeftGains,
                AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT, -24f, 24f);
        profile.hearingRightGains = boundedExactCopy(profile.hearingRightGains,
                AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT, -24f, 24f);
        profile.earScanFilterValues = boundedExactCopy(profile.earScanFilterValues,
                AudioDeviceProfile.EAR_SCAN_COEFFICIENT_COUNT, -128f, 128f);
        profile.soundIdEnabled &= profile.soundIdGains.length
                == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT;
        profile.hearingProfileEnabled &= profile.hearingLeftGains.length
                == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT
                && profile.hearingRightGains.length
                        == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT;
        profile.earScanEnabled &= profile.earScanFilterValues.length
                == AudioDeviceProfile.EAR_SCAN_COEFFICIENT_COUNT;
        profile.headsetModelId = clamp(profile.headsetModelId, 0, 25);
        profile.headsetModelName = nonNull(profile.headsetModelName);
        profile.dolbyEqPreset = nonEmpty(profile.dolbyEqPreset, "custom");
        profile.miSoundEqPreset = nonEmpty(profile.miSoundEqPreset, "custom");
        return profile;
    }

    private static String prefix(String key) {
        return PROFILE_PREFIX + Base64.encodeToString(key.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING) + ".";
    }

    private static String encodeFloats(float[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder builder = new StringBuilder();
        for (float value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(Float.toString(value));
        }
        return builder.toString();
    }

    private static float[] decodeFloats(String encoded, int expectedSize) {
        if (encoded == null || encoded.isEmpty()) {
            return expectedSize < 0 ? new float[0] : new float[expectedSize];
        }
        String[] parts = encoded.split(",", -1);
        if (expectedSize >= 0 && parts.length != expectedSize) return new float[expectedSize];
        float[] values = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) values[i] = Float.parseFloat(parts[i]);
            return values;
        } catch (NumberFormatException exception) {
            return expectedSize < 0 ? new float[0] : new float[expectedSize];
        }
    }

    private static float[] boundedExactCopy(float[] source, int size, float min, float max) {
        if (source == null || source.length != size) return new float[0];
        return clamp(AudioDeviceProfile.copy(source), min, max);
    }

    private static float[] clamp(float[] values, float min, float max) {
        for (int i = 0; i < values.length; i++) {
            if (!Float.isFinite(values[i])) values[i] = 0;
            values[i] = Math.max(min, Math.min(max, values[i]));
        }
        return values;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
