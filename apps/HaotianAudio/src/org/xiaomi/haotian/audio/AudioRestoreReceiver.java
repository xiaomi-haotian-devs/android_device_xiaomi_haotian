/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AudioRestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, HaotianAudioService.class));
    }
}
