/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import org.xiaomi.haotian.audio.HeadPoseFrame;
import org.xiaomi.haotian.audio.provider.HeadPoseRingWriter;
import org.xiaomi.haotian.audio.provider.HeadphoneOutput;
import org.xiaomi.haotian.audio.provider.HeadphoneWearState;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProviderCallback;

import java.io.Closeable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Single process-wide owner of the AirPods AACP and ATT L2CAP channels. */
final class AirPodsController implements NormalizedHeadPoseProvider.Sink, Closeable {
    static final String ACTION_STATE_CHANGED =
            "org.xiaomi.haotian.airpods.action.STATE_CHANGED";
    private static final String TAG = "HaotianAirPods";
    private static final String STEM_PREFERENCES = "airpods_stem_actions";
    private static final int STEM_PRESS_LONG = 0x08;
    private static final int STEM_BUD_LEFT = 0x01;
    private static final int STEM_BUD_RIGHT = 0x02;
    private static final int STEM_CONFIG_LONG = 0x08;

    interface StateListener {
        void onStateChanged(AirPodsState state);
    }

    interface PoseListener {
        void onPose(HeadPoseFrame frame);
    }

    private final Object lock = new Object();
    private final Context context;
    private final BluetoothAdapter adapter;
    private final AirPodsPro3HeadPoseProvider transport;
    private final SharedPreferences stemPreferences;
    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final List<PoseListener> poseListeners = new CopyOnWriteArrayList<>();

    private AirPodsState state = new AirPodsState();
    private AudioOutputIdentity audioOutput;
    private AudioOutputIdentity connectedOutput;
    private AudioOutputIdentity controlOutput;
    private AudioOutputIdentity activeOutput;
    private HeadPoseRingWriter audioPoseWriter;
    private IHeadphonePoseProviderCallback audioCallback;
    private boolean audioPoseRequested;
    private boolean transportStarted;
    private boolean poseStreamingEnabled;
    private boolean closed;
    private String configuredStemAddress = "";
    private int configuredStemValue = -1;

    AirPodsController(Context context) {
        this.context = context.getApplicationContext();
        adapter = BluetoothAdapter.getDefaultAdapter();
        stemPreferences = this.context.getSharedPreferences(
                STEM_PREFERENCES, Context.MODE_PRIVATE);
        transport = new AirPodsPro3HeadPoseProvider(adapter, this::onTransportStateChanged,
                this::onStemPress);
    }

    void addStateListener(StateListener listener) {
        if (listener != null) stateListeners.add(listener);
    }

    void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    AirPodsState getState() {
        synchronized (lock) {
            return new AirPodsState(state);
        }
    }

    boolean supports(HeadphoneOutput output) {
        return output != null && transport.supports(new AudioOutputIdentity(output));
    }

    boolean attachAudio(HeadphoneOutput output, HeadPoseRingWriter writer,
            IHeadphonePoseProviderCallback callback) {
        if (output == null || writer == null || callback == null) {
            closeQuietly(writer);
            return false;
        }
        AudioOutputIdentity requested = new AudioOutputIdentity(output);
        if (!transport.supports(requested)) {
            closeQuietly(writer);
            return false;
        }
        synchronized (lock) {
            if (closed) {
                closeQuietly(writer);
                return false;
            }
            closeQuietly(audioPoseWriter);
            audioPoseWriter = writer;
            audioCallback = callback;
            audioOutput = requested;
            audioPoseRequested = false;
            reconcileTransportLocked();
        }
        dispatchWearState(getState());
        return true;
    }

    void detachAudio() {
        synchronized (lock) {
            audioPoseRequested = false;
            closeQuietly(audioPoseWriter);
            audioPoseWriter = null;
            audioCallback = null;
            audioOutput = null;
            reconcileTransportLocked();
        }
    }

    void setAudioPoseStreamingEnabled(boolean enabled) {
        synchronized (lock) {
            audioPoseRequested = enabled && audioOutput != null && audioPoseWriter != null;
            reconcilePoseLocked();
        }
    }

    boolean recenter() {
        synchronized (lock) {
            return transportStarted && poseStreamingEnabled && transport.recenter();
        }
    }

