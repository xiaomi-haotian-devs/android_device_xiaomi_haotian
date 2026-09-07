/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.haotian.touch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "HaotianTouchBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        try {
            context.startService(new Intent(context, EdgeSuppressionService.class));
            context.startService(new Intent(context, TouchControlService.class));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to start touch services", e);
        }
    }
}
