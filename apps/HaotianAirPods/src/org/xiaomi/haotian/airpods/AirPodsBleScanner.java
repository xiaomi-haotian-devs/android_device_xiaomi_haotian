/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.xiaomi.haotian.airpods;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Authenticated background scanner for Apple's paired proximity advertisements. */
final class AirPodsBleScanner {
    interface Listener {
        void onNearbyStateChanged(AirPodsNearbyState state);
    }

    private static final String TAG = "HaotianAirPodsBLE";
    private static final int APPLE_COMPANY_ID = 76;
    private static final long CLEANUP_INTERVAL_MS = 10_000;
    private static final long SCAN_RETRY_MS = 10_000;
    private static final long STALE_DEVICE_TIMEOUT_MS = 15_000;

    private final Context context;
    private final AirPodsProximityKeyStore keyStore;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, AirPodsNearbyState> states = new HashMap<>();
    private final Map<String, Integer> caseBatteryByAddress = new HashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private byte[] verificationIrk;
    private final java.util.Set<String> verifiedAddresses = new java.util.HashSet<>();
    private AirPodsNearbyState publishedState;
    private final Runnable retry = this::start;

    private final Runnable cleanup = new Runnable() {
        @Override
        public void run() {
            long cutoff = SystemClock.elapsedRealtime() - STALE_DEVICE_TIMEOUT_MS;
            states.entrySet().removeIf(entry -> {
                if (entry.getValue().lastSeenElapsedRealtime >= cutoff) return false;
                verifiedAddresses.remove(entry.getKey());
                caseBatteryByAddress.remove(entry.getKey());
                return true;
            });
            publishMostRecent();
            if (callback != null) handler.postDelayed(this, CLEANUP_INTERVAL_MS);
        }
    };

    AirPodsBleScanner(Context context, AirPodsProximityKeyStore keyStore, Listener listener) {
        this.context = context.getApplicationContext();
        this.keyStore = keyStore;
        this.listener = listener;
    }

    void start() {
        stop();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        byte[] irk = keyStore.getIrk();
        if (irk == null) {
            Log.i(TAG, "Waiting for AirPods proximity keys");
            return;
        }
        verificationIrk = Arrays.copyOf(irk, irk.length);
        verifiedAddresses.clear();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        byte[] data = new byte[27];
        byte[] mask = new byte[27];
        data[0] = 7;
        data[1] = 25;
        mask[0] = (byte) 0xff;
        mask[1] = (byte) 0xff;
        ScanFilter filter = new ScanFilter.Builder()
                .setManufacturerData(APPLE_COMPANY_ID, data, mask).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(500)
                .build();
        callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                process(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) process(result);
            }