    boolean addPoseListener(PoseListener listener) {
        if (listener == null) return false;
        AirPodsState snapshot = getState();
        if (!snapshot.connected || snapshot.deviceAddress.isEmpty()) return false;
        if (!poseListeners.contains(listener)) poseListeners.add(listener);
        synchronized (lock) {
            reconcilePoseLocked();
        }
        return true;
    }

    void removePoseListener(PoseListener listener) {
        poseListeners.remove(listener);
        synchronized (lock) {
            reconcilePoseLocked();
        }
    }

    boolean setControlConnectionEnabled(String deviceAddress, boolean enabled) {
        synchronized (lock) {
            if (!enabled) {
                controlOutput = null;
                reconcileTransportLocked();
                return true;
            }
            AudioOutputIdentity requested = outputForAddress(deviceAddress);
            if (requested == null || !transport.supports(requested)) return false;
            controlOutput = requested;
            reconcileTransportLocked();
            return true;
        }
    }

    void onA2dpConnectionChanged(BluetoothDevice device, boolean connected) {
        if (device == null) return;
        synchronized (lock) {
            String address = device.getAddress();
            if (!connected) {
                if (connectedOutput != null && address.equalsIgnoreCase(
                        connectedOutput.getBluetoothTransportAddress())) {
                    connectedOutput = null;
                    reconcileTransportLocked();
                }
                return;
            }
            AudioOutputIdentity requested = outputForDevice(device);
            if (requested == null || !transport.supports(requested)) return;
            connectedOutput = requested;
            reconcileTransportLocked();
        }
    }

    void onBluetoothDisabled() {
        synchronized (lock) {
            audioOutput = null;
            audioPoseRequested = false;
            connectedOutput = null;
            controlOutput = null;
            reconcileTransportLocked();
        }
    }

    boolean setNoiseControlMode(String address, int mode) {
        return transport.setNoiseControlMode(address, mode);
    }

    boolean setControlValue(String address, int identifier, byte[] value) {
        return transport.setControlValue(address, identifier, value);
    }

    boolean setLongPressAction(String address, boolean left, int action) {
        if (!isLongPressAction(action)) return false;
        AirPodsState snapshot;
        int stemConfig;
        synchronized (lock) {
            if (!matchesConnectedAddressLocked(address)) return false;
            stemPreferences.edit().putInt(stemPreferenceKey(state.deviceAddress, left), action)
                    .apply();
            if (left) {
                state.leftLongPressAction = action;
            } else {
                state.rightLongPressAction = action;
            }
            stemConfig = stemConfigFor(state);
            configuredStemAddress = state.deviceAddress;
            configuredStemValue = stemConfig;
            snapshot = new AirPodsState(state);
        }
        transport.setStemConfig(stemConfig);
        publishState(snapshot);
        return true;
    }

    boolean rename(String address, String name) {
        return transport.rename(address, name);
    }

    boolean refreshAttValue(String address, int handle) {
        return transport.refreshAttValue(address, handle);
    }

    boolean setAttValue(String address, int handle, byte[] value) {
        return transport.setAttValue(address, handle, value);
    }

    boolean sendAacpMessage(String address, int opcode, byte[] body) {
        return transport.sendAacpMessage(address, opcode, body);
    }

    @Override
    public void onPose(HeadPoseFrame frame) {
        HeadPoseRingWriter writer;
        boolean publishToAudio;
        synchronized (lock) {
            writer = audioPoseWriter;
            publishToAudio = audioPoseRequested && writer != null;
        }
        if (publishToAudio) writer.publish(frame);
        for (PoseListener listener : poseListeners) listener.onPose(frame);
    }

