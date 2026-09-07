/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.xiaomi.haotian.airpods;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** LibrePods Smart Routing/TiPi policy backed by the local AirPods transport. */
final class SmartRoutingManager {
    static final String PREF_TAKEOVER_DISCONNECTED = "takeover_when_disconnected";
    static final String PREF_TAKEOVER_IDLE = "takeover_when_idle";
    static final String PREF_TAKEOVER_MUSIC = "takeover_when_music";
    static final String PREF_TAKEOVER_CALL = "takeover_when_call";
    static final String PREF_TAKEOVER_RINGING = "takeover_when_ringing_call";
    static final String PREF_TAKEOVER_MEDIA_START = "takeover_when_media_start";

    private static final String TAG = "HaotianAirPodsRouting";
    private static final int OPCODE_AUDIO_SOURCE = 0x0e;
    private static final int OPCODE_SMART_RESPONSE = 0x11;
    private static final int OPCODE_CONNECTED_DEVICES = 0x2e;
    private static final int OPCODE_PROXIMITY_KEYS = 0x31;
    private static final int CONTROL_OWNS_CONNECTION = 0x06;
    private static final long TAKEOVER_COOLDOWN_MS = 5_000;

    private final Context context;
    private final SharedPreferences preferences;
    private final AirPodsProximityKeyStore keyStore;
    private final Runnable proximityKeysChanged;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, RemoteDevice> connectedDevices = new LinkedHashMap<>();
    private IAirPodsService service;
    private AirPodsState aacpState;
    private AirPodsNearbyState nearbyState;
    private byte[] lastConnectedDevicesBody;
    private byte[] lastAudioSourceBody;
    private byte[] lastSmartResponseBody;
    private byte[] lastProximityKeysBody;
    private String pendingTakeover;
    private String sessionAddress = "";
    private long lastSmartResponseSequence = -1;
    private long lastTakeoverElapsedRealtime;
    private boolean sessionConnected;
    private boolean controlPinned;
    private Boolean lastOwnsConnection;

    SmartRoutingManager(Context context, AirPodsProximityKeyStore keyStore,
            Runnable proximityKeysChanged) {
        this.context = context.getApplicationContext();
        this.keyStore = keyStore;
        this.proximityKeysChanged = proximityKeysChanged;
        preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
    }

    void onAudioServiceDisconnected() {
        service = null;
        aacpState = null;
        controlPinned = false;
        lastOwnsConnection = null;
        sessionConnected = false;
    }

    void onAirPodsState(IAirPodsService requestedService, AirPodsState state) {
        boolean newAudioService = service != requestedService;
        service = requestedService;
        aacpState = state;
        if (state == null) {
            if (newAudioService && nearbyState == null) clearControlPin();
            return;
        }
        boolean newSession = !state.deviceAddress.equalsIgnoreCase(sessionAddress)
                || (state.connected && !sessionConnected);
        sessionAddress = state.deviceAddress;
        sessionConnected = state.connected;
        if (newSession) resetSessionMessages();
        if (!state.deviceAddress.isEmpty()) keyStore.setActiveDevice(state.deviceAddress);
        if (nearbyState != null) {
            setControlPinned(true);
        } else if (newAudioService) {
            clearControlPin();
        }

        byte[] keys = state.getAacpMessageValue(OPCODE_PROXIMITY_KEYS);
        if (changed(keys, lastProximityKeysBody)) {
            lastProximityKeysBody = copy(keys);
            if (keyStore.updateFromAacp(state.deviceAddress, keys)) proximityKeysChanged.run();
        }

        byte[] devices = state.getAacpMessageValue(OPCODE_CONNECTED_DEVICES);
        if (changed(devices, lastConnectedDevicesBody)) {
            lastConnectedDevicesBody = copy(devices);
            updateConnectedDevices(devices);
        }

        byte[] ownership = state.getControlValue(CONTROL_OWNS_CONNECTION);
        if (ownership != null && ownership.length > 0) {
            boolean owns = ownership[0] == 1;
            if (Boolean.TRUE.equals(lastOwnsConnection) && !owns) releaseProfiles(state);
            lastOwnsConnection = owns;
        }

        byte[] source = state.getAacpMessageValue(OPCODE_AUDIO_SOURCE);
        if (changed(source, lastAudioSourceBody)) {
            lastAudioSourceBody = copy(source);
            handleAudioSource(source);
        }

        byte[] response = state.getAacpMessageValue(OPCODE_SMART_RESPONSE);
        long smartResponseSequence = state.getAacpMessageSequence(OPCODE_SMART_RESPONSE);
        boolean newSmartResponse = lastSmartResponseSequence >= 0
                && smartResponseSequence != lastSmartResponseSequence;
        if (changed(response, lastSmartResponseBody)) {
            lastSmartResponseBody = copy(response);
            if (!newSession) handleSmartResponse(response);
        } else if (newSmartResponse && response != null) {
            handleSmartResponse(response);
        }

        lastSmartResponseSequence = smartResponseSequence;
        if (pendingTakeover != null && state.connected) executePendingTakeover();
    }