            @Override
            public void onScanFailed(int errorCode) {
                if (callback != this) return;
                Log.w(TAG, "AirPods BLE scan failed: " + errorCode);
                callback = null;
                handler.removeCallbacks(cleanup);
                handler.removeCallbacks(retry);
                handler.postDelayed(retry, SCAN_RETRY_MS);
            }
        };
        try {
            scanner.startScan(java.util.Collections.singletonList(filter), settings, callback);
            handler.postDelayed(cleanup, CLEANUP_INTERVAL_MS);
            Log.i(TAG, "Authenticated AirPods BLE scan started");
        } catch (SecurityException | IllegalStateException exception) {
            Log.w(TAG, "Could not start AirPods BLE scan", exception);
            callback = null;
            handler.postDelayed(retry, SCAN_RETRY_MS);
        }
    }

    void restartForKeys() {
        byte[] current = keyStore.getIrk();
        if (current != null && (callback == null
                || !Arrays.equals(current, verificationIrk))) start();
    }

    void stop() {
        handler.removeCallbacks(cleanup);
        handler.removeCallbacks(retry);
        ScanCallback currentCallback = callback;
        BluetoothLeScanner currentScanner = scanner;
        callback = null;
        scanner = null;
        if (currentCallback != null && currentScanner != null) {
            try {
                currentScanner.stopScan(currentCallback);
            } catch (SecurityException | IllegalStateException ignored) {
            }
        }
        states.clear();
        caseBatteryByAddress.clear();
        verifiedAddresses.clear();
        publish(null);
    }

    private void process(ScanResult result) {
        ScanRecord record = result == null ? null : result.getScanRecord();
        String broadcastAddress = result == null || result.getDevice() == null
                ? "" : result.getDevice().getAddress();
        byte[] data = record == null ? null : record.getManufacturerSpecificData(APPLE_COMPANY_ID);
        byte[] irk = keyStore.getIrk();
        if (data == null || data.length <= 20 || irk == null || broadcastAddress.isEmpty()) return;
        if (!Arrays.equals(irk, verificationIrk)) {
            start();
            return;
        }
        if (!verifiedAddresses.contains(broadcastAddress)) {
            if (!BluetoothCryptography.verifyResolvablePrivateAddress(broadcastAddress, irk)) {
                return;
            }
            verifiedAddresses.add(broadcastAddress);
        }
        byte[] decrypted = BluetoothCryptography.decryptAdvertisementTail(
                data, keyStore.getEncryptionKey());
        AirPodsNearbyState state = decrypted != null && decrypted.length == 16
                ? parseEncrypted(broadcastAddress, data, decrypted)
                : parseLegacy(broadcastAddress, data);
        if (state == null) return;
        states.put(broadcastAddress, state);
        publishMostRecent();
    }

    private AirPodsNearbyState parseEncrypted(String address, byte[] data, byte[] decrypted) {
        if (data.length < 11 || decrypted.length < 4) return null;
        int status = data[5] & 0xff;
        boolean primaryLeft = ((status >> 5) & 1) == 1;
        boolean thisInCase = ((status >> 6) & 1) == 1;
        boolean swap = !primaryLeft;
        boolean xor = swap ^ thisInCase;
        boolean leftInEar = xor ? (status & 0x08) != 0 : (status & 0x02) != 0;
        boolean rightInEar = xor ? (status & 0x02) != 0 : (status & 0x08) != 0;
        int leftRaw = decrypted[swap ? 2 : 1] & 0xff;
        int rightRaw = decrypted[swap ? 1 : 2] & 0xff;
        int caseRaw = decrypted[3] & 0xff;
        int caseBattery = batteryValue(caseRaw);
        if (caseRaw == 0xff || ((caseRaw & 0x80) != 0 && (caseRaw & 0x7f) == 127)) {
            caseBattery = caseBatteryByAddress.getOrDefault(address, -1);
        } else if (caseBattery >= 0) {
            caseBatteryByAddress.put(address, caseBattery);
        }
        return newState(address, data, leftInEar, rightInEar,
                batteryValue(leftRaw), batteryValue(rightRaw), caseBattery,
                (leftRaw & 0x80) != 0, (rightRaw & 0x80) != 0, (caseRaw & 0x80) != 0);
    }

    private AirPodsNearbyState parseLegacy(String address, byte[] data) {
        if (data.length < 11) return null;
        int status = data[5] & 0xff;
        int podsBattery = data[6] & 0xff;
        int flagsCase = data[7] & 0xff;
        boolean primaryLeft = ((status >> 5) & 1) == 1;
        boolean thisInCase = ((status >> 6) & 1) == 1;
        boolean swap = !primaryLeft;
        boolean xor = swap ^ thisInCase;
        boolean leftInEar = xor ? (status & 0x08) != 0 : (status & 0x02) != 0;
        boolean rightInEar = xor ? (status & 0x02) != 0 : (status & 0x08) != 0;
        int leftNibble = swap ? (podsBattery >> 4) & 0x0f : podsBattery & 0x0f;
        int rightNibble = swap ? podsBattery & 0x0f : (podsBattery >> 4) & 0x0f;
        int flags = (flagsCase >> 4) & 0x0f;
        return newState(address, data, leftInEar, rightInEar,
                decodeBatteryNibble(leftNibble), decodeBatteryNibble(rightNibble),
                decodeBatteryNibble(flagsCase & 0x0f),
                swap ? (flags & 0x02) != 0 : (flags & 0x01) != 0,
                swap ? (flags & 0x01) != 0 : (flags & 0x02) != 0,
                (flags & 0x04) != 0);
    }

    private AirPodsNearbyState newState(String address, byte[] data, boolean leftInEar,
            boolean rightInEar, int leftBattery, int rightBattery, int caseBattery,
            boolean leftCharging, boolean rightCharging, boolean caseCharging) {
        int modelId = ((data[3] & 0xff) << 8) | (data[4] & 0xff);
        int connection = data[10] & 0xff;
        if (connection != AirPodsNearbyState.CONNECTION_DISCONNECTED
                && connection != AirPodsNearbyState.CONNECTION_IDLE
                && connection != AirPodsNearbyState.CONNECTION_MUSIC
                && connection != AirPodsNearbyState.CONNECTION_CALL
                && connection != AirPodsNearbyState.CONNECTION_RINGING
                && connection != AirPodsNearbyState.CONNECTION_HANGING_UP) {
            connection = AirPodsNearbyState.CONNECTION_UNKNOWN;
        }
        return new AirPodsNearbyState(keyStore.getActiveDevice(), address,
                SystemClock.elapsedRealtime(), data[2] == 1, modelId, modelName(modelId),
                leftBattery, rightBattery, caseBattery, leftInEar, rightInEar,
                leftCharging, rightCharging, caseCharging, ((data[8] >> 3) & 1) == 0,
                data[9] & 0xff, connection);
    }

    private void publishMostRecent() {
        AirPodsNearbyState newest = null;
        for (AirPodsNearbyState state : states.values()) {
            if (newest == null || state.lastSeenElapsedRealtime > newest.lastSeenElapsedRealtime) {
                newest = state;
            }
        }
        publish(newest);
    }

    private void publish(AirPodsNearbyState state) {
        if (state == null && publishedState == null) return;
        if (state != null && state.samePayload(publishedState)) {
            publishedState = state;
            return;
        }
        publishedState = state;
        listener.onNearbyStateChanged(state);
    }

    private static int batteryValue(int raw) {
        int value = raw & 0x7f;
        return value <= 100 ? value : -1;
    }

    private static int decodeBatteryNibble(int value) {
        if (value >= 0 && value <= 9) return value * 10;
        if (value >= 0x0a && value <= 0x0e) return 100;
        return -1;
    }

    private static String modelName(int modelId) {
        switch (modelId) {
            case 0x0e20: return "AirPods Pro";
            case 0x1420: return "AirPods Pro 2";
            case 0x2420: return "AirPods Pro 2 (USB-C)";
            case 0x0220: return "AirPods";
            case 0x0f20: return "AirPods 2";
            case 0x1320: return "AirPods 3";
            case 0x1920: return "AirPods 4";
            case 0x1b20: return "AirPods 4 (ANC)";
            case 0x0a20: return "AirPods Max";
            case 0x1f20: return "AirPods Max (USB-C)";
            default: return "AirPods";
        }
    }
}
