/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.media.AudioDeviceInfo;

import java.util.Locale;
import java.util.regex.Pattern;

/** Stable, read-only output identity passed to optional provider adapters. */
public final class AudioOutputIdentity {
    private static final Pattern BLUETOOTH_ADDRESS =
            Pattern.compile("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}");

    public final String deviceKey;
    public final String displayName;
    public final int deviceType;
    public final String address;
    private final String bluetoothIdentityAddress;

    AudioOutputIdentity(OutputDeviceManager.ActiveOutput output) {
        deviceKey = output.key;
        displayName = output.displayName;
        deviceType = output.type;
        address = output.address;
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

    public boolean isBluetoothClassicAudio() {
        return deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
    }

    /** Address on which the current A2DP transport is reachable. */
    public String getBluetoothTransportAddress() {
        return validatedAddress(address);
    }

    /**
     * Returns the controller identity address used by AudioService for head-tracker UUID matching.
     * Classic devices normally use the same public address, but resolving it explicitly also
     * handles dual-mode peers whose active transport address is an alias.
     */
    public String getBluetoothIdentityAddress() {
        if (!bluetoothIdentityAddress.isEmpty()) return bluetoothIdentityAddress;
        return getBluetoothTransportAddress();
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
        // BluetoothAdapter#getRemoteDevice(String) validates the platform's canonical address
        // syntax and, on current Android Bluetooth modules, rejects lower-case hexadecimal even
        // though it represents the same address. OutputDeviceManager deliberately keeps its
        // persistent device key lower-case; only the address handed to Bluetooth APIs is
        // canonicalized here.
        String candidate = address == null ? "" : address.toUpperCase(Locale.ROOT);
        return BLUETOOTH_ADDRESS.matcher(candidate).matches() ? candidate : "";
    }
}
