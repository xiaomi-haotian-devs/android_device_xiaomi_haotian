/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

/** Applies the local playback policy to Conversation Awareness notifications. */
final class ConversationAwarenessMediaPolicy {
    private static final String TAG = "HaotianAirPods";
    private static final int AACP_CONVERSATION_AWARENESS = 0x4b;
    private static final int DEFAULT_VOLUME_PERCENT = 43;

    private static String deviceAddress = "";
    private static int lastStatus = -1;
    private static int initialWornCount = -1;
    private static boolean conversationActive;
    private static boolean pausedByConversation;

    private ConversationAwarenessMediaPolicy() {
    }

    static synchronized void onState(Context context, AirPodsState state) {
        if (state == null || !state.connected || state.deviceAddress.isEmpty()) {
            finishConversation(context, state);
            reset();
            return;
        }
        if (!state.deviceAddress.equalsIgnoreCase(deviceAddress)) {
            boolean wasActive = conversationActive;
            finishConversation(context, null);
            reset();
            deviceAddress = state.deviceAddress;
            if (wasActive) return;
        }
        byte[] message = state.getAacpMessageValue(AACP_CONVERSATION_AWARENESS);
        if (message == null || message.length < 4) return;
        int status = message[3] & 0xff;
        if (status == lastStatus) return;
        lastStatus = status;
        if (status == 1 || status == 2) {
            startConversation(context, state);
        } else if (status == 6 || status == 8 || status == 9) {
            finishConversation(context, state);
        }
    }

    private static void startConversation(Context context, AirPodsState state) {
        if (conversationActive) return;
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) return;
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        conversationActive = true;
        initialWornCount = wornCount(state);
        int percent = Math.max(10, Math.min(85, preferences.getInt(
                "conversation_awareness_volume", DEFAULT_VOLUME_PERCENT)));
        boolean relative = preferences.getBoolean(
                "relative_conversation_awareness_volume", true);
        float duckVolume = calculateDuckVolume(audioManager, percent, relative);
        String outputDeviceKey = state.deviceKey;
        if (outputDeviceKey.isEmpty()) {
            outputDeviceKey = AudioOutputIdentity.forBluetoothClassic(
                    state.deviceAddress, "").deviceKey;
        }
        HaotianAudioDuckClient.request(context, outputDeviceKey, duckVolume);
        if (preferences.getBoolean("conversation_awareness_pause_music", false)
                && audioManager.isMusicActive()) {
            dispatch(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE);
            pausedByConversation = true;
        }
        Log.i(TAG, "Applied Conversation Awareness media policy at "
                + Math.round(duckVolume * 100.0f) + "% playback gain");
    }

    private static void finishConversation(Context context, AirPodsState state) {
        if (!conversationActive) return;
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        HaotianAudioDuckClient.release(context);
        if (audioManager != null) {
            if (pausedByConversation && state != null && state.connected
                    && (initialWornCount < 0 || wornCount(state) >= initialWornCount)) {
                dispatch(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY);
            }
        }
        conversationActive = false;
        initialWornCount = -1;
        pausedByConversation = false;
        Log.i(TAG, "Restored media after Conversation Awareness");
    }

    static synchronized void shutdown(Context context) {
        HaotianAudioDuckClient.shutdown(context);
        reset();
    }

    private static float calculateDuckVolume(
            AudioManager audioManager, int percent, boolean relative) {
        int stream = AudioManager.STREAM_MUSIC;
        int current = audioManager.getStreamVolume(stream);
        int minimum = audioManager.getStreamMinVolume(stream);
        int maximum = audioManager.getStreamMaxVolume(stream);
        if (current <= minimum || maximum <= minimum) return 1.0f;

        int target = relative
                ? current * percent / 100
                : Math.min(current, maximum * percent / 100);
        target = Math.max(minimum, Math.min(current, target));
        if (target >= current) return 1.0f;

        try {
            float currentDb = audioManager.getStreamVolumeDb(
                    stream, current, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
            float targetDb = audioManager.getStreamVolumeDb(
                    stream, target, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
            if (targetDb == Float.NEGATIVE_INFINITY) return 0.0f;
            if (Float.isFinite(currentDb) && Float.isFinite(targetDb)) {
                float volume = (float) Math.pow(10.0, (targetDb - currentDb) / 20.0);
                return Math.max(0.0f, Math.min(1.0f, volume));
            }
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Could not query the Bluetooth media volume curve", exception);
        }
        return Math.max(0.0f, Math.min(1.0f, target / (float) current));
    }

    private static void dispatch(AudioManager audioManager, int keyCode) {
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private static int wornCount(AirPodsState state) {
        if (state == null || !knownWear(state.leftWear) || !knownWear(state.rightWear)) {
            return -1;
        }
        return (state.leftWear == AirPodsState.WEAR_IN_EAR ? 1 : 0)
                + (state.rightWear == AirPodsState.WEAR_IN_EAR ? 1 : 0);
    }

    private static boolean knownWear(int wear) {
        return wear == AirPodsState.WEAR_IN_EAR || wear == AirPodsState.WEAR_OUT_OF_EAR
                || wear == AirPodsState.WEAR_IN_CASE;
    }

    private static void reset() {
        deviceAddress = "";
        lastStatus = -1;
        initialWornCount = -1;
        conversationActive = false;
        pausedByConversation = false;
    }
}