    @Override
    public void onProviderError(String providerId, String deviceKey, String reason) {
        Log.w(TAG, providerId + ": " + reason);
        IHeadphonePoseProviderCallback callback;
        synchronized (lock) {
            callback = audioCallback;
        }
        if (callback == null) return;
        try {
            callback.onProviderError(deviceKey, reason);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void onWearStateChanged(NormalizedHeadPoseProvider.WearState ignored) {
        // The full state callback below preserves out-of-ear, in-case and disconnected values.
    }

    private void onTransportStateChanged(AirPodsState requestedState) {
        AirPodsState snapshot = requestedState == null
                ? new AirPodsState() : new AirPodsState(requestedState);
        boolean configureStem = false;
        int stemConfig = 0;
        synchronized (lock) {
            populateLongPressActions(snapshot);
            state = snapshot;
            if (snapshot.connected) {
                stemConfig = stemConfigFor(snapshot);
                if (!snapshot.deviceAddress.equalsIgnoreCase(configuredStemAddress)
                        || configuredStemValue != stemConfig) {
                    configuredStemAddress = snapshot.deviceAddress;
                    configuredStemValue = stemConfig;
                    configureStem = true;
                }
            } else {
                configuredStemAddress = "";
                configuredStemValue = -1;
            }
        }
        if (configureStem) transport.setStemConfig(stemConfig);
        publishState(snapshot);
    }

    private void publishState(AirPodsState snapshot) {
        dispatchWearState(snapshot);
        for (StateListener listener : stateListeners) {
            listener.onStateChanged(new AirPodsState(snapshot));
        }
        context.sendBroadcast(new Intent(ACTION_STATE_CHANGED)
                .setPackage(context.getPackageName()));
    }

    private void populateLongPressActions(AirPodsState snapshot) {
        snapshot.leftLongPressAction = longPressAction(snapshot.deviceAddress, true);
        snapshot.rightLongPressAction = longPressAction(snapshot.deviceAddress, false);
    }

    private int longPressAction(String address, boolean left) {
        int action = stemPreferences.getInt(stemPreferenceKey(address, left),
                AirPodsState.LONG_PRESS_NOISE_CONTROL);
        return isLongPressAction(action) ? action : AirPodsState.LONG_PRESS_NOISE_CONTROL;
    }

    private static boolean isLongPressAction(int action) {
        return action == AirPodsState.LONG_PRESS_NOISE_CONTROL
                || action == AirPodsState.LONG_PRESS_VOICE_ASSISTANT;
    }

    private static int stemConfigFor(AirPodsState snapshot) {
        return snapshot.leftLongPressAction == AirPodsState.LONG_PRESS_VOICE_ASSISTANT
                || snapshot.rightLongPressAction == AirPodsState.LONG_PRESS_VOICE_ASSISTANT
                ? STEM_CONFIG_LONG : 0;
    }

    private static String stemPreferenceKey(String address, boolean left) {
        String device = TextUtils.isEmpty(address) ? "default"
                : address.toLowerCase(Locale.ROOT).replace(":", "");
        return device + (left ? "_left_long_press" : "_right_long_press");
    }

    private boolean matchesConnectedAddressLocked(String address) {
        return state.connected && (TextUtils.isEmpty(address)
                || address.equalsIgnoreCase(state.deviceAddress));
    }

    private void onStemPress(int pressType, int bud) {
        if (pressType != STEM_PRESS_LONG
                || (bud != STEM_BUD_LEFT && bud != STEM_BUD_RIGHT)) return;
        AirPodsState snapshot;
        int action;
        synchronized (lock) {
            if (!state.connected || configuredStemValue != STEM_CONFIG_LONG) return;
            snapshot = new AirPodsState(state);
            action = bud == STEM_BUD_LEFT
                    ? state.leftLongPressAction : state.rightLongPressAction;
        }
        if (action == AirPodsState.LONG_PRESS_VOICE_ASSISTANT) {
            launchVoiceAssistant();
        } else {
            cycleNoiseControlMode(snapshot);
        }
    }

    private void launchVoiceAssistant() {
        try {
            context.startActivityAsUser(new Intent(Intent.ACTION_VOICE_COMMAND)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), UserHandle.CURRENT);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not launch the configured voice assistant", exception);
        }
    }

    private void cycleNoiseControlMode(AirPodsState snapshot) {
        byte[] configuredModes = snapshot.getControlValue(0x1a);
        int bits = configuredModes == null || configuredModes.length == 0
                ? 0x07 : configuredModes[0] & 0xff;
        byte[] allowOffValue = snapshot.getControlValue(0x34);
        boolean allowOff = allowOffValue == null || (allowOffValue.length > 0
                && allowOffValue[0] == 0x01);
        int[] order = {
                AirPodsState.NOISE_OFF,
                AirPodsState.NOISE_TRANSPARENCY,
                AirPodsState.NOISE_ADAPTIVE,
                AirPodsState.NOISE_CANCELLATION
        };
        int current = snapshot.noiseControlMode;
        if (current == AirPodsState.NOISE_UNKNOWN) {
            byte[] reported = snapshot.getControlValue(0x0d);
            if (reported != null && reported.length > 0) current = reported[0] & 0xff;
        }
        int first = AirPodsState.NOISE_UNKNOWN;
        boolean useNext = false;
        for (int mode : order) {
            int bit = 1 << (mode - 1);
            if ((bits & bit) == 0 || (mode == AirPodsState.NOISE_OFF && !allowOff)) continue;
            if (first == AirPodsState.NOISE_UNKNOWN) first = mode;
            if (useNext) {
                transport.setNoiseControlMode(snapshot.deviceAddress, mode);
                return;
            }
            if (mode == current) useNext = true;
        }
        if (first != AirPodsState.NOISE_UNKNOWN) {
            transport.setNoiseControlMode(snapshot.deviceAddress, first);
        }
    }

    private void dispatchWearState(AirPodsState snapshot) {
        IHeadphonePoseProviderCallback callback;
        synchronized (lock) {
            callback = audioCallback;
        }
        if (callback == null) return;
        try {
            int leftWear = snapshot.connected
                    ? snapshot.leftWear : HeadphoneWearState.DISCONNECTED;
            int rightWear = snapshot.connected
                    ? snapshot.rightWear : HeadphoneWearState.DISCONNECTED;
            callback.onWearStateChanged(snapshot.deviceKey, leftWear,
                    rightWear, snapshot.updatedElapsedRealtimeNanos > 0
                            ? snapshot.updatedElapsedRealtimeNanos
                            : SystemClock.elapsedRealtimeNanos());
        } catch (RemoteException ignored) {
        }
    }

    private void reconcileTransportLocked() {
        AudioOutputIdentity requested = audioOutput != null ? audioOutput
                : connectedOutput != null ? connectedOutput : controlOutput;
        if (requested == null) {
            if (transportStarted) transport.stop();
            transportStarted = false;
            activeOutput = null;
            poseStreamingEnabled = false;
            return;
        }
        if (!transportStarted || activeOutput == null
                || !TextUtils.equals(activeOutput.deviceKey, requested.deviceKey)) {
            if (transportStarted) transport.stop();
            activeOutput = requested;
            transport.start(requested, this);
            transportStarted = true;
            poseStreamingEnabled = false;
        }
        reconcilePoseLocked();
    }

    private void reconcilePoseLocked() {
        boolean requested = transportStarted
                && (audioPoseRequested || !poseListeners.isEmpty());
        if (requested == poseStreamingEnabled) return;
        poseStreamingEnabled = requested;
        transport.setPoseStreamingEnabled(requested);
    }

    private AudioOutputIdentity outputForAddress(String deviceAddress) {
        if (adapter == null || TextUtils.isEmpty(deviceAddress)) return null;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(
                    deviceAddress.toUpperCase(Locale.ROOT));
            return outputForDevice(device);
        } catch (IllegalArgumentException | SecurityException exception) {
            Log.w(TAG, "Could not select AirPods control peer", exception);
            return null;
        }
    }

    private static AudioOutputIdentity outputForDevice(BluetoothDevice device) {
        if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED) return null;
        try {
            String name = device.getAlias();
            if (TextUtils.isEmpty(name)) name = device.getName();
            return AudioOutputIdentity.forBluetoothClassic(device.getAddress(), name);
        } catch (IllegalArgumentException | SecurityException exception) {
            return null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            audioOutput = null;
            connectedOutput = null;
            controlOutput = null;
            audioCallback = null;
            audioPoseRequested = false;
            closeQuietly(audioPoseWriter);
            audioPoseWriter = null;
            poseListeners.clear();
            stateListeners.clear();
            transport.close();
            transportStarted = false;
            poseStreamingEnabled = false;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
