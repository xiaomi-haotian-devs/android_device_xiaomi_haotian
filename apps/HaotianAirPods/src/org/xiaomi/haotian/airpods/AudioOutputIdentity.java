/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.media.AudioDeviceInfo;

import org.xiaomi.haotian.audio.provider.HeadphoneOutput;

import java.util.Locale;
import java.util.regex.Pattern;

/** AirPods-side identity for a Bluetooth route and its controller identity address. */
final class AudioOutputIdentity {
    private static final Pattern BLUETOOTH_ADDRESS =
            Pattern.compile("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}");

    final String deviceKey;
    final String displayName;
    final int deviceType;
    final String address;
    private final String bluetoothIdentityAddress;

    AudioOutputIdentity(HeadphoneOutput output) {
        deviceKey = output == null ? "" : output.deviceKey;
        displayName = output == null ? "" : output.displayName;
        deviceType = output == null ? AudioDeviceInfo.TYPE_UNKNOWN : output.deviceType;
        address = output == null ? "" : output.address;
        bluetoothIdentityAddress = resolveBluetoothIdentityAddress(address);
    }

    private AudioOutputIdentity(String requestedAddress, String requestedName) {
        String canonical = validatedAddress(requestedAddress);
        String stableAddress = canonical.toLowerCase(Locale.ROOT);
        deviceKey = "bluetooth:" + AudioDeviceInfo.TYPE_BLUETOOTH_A2DP + ":" + stableAddress;
        displayName = requestedName == null || requestedName.isEmpty()
                ? "Bluetooth audio" : requestedName;
        deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
        address = stableAddress;
        bluetoothIdentityAddress = resolveBluetoothIdentityAddress(address);
    }

    static AudioOutputIdentity forBluetoothClassic(String address, String displayName) {
        if (validatedAddress(address).isEmpty()) {
            throw new IllegalArgumentException("Invalid Bluetooth address");
        }
        return new AudioOutputIdentity(address, displayName);
    }

    boolean isBluetoothClassicAudio() {
        return deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
    }

    String getBluetoothTransportAddress() {
        return validatedAddress(address);
    }

    String getBluetoothIdentityAddress() {
        return bluetoothIdentityAddress.isEmpty()
                ? getBluetoothTransportAddress() : bluetoothIdentityAddress;
    }

    private static String resolveBluetoothIdentityAddress(String transportAddress) {
        String validatedTransport = validatedAddress(transportAddress);
        if (validatedTransport.isEmpty()) return "";
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return "";
            BluetoothDevice device = adapter.getRemoteDevice(validatedTransport);
            return validatedAddress(device.getIdentityAddress());
        } catch (IllegalArgumentException | SecurityException exception) {
            return "";
        }
    }

    private static String validatedAddress(String address) {
        String candidate = address == null ? "" : address.toUpperCase(Locale.ROOT);
        return BLUETOOTH_ADDRESS.matcher(candidate).matches() ? candidate : "";
    }
}
