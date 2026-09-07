/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.ContentResolver;
import android.provider.Settings;

/** Durable device-wide microphone policy shared by Settings and the recording control plane. */
final class MicrophoneConfigStore {
    static final String KEY_MODE = "haotian_microphone_recording_mode";
    static final String KEY_WIND_REDUCTION = "haotian_microphone_wind_reduction";
    static final String KEY_SOURCE_TRACKING = "haotian_microphone_source_tracking";
    static final String KEY_FOCUS_WIDTH = "haotian_microphone_focus_width";

    static final String MODE_STANDARD = "standard";
    static final String MODE_OMNI = "omni";
    static final String MODE_FRONT = "front";
    static final String MODE_BACK = "back";
    static final String MODE_DUAL = "dual";
    static final String MODE_AUDIO_ZOOM = "audio_zoom";
    static final String MODE_SPATIAL = "spatial";

    static final int DEFAULT_FOCUS_WIDTH = 60;

    private final ContentResolver resolver;

    MicrophoneConfigStore(ContentResolver resolver) {
        this.resolver = resolver;
    }

    String getMode() {
        String mode = Settings.Secure.getString(resolver, KEY_MODE);
        return isKnownMode(mode) ? mode : MODE_STANDARD;
    }

    void setMode(String mode) {
        if (!isKnownMode(mode)) throw new IllegalArgumentException("Unknown microphone mode");
        Settings.Secure.putString(resolver, KEY_MODE, mode);
    }

    boolean isWindReductionEnabled() {
        return Settings.Secure.getInt(resolver, KEY_WIND_REDUCTION, 1) != 0;
    }

    void setWindReductionEnabled(boolean enabled) {
        Settings.Secure.putInt(resolver, KEY_WIND_REDUCTION, enabled ? 1 : 0);
    }

    boolean isSourceTrackingEnabled() {
        return Settings.Secure.getInt(resolver, KEY_SOURCE_TRACKING, 0) != 0;
    }

    void setSourceTrackingEnabled(boolean enabled) {
        Settings.Secure.putInt(resolver, KEY_SOURCE_TRACKING, enabled ? 1 : 0);
    }

    int getFocusWidth() {
        return clampFocusWidth(Settings.Secure.getInt(
                resolver, KEY_FOCUS_WIDTH, DEFAULT_FOCUS_WIDTH));
    }

    void setFocusWidth(int degrees) {
        Settings.Secure.putInt(resolver, KEY_FOCUS_WIDTH, clampFocusWidth(degrees));
    }

    private static int clampFocusWidth(int degrees) {
        return Math.max(20, Math.min(180, degrees));
    }

    private static boolean isKnownMode(String mode) {
        return MODE_STANDARD.equals(mode)
                || MODE_OMNI.equals(mode)
                || MODE_FRONT.equals(mode)
                || MODE_BACK.equals(mode)
                || MODE_DUAL.equals(mode)
                || MODE_AUDIO_ZOOM.equals(mode)
                || MODE_SPATIAL.equals(mode);
    }
}
