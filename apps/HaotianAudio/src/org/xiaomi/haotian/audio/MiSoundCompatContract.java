/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.ComponentName;

/** Stable component and version contract for platform-signed HaotianAudio clients. */
public final class MiSoundCompatContract {
    public static final int API_VERSION = 2;
    public static final String PACKAGE_NAME = "org.xiaomi.haotian.audio";
    public static final String SERVICE_CLASS = PACKAGE_NAME + ".HaotianAudioService";
    public static final String CONTROL_PERMISSION =
            PACKAGE_NAME + ".permission.CONTROL_AUDIO";
    public static final ComponentName SERVICE_COMPONENT =
            new ComponentName(PACKAGE_NAME, SERVICE_CLASS);

    private MiSoundCompatContract() {
    }
}
