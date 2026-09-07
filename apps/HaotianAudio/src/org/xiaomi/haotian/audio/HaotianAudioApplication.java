/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.app.Application;
import android.content.Intent;

public final class HaotianAudioApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, HaotianAudioService.class));
    }
}