    void onNearbyState(AirPodsNearbyState state) {
        nearbyState = state;
        if (state != null && !state.classicAddress.isEmpty()) {
            setControlPinned(true);
            if (pendingTakeover != null && aacpState != null && aacpState.connected) {
                executePendingTakeover();
            }
        } else {
            setControlPinned(false);
        }
    }

    void onLocalMediaPlaybackChanged(boolean playing) {
        sendLocalMediaState(playing);
        if (playing && preferences.getBoolean(PREF_TAKEOVER_MEDIA_START, false)) {
            requestTakeover("music");
        }
    }

    void onPhoneStateChanged(String phoneState) {
        if (!android.telephony.TelephonyManager.EXTRA_STATE_RINGING.equals(phoneState)
                && !android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK.equals(phoneState)) {
            return;
        }
        if (!preferences.getBoolean(PREF_TAKEOVER_RINGING, false)) return;
        requestTakeover("call");
    }

    private void requestTakeover(String reason) {
        AirPodsNearbyState nearby = nearbyState;
        if (ownsLocally()) return;
        if (nearby == null || !nearby.isWorn() || !allowsRemoteState(nearby.connectionState)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastTakeoverElapsedRealtime < TAKEOVER_COOLDOWN_MS) return;
        pendingTakeover = reason;
        setControlPinned(true);
        if (aacpState != null && aacpState.connected) executePendingTakeover();
    }

