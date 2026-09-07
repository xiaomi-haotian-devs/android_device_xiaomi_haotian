/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;

/** Applies LibrePods-style play/pause behavior to changes in the two reported wear states. */
final class WearMediaPolicy {
    private static final String TAG = "HaotianAirPods";
    private static final int EAR_DETECTION_CONTROL = 0x0a;

    private static String deviceAddress = "";
    private static int previousWornCount;
    private static boolean initialized;
    private static boolean pausedByWear;

    private WearMediaPolicy() {
    }

    static synchronized void onState(Context context, AirPodsState state) {
        if (state == null || !state.connected || state.deviceAddress.isEmpty()) {
            reset();
            return;
        }
        if (!state.deviceAddress.equalsIgnoreCase(deviceAddress)) {
            reset();
            deviceAddress = state.deviceAddress;
        }
        byte[] earDetection = state.getControlValue(EAR_DETECTION_CONTROL);
        if (earDetection == null || earDetection.length == 0 || earDetection[0] != 1
                || !isKnownWear(state.leftWear) || !isKnownWear(state.rightWear)) {
            initialized = false;
            pausedByWear = false;
            return;
        }

        int wornCount = (state.leftWear == AirPodsState.WEAR_IN_EAR ? 1 : 0)
                + (state.rightWear == AirPodsState.WEAR_IN_EAR ? 1 : 0);
        if (!initialized) {
            previousWornCount = wornCount;
            initialized = true;
            return;
        }
        if (wornCount == previousWornCount) return;

        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) {
            previousWornCount = wornCount;
            return;
        }
        boolean shouldPause = wornCount == 0 || (previousWornCount == 2 && wornCount == 1);
        boolean shouldResume = (previousWornCount == 0 && wornCount > 0)
                || (previousWornCount == 1 && wornCount == 2);
        if (shouldPause && audioManager.isMusicActive()) {
            dispatch(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE);
            pausedByWear = true;
            Log.i(TAG, "Paused media after AirPods wear-state change");
        } else if (shouldResume && pausedByWear) {
            dispatch(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY);
            pausedByWear = false;
            Log.i(TAG, "Resumed media after AirPods wear-state change");
        }
        previousWornCount = wornCount;
    }

    private static boolean isKnownWear(int wear) {
        return wear == AirPodsState.WEAR_IN_EAR || wear == AirPodsState.WEAR_OUT_OF_EAR
                || wear == AirPodsState.WEAR_IN_CASE;
    }

    private static void dispatch(AudioManager audioManager, int keyCode) {
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private static void reset() {
        deviceAddress = "";
        previousWornCount = 0;
        initialized = false;
        pausedByWear = false;
    }
}
