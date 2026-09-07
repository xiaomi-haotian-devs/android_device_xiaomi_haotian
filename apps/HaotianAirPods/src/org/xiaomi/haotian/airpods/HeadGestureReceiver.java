/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

/** Starts gesture recognition only for the lifetime of an incoming ringing call. */
public final class HeadGestureReceiver extends BroadcastReceiver {
    private static final String TAG = "HaotianAirPods";
    static final String PREF_ENABLED = "head_gestures_enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        HaotianAirPodsApplication.onPhoneStateChanged(state);
        Intent service = new Intent(context, HeadGestureService.class);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)
                && PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(PREF_ENABLED, false)) {
            try {
                context.startForegroundService(
                        service.setAction(HeadGestureService.ACTION_START));
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not start incoming-call head gestures", exception);
            }
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)
                || TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            context.stopService(service);
        }
    }
}