    private void executePendingTakeover() {
        String reason = pendingTakeover;
        AirPodsState state = aacpState;
        if (reason == null || state == null || !state.connected) return;
        String self = localAddress();
        String deviceAddress = state.deviceAddress.isEmpty()
                ? keyStore.getActiveDevice() : state.deviceAddress;
        if (self.isEmpty() || deviceAddress.isEmpty()) return;
        if (ownsLocally()) {
            pendingTakeover = null;
            return;
        }

        if ("music".equals(reason)) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        sendOwnership(deviceAddress, true);
        RemoteDevice primaryRemote = null;
        for (RemoteDevice remote : connectedDevices.values()) {
            if (remote.address.equalsIgnoreCase(self)) continue;
            if (primaryRemote == null) primaryRemote = remote;
        }
        if (primaryRemote != null) {
            send(deviceAddress, SmartRoutingPackets.mediaInformation(self,
                    primaryRemote.address, "music".equals(reason)));
            send(deviceAddress, SmartRoutingPackets.showNearbyUi(primaryRemote.address));
        }
        for (RemoteDevice remote : connectedDevices.values()) {
            if (!remote.address.equalsIgnoreCase(self)) {
                send(deviceAddress, SmartRoutingPackets.hijackRequest(remote.address));
            }
        }
        connectProfiles(deviceAddress);
        pendingTakeover = null;
        lastTakeoverElapsedRealtime = SystemClock.elapsedRealtime();
        if ("music".equals(reason)) {
            handler.postDelayed(() -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY), 800);
            handler.postDelayed(() -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY), 1_800);
        }
        Log.i(TAG, "Requested AirPods takeover for " + reason);
    }

    private boolean allowsRemoteState(int state) {
        switch (state) {
            case AirPodsNearbyState.CONNECTION_DISCONNECTED:
                return preferences.getBoolean(PREF_TAKEOVER_DISCONNECTED, false);
            case AirPodsNearbyState.CONNECTION_IDLE:
                return preferences.getBoolean(PREF_TAKEOVER_IDLE, false);
            case AirPodsNearbyState.CONNECTION_MUSIC:
                return preferences.getBoolean(PREF_TAKEOVER_MUSIC, false);
            case AirPodsNearbyState.CONNECTION_CALL:
            case AirPodsNearbyState.CONNECTION_RINGING:
            case AirPodsNearbyState.CONNECTION_HANGING_UP:
                return preferences.getBoolean(PREF_TAKEOVER_CALL, false);
            default:
                return false;
        }
    }

    private void sendLocalMediaState(boolean playing) {
        AirPodsState state = aacpState;
        String self = localAddress();
        if (state == null || !state.connected || state.deviceAddress.isEmpty()
                || self.isEmpty()) {
            return;
        }
        for (RemoteDevice remote : connectedDevices.values()) {
            if (!remote.address.equalsIgnoreCase(self)) {
                send(state.deviceAddress, SmartRoutingPackets.mediaInformation(
                        self, remote.address, playing));
                return;
            }
        }
    }

    private void updateConnectedDevices(byte[] body) {
        if (body == null || body.length < 3) return;
        int count = body[2] & 0xff;
        if (count > 8) return;
        int offset = 3;
        Map<String, RemoteDevice> parsed = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            if (offset + 8 > body.length) return;
            String address = formatMac(body, offset, false);
            parsed.put(address, new RemoteDevice(address, body[offset + 6], body[offset + 7]));
            offset += 8;
        }
        List<String> added = new ArrayList<>(parsed.keySet());
        added.removeAll(connectedDevices.keySet());
        connectedDevices.clear();
        connectedDevices.putAll(parsed);

        String self = localAddress();
        AirPodsState state = aacpState;
        if (self.isEmpty() || state == null || !state.connected) return;
        for (String target : added) {
            if (target.equalsIgnoreCase(self)) continue;
            send(state.deviceAddress,
                    SmartRoutingPackets.mediaInformationForNewDevice(self, target));
            send(state.deviceAddress, SmartRoutingPackets.addTipiDevice(self, target));
        }
    }

    private void handleAudioSource(byte[] body) {
        if (body == null || body.length < 7) return;
        String source = formatMac(body, 0, true);
        int type = body[6] & 0xff;
        String self = localAddress();
        if (type != 0 && !self.isEmpty() && !source.equalsIgnoreCase(self)) handOff(false);
    }

    private void handleSmartResponse(byte[] body) {
        if (body == null || body.length < 6) return;
        String value = new String(body, StandardCharsets.ISO_8859_1);
        if (value.contains("SetOwnershipToFalse")) {
            handOff(value.contains("ReverseBannerTapped"));
        }
        // ShowNearbyUI is intentionally ignored: this integration has no popup/island surface.
    }

    private void handOff(boolean reversed) {
        AirPodsState state = aacpState;
        if (state == null || state.deviceAddress.isEmpty()) return;
        lastOwnsConnection = false;
        sendOwnership(state.deviceAddress, false);
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        disconnectProfiles(state.deviceAddress);
        Log.i(TAG, reversed
                ? "Released AirPods ownership after a reverse request"
                : "Released AirPods ownership to another device");
    }

    private void releaseProfiles(AirPodsState state) {
        pendingTakeover = null;
        lastOwnsConnection = false;
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        if (!state.deviceAddress.isEmpty()) disconnectProfiles(state.deviceAddress);
    }

    private boolean ownsLocally() {
        AirPodsState state = aacpState;
        if (state == null || !state.connected) return false;
        byte[] ownership = state.getControlValue(CONTROL_OWNS_CONNECTION);
        if (ownership == null || ownership.length == 0 || ownership[0] != 1) return false;
        byte[] source = state.getAacpMessageValue(OPCODE_AUDIO_SOURCE);
        if (source == null || source.length < 7 || (source[6] & 0xff) == 0) return true;
        String self = localAddress();
        return !self.isEmpty() && formatMac(source, 0, true).equalsIgnoreCase(self);
    }

    private void resetSessionMessages() {
        connectedDevices.clear();
        lastConnectedDevicesBody = null;
        lastAudioSourceBody = null;
        lastSmartResponseBody = null;
        lastProximityKeysBody = null;
        lastOwnsConnection = null;
        lastSmartResponseSequence = -1;
    }

    private void setControlPinned(boolean enabled) {
        IAirPodsService current = service;
        String address = keyStore.getActiveDevice();
        if (current == null || address.isEmpty() || enabled == controlPinned) return;
        try {
            if (current.setAirPodsControlConnectionEnabled(address, enabled)) {
                controlPinned = enabled;
            }
        } catch (RemoteException exception) {
            service = null;
            controlPinned = false;
        }
    }

    private void clearControlPin() {
        IAirPodsService current = service;
        if (current == null) return;
        try {
            current.setAirPodsControlConnectionEnabled("", false);
        } catch (RemoteException exception) {
            service = null;
        }
        controlPinned = false;
    }

    private void sendOwnership(String deviceAddress, boolean owns) {
        IAirPodsService current = service;
        if (current == null) return;
        try {
            current.setAirPodsControlValue(deviceAddress, CONTROL_OWNS_CONNECTION,
                    new byte[] {(byte) (owns ? 1 : 0)});
        } catch (RemoteException ignored) {
        }
    }

    private void send(String deviceAddress, byte[] body) {
        IAirPodsService current = service;
        if (current == null) return;
        try {
            current.sendAirPodsAacpMessage(deviceAddress, SmartRoutingPackets.OPCODE, body);
        } catch (RemoteException | IllegalArgumentException ignored) {
        }
    }

    private void connectProfiles(String address) {
        withDevice(address, (adapter, device) -> {
            adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        if (proxy instanceof BluetoothA2dp) {
                            device.connect();
                        }
                    } finally {
                        adapter.closeProfileProxy(profile, proxy);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.A2DP);
            adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        if (proxy instanceof BluetoothHeadset) {
                            ((BluetoothHeadset) proxy).connect(device);
                        }
                    } finally {
                        adapter.closeProfileProxy(profile, proxy);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.HEADSET);
        });
    }

    private void disconnectProfiles(String address) {
        withDevice(address, (adapter, device) -> {
            adapter.getProfileProxy(context, new ProfileDisconnectListener(adapter, device),
                    BluetoothProfile.A2DP);
            adapter.getProfileProxy(context, new ProfileDisconnectListener(adapter, device),
                    BluetoothProfile.HEADSET);
        });
    }

    private void withDevice(String address, DeviceAction action) {
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || address == null || address.isEmpty()) return;
            BluetoothDevice device = adapter.getRemoteDevice(address.toUpperCase(Locale.ROOT));
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) action.run(adapter, device);
        } catch (IllegalArgumentException | SecurityException exception) {
            Log.w(TAG, "Could not change AirPods audio profiles", exception);
        }
    }

    private String localAddress() {
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            String address = adapter == null ? "" : adapter.getAddress();
            String normalized = address == null ? "" : address.toUpperCase(Locale.ROOT);
            if (normalized.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
                    && !"02:00:00:00:00:00".equals(normalized)) {
                return normalized;
            }
        } catch (SecurityException ignored) {
        }
        return "";
    }

    private void dispatchMediaKey(int keyCode) {
        AudioManager manager = context.getSystemService(AudioManager.class);
        if (manager == null) return;
        long time = android.os.SystemClock.uptimeMillis();
        manager.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0));
        manager.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private static String formatMac(byte[] value, int offset, boolean reverse) {
        StringBuilder result = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            int index = reverse ? offset + 5 - i : offset + i;
            if (i > 0) result.append(':');
            result.append(String.format(Locale.ROOT, "%02X", value[index] & 0xff));
        }
        return result.toString();
    }

    private static boolean changed(byte[] value, byte[] previous) {
        return value != null && !Arrays.equals(value, previous);
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private interface DeviceAction {
        void run(BluetoothAdapter adapter, BluetoothDevice device);
    }

    private static final class RemoteDevice {
        final String address;
        final byte info1;
        final byte info2;

        RemoteDevice(String address, byte info1, byte info2) {
            this.address = address;
            this.info1 = info1;
            this.info2 = info2;
        }
    }

    private static final class ProfileDisconnectListener
            implements BluetoothProfile.ServiceListener {
        private final BluetoothAdapter adapter;
        private final BluetoothDevice device;

        ProfileDisconnectListener(BluetoothAdapter adapter, BluetoothDevice device) {
            this.adapter = adapter;
            this.device = device;
        }

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            try {
                if (proxy instanceof BluetoothA2dp) {
                    device.disconnect();
                } else if (proxy instanceof BluetoothHeadset) {
                    ((BluetoothHeadset) proxy).disconnect(device);
                }
            } finally {
                adapter.closeProfileProxy(profile, proxy);
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
        }
    }
}
