/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.text.TextUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/** Tracks the device actually selected by AudioPolicy for media playback. */
final class OutputDeviceManager {
    static final String SPEAKER_KEY = AudioDeviceProfile.BUILTIN_SPEAKER_KEY;

    interface Listener {
        void onActiveOutputChanged(ActiveOutput output);
    }

    static final class ActiveOutput {
        final String key;
        final String displayName;
        final int type;
        final String address;

        ActiveOutput(String key, String displayName, int type, String address) {
            this.key = key;
            this.displayName = displayName;
            this.type = type;
            this.address = address;
        }
    }

    private static final AudioAttributes MEDIA_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private final AudioManager audioManager;
    private final Executor executor;
    private final Listener listener;
    private final AudioManager.OnDevicesForAttributesChangedListener routeListener =
            (attributes, devices) -> dispatch(selectPrimary(devices));

    OutputDeviceManager(AudioManager audioManager, Executor executor, Listener listener) {
        this.audioManager = audioManager;
        this.executor = executor;
        this.listener = listener;
    }

    void start() {
        audioManager.addOnDevicesForAttributesChangedListener(
                MEDIA_ATTRIBUTES, executor, routeListener);
        executor.execute(() -> dispatch(
                selectPrimary(audioManager.getDevicesForAttributes(MEDIA_ATTRIBUTES))));
    }

    void stop() {
        audioManager.removeOnDevicesForAttributesChangedListener(routeListener);
    }

    ActiveOutput current() {
        return selectPrimary(audioManager.getDevicesForAttributes(MEDIA_ATTRIBUTES));
    }

    private void dispatch(ActiveOutput output) {
        listener.onActiveOutputChanged(output);
    }

    private static ActiveOutput selectPrimary(List<AudioDeviceAttributes> requestedDevices) {
        List<AudioDeviceAttributes> devices = requestedDevices == null
                ? Collections.emptyList() : requestedDevices;
        AudioDeviceAttributes selected = null;
        int bestPriority = Integer.MIN_VALUE;
        for (AudioDeviceAttributes device : devices) {
            int priority = priority(device.getType());
            if (priority > bestPriority) {
                selected = device;
                bestPriority = priority;
            }
        }
        if (selected == null) {
            return new ActiveOutput(SPEAKER_KEY, "Built-in speaker",
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "");
        }
        return fromAttributes(selected);
    }

    private static ActiveOutput fromAttributes(AudioDeviceAttributes device) {
        int type = device.getType();
        String address = nonNull(device.getAddress()).toLowerCase(Locale.ROOT);
        String name = TextUtils.isEmpty(device.getName()) ? defaultName(type) : device.getName();
        String key;
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                key = SPEAKER_KEY;
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
                key = "wired:" + type;
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                key = "bluetooth:" + type + ":" + stableAddress(address, name);
                break;
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                key = "usb:" + type + ":" + stableAddress(address, name);
                break;
            default:
                key = "output:" + type + ":" + stableAddress(address, name);
                break;
        }
        return new ActiveOutput(key, name, type, address);
    }

    private static int priority(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return 50;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return 45;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return 40;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
                return 35;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return 10;
            default:
                return 20;
        }
    }

    private static String defaultName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Built-in speaker";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "Bluetooth audio";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB audio";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired headphones";
            default:
                return "Audio output";
        }
    }

    private static String stableAddress(String address, String name) {
        if (!TextUtils.isEmpty(address)) return address;
        return Integer.toHexString(nonNull(name).toLowerCase(Locale.ROOT).hashCode());
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
