/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import org.xiaomi.haotian.audio.HeadPoseFrame;
import org.xiaomi.haotian.audio.provider.HeadPoseRingWriter;
import org.xiaomi.haotian.audio.provider.HeadphoneOutput;
import org.xiaomi.haotian.audio.provider.HeadphoneWearState;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProviderCallback;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.util.List;
import java.util.Locale;

/** Process-wide owner of the Xiaomi Buds RCSP control and pose session. */
final class MiBudsController implements Closeable {
    static final String PROVIDER_ID = "xiaomi-buds4pro-rcsp";
    private static final String TAG = "HaotianMiBuds";
    private static final long[] RETRY_DELAYS_MS = {1_000, 2_000, 5_000, 10_000, 30_000};

    private final Context context;
    private final BluetoothAdapter adapter;
    private final HandlerThread workerThread;
    private final Handler worker;
    private final RemoteCallbackList<IMiBudsStateCallback> stateCallbacks =
            new RemoteCallbackList<>();
    private volatile String publishedState = "{}";

    private BluetoothA2dp a2dp;
    private BluetoothDevice device;
    private MiBudsTransport transport;
    private int transportGeneration;
    private int retryAttempt;
    private volatile boolean authenticated;
    private boolean poseRequested;
    private volatile boolean poseStreaming;
    private boolean closed;
    private boolean receiverRegistered;

    private HeadPoseRingWriter poseWriter;
    private IHeadphonePoseProviderCallback poseCallback;
    private HeadphoneOutput providerOutput;
    private volatile float[] lastRawQuaternion;
    private float[] recenterReference;
    private int discontinuityCount;

    // Settings state is readback-driven. It must never be fabricated from an ACK to a write.
    private int spatialState = -1;
    private byte[] noiseState;
    private int soundPreset = -1;
    private int lowLatency = -1;
    private boolean spatialWritePending;
    private int spatialWriteGeneration;
    private String status = "disconnected";
    private String error = "";

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            worker.post(MiBudsController.this::reconcileOnWorker);
        }
    };

    MiBudsController(Context context) {
        this.context = context.getApplicationContext();
        adapter = BluetoothAdapter.getDefaultAdapter();
        workerThread = new HandlerThread("MiBudsController");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        try {
            this.context.registerReceiver(
                    bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot observe Bluetooth broadcasts", exception);
        }

        if (adapter != null) {
            try {
                adapter.getProfileProxy(this.context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile != BluetoothProfile.A2DP) return;
                        worker.post(() -> {
                            a2dp = (BluetoothA2dp) proxy;
                            reconcileOnWorker();
                        });
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile != BluetoothProfile.A2DP) return;
                        worker.post(() -> {
                            a2dp = null;
                            reconcileOnWorker();
                        });
                    }
                }, BluetoothProfile.A2DP);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Cannot observe A2DP profile", exception);
            }
        }
        worker.post(this::publishState);
    }

    String getPublishedState() {
        return publishedState;
    }

    void registerCallback(IMiBudsStateCallback callback) {
        if (callback == null) return;
        worker.post(() -> {
            stateCallbacks.register(callback);
            try {
                callback.onStateChanged(publishedState);
            } catch (RemoteException ignored) {
            }
        });
    }

    void unregisterCallback(IMiBudsStateCallback callback) {
        if (callback != null) worker.post(() -> stateCallbacks.unregister(callback));
    }

    void refresh() {
        worker.post(() -> {
            error = "";
            MiBudsTransport current = transport;
            if (current != null && authenticated) {
                status = "connecting";
                publishState();
                queryReportedState(current);
                worker.postDelayed(() -> {
                    if (transport == current && authenticated && status.equals("connecting")) {
                        status = "ready";
                        publishState();
                    }
                }, 2_500);
            } else {
                retryAttempt = 0;
                reconcileOnWorker();
            }
        });
    }

    void setLocalSpatialEnabled(boolean enabled) {
        worker.post(() -> setSpatialBitOnWorker(0x01, enabled));
    }

    void setLocalHeadTrackingEnabled(boolean enabled) {
        worker.post(() -> setSpatialBitOnWorker(0x08, enabled));
    }

    boolean supports(HeadphoneOutput output) {
        if (output == null) return false;
        BluetoothDevice candidate = deviceForAddress(output.address);
        if (candidate != null) return isEligible(candidate);
        return isCapturedModelName(output.displayName);
    }

    boolean attachProvider(HeadphoneOutput output, HeadPoseRingWriter writer,
            IHeadphonePoseProviderCallback callback) {
        if (output == null || writer == null || callback == null || !supports(output)) {
            closeQuietly(writer);
            return false;
        }
        worker.post(() -> {
            if (closed) {
                closeQuietly(writer);
                return;
            }
            stopPoseStreamingOrCloseOnWorker();
            closeQuietly(poseWriter);
            poseWriter = writer;
            poseCallback = callback;
            providerOutput = output;
            lastRawQuaternion = null;
            recenterReference = null;
            notifyWearState(HeadphoneWearState.UNKNOWN);
            reconcileOnWorker();
        });
        return true;
    }

    void detachProvider() {
        worker.post(() -> {
            stopPoseStreamingOrCloseOnWorker();
            closeQuietly(poseWriter);
            poseWriter = null;
            poseCallback = null;
            providerOutput = null;
            lastRawQuaternion = null;
            recenterReference = null;
            reconcileOnWorker();
        });
    }

    private void stopPoseStreamingOrCloseOnWorker() {
        boolean wasStreaming = poseStreaming;
        poseRequested = false;
        updatePoseStreamingOnWorker();
        if (wasStreaming) {
            // The F3 response is asynchronous, so even a successfully queued stop
            // is not yet a verified stop. Drop RFCOMM after the best-effort write;
            // this is the reliable boundary for an unconsumed IMU producer.
            closeTransportOnWorker();
        }
    }

    void setPoseStreamingEnabled(boolean enabled) {
        worker.post(() -> {
            boolean wasStreaming = poseStreaming;
            poseRequested = enabled && poseWriter != null && providerOutput != null;
            updatePoseStreamingOnWorker();
            if (!enabled && wasStreaming) {
                closeTransportOnWorker();
                // Let a queued stop()/replacement update the provider identity first.
                worker.post(this::reconcileOnWorker);
            }
        });
    }

    boolean recenter() {
        boolean available = authenticated && poseStreaming && lastRawQuaternion != null;
        worker.post(() -> {
            if (lastRawQuaternion == null) return;
            recenterReference = lastRawQuaternion.clone();
            discontinuityCount = (discontinuityCount + 1) & 0xff;
        });
        return available;
    }

    void reconcile() {
        worker.post(this::reconcileOnWorker);
    }

    private void reconcileOnWorker() {
        if (closed) return;
        BluetoothDevice chosen = chooseDevice();
        if (device == null ? chosen != null : !device.equals(chosen)) {
            closeTransportOnWorker();
            device = chosen;
            retryAttempt = 0;
            clearReportedState();
        }
        if (device == null) {
            status = "disconnected";
            error = "";
            notifyWearState(HeadphoneWearState.DISCONNECTED);
            publishState();
            return;
        }
        if (transport == null) startTransportOnWorker(device);
    }

    private BluetoothDevice chooseDevice() {
        if (adapter == null || a2dp == null) return null;
        try {
            if (!adapter.isEnabled()) return null;
        } catch (RuntimeException exception) {
            return null;
        }
        List<BluetoothDevice> connected;
        try {
            connected = a2dp.getConnectedDevices();
        } catch (RuntimeException exception) {
            return null;
        }

        if (providerOutput != null) {
            BluetoothDevice requested = deviceForAddress(providerOutput.address);
            if (requested != null && connected.contains(requested) && isEligible(requested)) {
                return requested;
            }
            // Never substitute another connected Xiaomi peer while samples are
            // attributed to a specific HaotianAudio route/deviceKey.
            return null;
        }
        try {
            for (BluetoothDevice active : adapter.getActiveDevices(BluetoothProfile.A2DP)) {
                if (connected.contains(active) && isEligible(active)) return active;
            }
        } catch (RuntimeException ignored) {
        }
        if (device != null && connected.contains(device) && isEligible(device)) return device;
        for (BluetoothDevice candidate : connected) {
            if (isEligible(candidate)) return candidate;
        }
        return null;
    }

    private void startTransportOnWorker(BluetoothDevice target) {
        final int generation = ++transportGeneration;
        MiBudsTransport session = new MiBudsTransport(target, worker,
                new MiBudsTransport.Listener() {
                    @Override
                    public void onTransportConnected() {
                        if (!isCurrent(generation, session())) return;
                        status = "authenticating";
                        error = "";
                        publishState();
                    }

                    @Override
                    public void onAuthenticated() {
                        if (!isCurrent(generation, session())) return;
                        retryAttempt = 0;
                        authenticated = true;
                        status = "ready";
                        error = "";
                        notifyWearState(HeadphoneWearState.UNKNOWN);
                        queryReportedState(session());
                        updatePoseStreamingOnWorker();
                        publishState();
                    }

                    @Override
                    public void onConfig(int type, byte[] value) {
                        if (!isCurrent(generation, session())) return;
                        updateConfig(type, value);
                        // An unrelated F4 notification must not make a pending
                        // spatial write look complete before its 0x1e readback.
                        if (!spatialWritePending) {
                            status = "ready";
                            error = "";
                        }
                        publishState();
                    }

                    @Override
                    public void onPose(long receivedNanos, float yaw, float pitch, float roll) {
                        if (!isCurrent(generation, session())) return;
                        publishPose(receivedNanos, yaw, pitch, roll);
                    }

                    @Override
                    public void onCommandRejected(int opcode, int resultStatus,
                            boolean poseStreamCommand) {
                        if (!isCurrent(generation, session())) return;
                        if (!poseStreamCommand) spatialWritePending = false;
                        if (poseStreamCommand) {
                            poseStreaming = false;
                            error = "Earbud rejected phone head tracking ("
                                    + resultStatus + ")";
                            notifyProviderError(error);
                        } else {
                            error = "Earbud rejected setting (" + resultStatus + ")";
                        }
                        status = "ready";
                        Log.w(TAG, "Earbud rejected RCSP opcode " + opcode
                                + " with status " + resultStatus);
                        MiBudsTransport current = session();
                        if (opcode == MiBudsTransport.OPCODE_SET_CONFIG
                                && current != null && authenticated) {
                            queryReportedState(current);
                        }
                        publishState();
                    }

                    @Override
                    public void onTransportClosed(String reason) {
                        MiBudsTransport current = session();
                        if (!isCurrent(generation, current)) return;
                        transport = null;
                        // A new RFCOMM generation must earn fresh state. Keeping the
                        // previous bitfield here could let the UI preserve unknown bits
                        // from a different earbud/session in a later F2 write.
                        clearReportedState();
                        status = "retrying";
                        error = reason == null ? "Control connection ended" : reason;
                        notifyWearState(HeadphoneWearState.DISCONNECTED);
                        notifyProviderError(reason);
                        long delay = RETRY_DELAYS_MS[Math.min(
                                retryAttempt++, RETRY_DELAYS_MS.length - 1)];
                        Log.w(TAG, "Control session interrupted; retry in " + delay + " ms");
                        publishState();
                        worker.postDelayed(MiBudsController.this::reconcileOnWorker, delay);
                    }

                    private MiBudsTransport session() {
                        return transport;
                    }
                });
        transport = session;
        authenticated = false;
        poseStreaming = false;
        status = "connecting";
        error = "";
        publishState();
        session.connect();
    }

    private boolean isCurrent(int generation, MiBudsTransport session) {
        return !closed && generation == transportGeneration && session != null
                && session == transport;
    }

    private void updatePoseStreamingOnWorker() {
        boolean shouldStream = poseRequested && authenticated && poseWriter != null
                && providerOutput != null && transport != null;
        if (shouldStream == poseStreaming) return;
        if (transport == null || !transport.setPoseStreaming(shouldStream)) {
            if (shouldStream) notifyProviderError("Could not change earbud pose stream");
            return;
        }
        poseStreaming = shouldStream;
        if (!shouldStream) {
            lastRawQuaternion = null;
            recenterReference = null;
        } else {
            discontinuityCount = (discontinuityCount + 1) & 0xff;
        }
    }

    private void updateConfig(int type, byte[] value) {
        if (value == null) return;
        switch (type) {
            case MiBudsTransport.CONFIG_SPATIAL_READ:
                if (value.length == 1) {
                    spatialState = value[0] & 0xff;
                    spatialWritePending = false;
                }
                break;
            case MiBudsTransport.CONFIG_NOISE_LEVEL:
                if (value.length == 2) noiseState = value.clone();
                break;
            case MiBudsTransport.CONFIG_SOUND_PRESET:
                if (value.length == 1) soundPreset = value[0] & 0xff;
                break;
            case MiBudsTransport.CONFIG_LOW_LATENCY:
                if (value.length == 1) lowLatency = value[0] & 0xff;
                break;
            default:
                break;
        }
    }

    private void setSpatialBitOnWorker(int mask, boolean enabled) {
        MiBudsTransport current = transport;
        if (current == null || !authenticated || spatialState < 0) {
            error = "Spatial state is not available";
            publishState();
            return;
        }
        if (spatialWritePending) return;
        int requested = enabled ? spatialState | mask : spatialState & ~mask;
        if (requested == spatialState) return;
        if (!current.setConfig(MiBudsTransport.CONFIG_SPATIAL_WRITE,
                new byte[] {(byte) requested})) {
            error = "Could not write spatial setting";
            publishState();
            return;
        }
        // An F2 response only acknowledges receipt. Keep the previous displayed value
        // until the independently queried F3/0x1e readback arrives.
        status = "applying";
        spatialWritePending = true;
        int writeGeneration = ++spatialWriteGeneration;
        error = "";
        publishState();
        worker.postDelayed(() -> {
            if (transport == current && authenticated) {
                current.queryConfigs(MiBudsTransport.CONFIG_SPATIAL_READ);
            }
        }, 250);
        worker.postDelayed(() -> {
            if (transport == current && authenticated && spatialWritePending
                    && spatialWriteGeneration == writeGeneration) {
                spatialWritePending = false;
                status = "ready";
                error = "Spatial setting readback timed out";
                publishState();
            }
        }, 2_500);
    }

    private void queryReportedState(MiBudsTransport current) {
        current.queryConfigs(
                MiBudsTransport.CONFIG_SPATIAL_READ,
                MiBudsTransport.CONFIG_NOISE_LEVEL,
                MiBudsTransport.CONFIG_SOUND_PRESET,
                MiBudsTransport.CONFIG_LOW_LATENCY);
    }

    private void publishState() {
        JSONObject state = new JSONObject();
        try {
            state.put("status", status);
            state.put("error", error);
            state.put("ready", authenticated && transport != null);
            state.put("name", device == null || device.getName() == null
                    ? "Xiaomi earbuds" : device.getName());
            if (device != null) state.put("address", device.getAddress());
            if (spatialState >= 0) state.put("spatialState", spatialState);
            if (noiseState != null) {
                state.put("noiseState", noiseState[0] & 0xff);
                state.put("noiseLevel", noiseState[1] & 0xff);
            }
            if (soundPreset >= 0) state.put("soundPreset", soundPreset);
            if (lowLatency >= 0) state.put("lowLatency", lowLatency);
        } catch (JSONException | RuntimeException exception) {
            Log.w(TAG, "Could not publish earbud state", exception);
            return;
        }
        String encoded = state.toString();
        if (encoded.equals(publishedState)) return;
        publishedState = encoded;
        int count = stateCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    stateCallbacks.getBroadcastItem(i).onStateChanged(encoded);
                } catch (RemoteException ignored) {
                }
            }
        } finally {
            stateCallbacks.finishBroadcast();
        }
    }

    private void publishPose(long receivedNanos, float yawDegrees, float pitchDegrees,
            float rollDegrees) {
        if (!poseStreaming || poseWriter == null || providerOutput == null) return;
        float[] raw = eulerDegreesToQuaternion(yawDegrees, pitchDegrees, rollDegrees);
        lastRawQuaternion = raw;
        float[] output = recenterReference == null
                ? raw : multiply(conjugate(recenterReference), raw);
        long decodedNanos = SystemClock.elapsedRealtimeNanos();
        try {
            poseWriter.publish(new HeadPoseFrame(
                    providerOutput.deviceKey,
                    PROVIDER_ID,
                    receivedNanos,
                    Math.max(receivedNanos, decodedNanos),
                    output[0], output[1], output[2], output[3],
                    0f, 0f, 0f,
                    1f,
                    discontinuityCount));
        } catch (RuntimeException exception) {
            notifyProviderError("Could not publish earbud pose");
        }
    }

    /**
     * Accessory reports yaw/pitch/roll in degrees. Stock HyperOS maps them to
     * Android Z/X/Y respectively; compose intrinsic yaw, pitch, then roll.
     */
    static float[] eulerDegreesToQuaternion(float yaw, float pitch, float roll) {
        float[] qYaw = axisQuaternion(0f, 0f, 1f, yaw);
        float[] qPitch = axisQuaternion(1f, 0f, 0f, pitch);
        float[] qRoll = axisQuaternion(0f, 1f, 0f, roll);
        return normalize(multiply(multiply(qYaw, qPitch), qRoll));
    }

    private static float[] axisQuaternion(float x, float y, float z, float degrees) {
        double half = Math.toRadians(degrees) * 0.5;
        float sine = (float) Math.sin(half);
        return new float[] {x * sine, y * sine, z * sine, (float) Math.cos(half)};
    }

    private static float[] multiply(float[] left, float[] right) {
        return new float[] {
                left[3] * right[0] + left[0] * right[3]
                        + left[1] * right[2] - left[2] * right[1],
                left[3] * right[1] - left[0] * right[2]
                        + left[1] * right[3] + left[2] * right[0],
                left[3] * right[2] + left[0] * right[1]
                        - left[1] * right[0] + left[2] * right[3],
                left[3] * right[3] - left[0] * right[0]
                        - left[1] * right[1] - left[2] * right[2]
        };
    }

    private static float[] conjugate(float[] value) {
        return new float[] {-value[0], -value[1], -value[2], value[3]};
    }

    private static float[] normalize(float[] value) {
        float length = (float) Math.sqrt(value[0] * value[0] + value[1] * value[1]
                + value[2] * value[2] + value[3] * value[3]);
        if (!Float.isFinite(length) || length < 0.0001f) return new float[] {0f, 0f, 0f, 1f};
        return new float[] {
                value[0] / length, value[1] / length,
                value[2] / length, value[3] / length
        };
    }

    private BluetoothDevice deviceForAddress(String address) {
        if (adapter == null || address == null) return null;
        String canonical = address.toUpperCase(Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(canonical)) return null;
        try {
            return adapter.getRemoteDevice(canonical);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean isEligible(BluetoothDevice candidate) {
        if (candidate == null) return false;
        try {
            if (candidate.getBondState() != BluetoothDevice.BOND_BONDED) return false;
        } catch (RuntimeException exception) {
            return false;
        }
        String name;
        try {
            name = candidate.getName();
        } catch (RuntimeException exception) {
            return false;
        }
        boolean exactModel = isCapturedModelName(name);
        ParcelUuid[] uuids;
        try {
            uuids = candidate.getUuids();
        } catch (RuntimeException exception) {
            uuids = null;
        }
        if (uuids != null) {
            for (ParcelUuid uuid : uuids) {
                if (uuid != null && MiBudsTransport.MIUI_SPP_UUID.equals(uuid.getUuid())) {
                    return true;
                }
            }
        }
        return exactModel;
    }

    private static boolean isCapturedModelName(String name) {
        return name != null
                && name.toLowerCase(Locale.ROOT).contains("xiaomi buds 4 pro");
    }

    private void clearReportedState() {
        authenticated = false;
        poseStreaming = false;
        spatialState = -1;
        noiseState = null;
        soundPreset = -1;
        lowLatency = -1;
        spatialWritePending = false;
        spatialWriteGeneration++;
        lastRawQuaternion = null;
        recenterReference = null;
        discontinuityCount = (discontinuityCount + 1) & 0xff;
    }

    private void closeTransportOnWorker() {
        transportGeneration++;
        MiBudsTransport current = transport;
        transport = null;
        if (current != null) current.close();
        clearReportedState();
    }

    private void notifyWearState(int state) {
        IHeadphonePoseProviderCallback callback = poseCallback;
        HeadphoneOutput output = providerOutput;
        if (callback == null || output == null) return;
        try {
            callback.onWearStateChanged(output.deviceKey, state, state,
                    SystemClock.elapsedRealtimeNanos());
        } catch (RemoteException ignored) {
        }
    }

    private void notifyProviderError(String reason) {
        if (!poseRequested) return;
        IHeadphonePoseProviderCallback callback = poseCallback;
        HeadphoneOutput output = providerOutput;
        if (callback == null || output == null) return;
        String bounded = reason == null ? "Earbud provider error"
                : reason.substring(0, Math.min(reason.length(), 160));
        try {
            callback.onProviderError(output.deviceKey, bounded);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void close() {
        worker.post(() -> {
            if (closed) return;
            closed = true;
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(bluetoothReceiver);
                } catch (RuntimeException ignored) {
                }
                receiverRegistered = false;
            }
            if (adapter != null && a2dp != null) {
                try {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
                } catch (RuntimeException ignored) {
                }
            }
            a2dp = null;
            closeTransportOnWorker();
            closeQuietly(poseWriter);
            poseWriter = null;
            poseCallback = null;
            providerOutput = null;
            stateCallbacks.kill();
            workerThread.quitSafely();
        });
    }

    private static void closeQuietly(HeadPoseRingWriter writer) {
        if (writer == null) return;
        try {
            writer.close();
        } catch (RuntimeException ignored) {
        }
    }
}
