// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One process-wide MDR owner. All mutable state and native calls are confined to worker. */
final class SonyController {
    private static final String TAG = "HaotianSony";
    static final UUID MDR_UUID = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2");
    private static final long[] RETRY_MS = {1000, 2000, 5000, 10000, 30000};
    private static final long REQUEST_TIMEOUT_MS = 30_000;
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler worker;
    private final ExecutorService connector = Executors.newSingleThreadExecutor();
    private final RemoteCallbackList<ISonyStateCallback> callbacks = new RemoteCallbackList<>();
    private volatile String published = "{}";
    private BluetoothA2dp a2dp;
    private BluetoothDevice device;
    private BluetoothSocket socket;
    private long nativeHandle;
    private int generation;
    private int attempts;
    private long nextAttempt;
    private long requestDeadline;
    private long nextSync;
    private String selectedAddress = "";
    private String status = "disconnected";
    private String error = "";
    private JSONObject reported = new JSONObject();
    private boolean synchronizedState;
    private boolean connecting;
    private boolean pendingSync;
    private boolean awaitingReadback;
    private boolean shutdownRequested;
    private boolean manuallyStopped;

    SonyController(Context context) {
        this.context = context.getApplicationContext();
        adapter = BluetoothAdapter.getDefaultAdapter();
        HandlerThread thread = new HandlerThread("SonyController");
        thread.start(); worker = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                // These actions are protected framework broadcasts emitted by the Bluetooth UID.
                reconcile();
            }
        }, filter, Context.RECEIVER_EXPORTED);
        if (adapter != null) {
            try {
                adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) worker.post(() -> {
                            a2dp = (BluetoothA2dp) proxy; reconcileOnWorker();
                        });
                    }
                    @Override public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.A2DP) worker.post(() -> {
                            a2dp = null; reconcileOnWorker();
                        });
                    }
                }, BluetoothProfile.A2DP);
            } catch (RuntimeException e) { Log.w(TAG, "Cannot observe Bluetooth profile", e); }
        }
        worker.post(this::publish);
    }

    String getState() { return published; }
    void register(ISonyStateCallback callback) {
        if (callback == null) return;
        worker.post(() -> {
            callbacks.register(callback);
            try { callback.onStateChanged(published); } catch (RemoteException ignored) {}
        });
    }
    void unregister(ISonyStateCallback callback) {
        if (callback != null) worker.post(() -> callbacks.unregister(callback));
    }
    void reconcile() { worker.post(this::reconcileOnWorker); }

    void select(String address) {
        String canonical = canonicalAddress(address);
        if (canonical.isEmpty()) return;
        worker.post(() -> {
            try {
                if (adapter == null || !eligible(adapter.getRemoteDevice(canonical))) return;
                selectedAddress = canonical;
                manuallyStopped = false;
                reconcileOnWorker();
            } catch (RuntimeException e) { fail("Cannot select Sony device"); }
        });
    }
    void refresh(String address) {
        String canonical = canonicalAddress(address);
        worker.post(() -> {
            if (!matches(canonical)) return;
            error = "";
            if (nativeHandle != 0 && SonyNative.ready(nativeHandle) && !awaitingReadback) {
                beginRequest(0, "syncing");
            } else if (nativeHandle == 0 && !connecting) {
                nextAttempt = 0; manuallyStopped = false; reconcileOnWorker();
            }
        });
    }
    void setValue(String address, String key, int value, String target) {
        String canonical = canonicalAddress(address);
        if (key == null || key.length() > 64 || target == null || target.length() > 128) return;
        worker.post(() -> {
            if (!matches(canonical) || nativeHandle == 0 || !synchronizedState
                    || awaitingReadback || !SonyNative.ready(nativeHandle)) return;
            int result = SonyNative.set(nativeHandle, key, value, target);
            if (result != 0) {
                error = "Setting rejected (" + result + ")";
                publish(); return;
            }
            error = "";
            awaitingReadback = true;
            shutdownRequested = key.equals("power.shutdown");
            beginRequest(2, "applying");
        });
    }

    private boolean matches(String address) {
        return device != null && !address.isEmpty() && device.getAddress().equalsIgnoreCase(address);
    }
    static String canonicalAddress(String address) {
        String candidate = address == null ? "" : address.toUpperCase(Locale.ROOT);
        return BluetoothAdapter.checkBluetoothAddress(candidate) ? candidate : "";
    }
    private boolean eligible(BluetoothDevice candidate) {
        if (candidate == null || candidate.getBondState() != BluetoothDevice.BOND_BONDED) return false;
        ParcelUuid[] uuids = candidate.getUuids();
        if (uuids != null) for (ParcelUuid uuid : uuids)
            if (MDR_UUID.equals(uuid.getUuid())) return true;
        return false;
    }
    private BluetoothDevice chooseDevice() {
        if (adapter == null || !adapter.isEnabled() || a2dp == null) return null;
        List<BluetoothDevice> connected = a2dp.getConnectedDevices();
        for (BluetoothDevice candidate : connected)
            if (selectedAddress.equals(candidate.getAddress()) && eligible(candidate)) return candidate;
        selectedAddress = "";
        // BluetoothA2dp#getActiveDevice is absent from the Bluetooth module's app stubs.
        // Use the exported SystemApi, as SettingsLib's A2dpProfile does.
        for (BluetoothDevice active : adapter.getActiveDevices(BluetoothProfile.A2DP))
            if (connected.contains(active) && eligible(active)) return active;
        if (device != null && connected.contains(device) && eligible(device)) return device;
        for (BluetoothDevice candidate : connected) if (eligible(candidate)) return candidate;
        return null;
    }
    private void reconcileOnWorker() {
        BluetoothDevice chosen;
        try { chosen = chooseDevice(); }
        catch (RuntimeException e) { chosen = null; }
        if (device == null ? chosen != null : !device.equals(chosen)) {
            closeSession();
            device = chosen;
            reported = new JSONObject();
            error = ""; attempts = 0; nextAttempt = 0; manuallyStopped = false;
        }
        if (device == null) { status = "disconnected"; publish(); return; }
        if (nativeHandle == 0 && !connecting && !manuallyStopped) {
            long remaining = nextAttempt - SystemClock.elapsedRealtime();
            if (remaining <= 0) connect();
            else {
                worker.removeCallbacks(retry);
                worker.postDelayed(retry, remaining);
            }
        }
        publish();
    }
    private final Runnable retry = this::reconcileOnWorker;

    private void connect() {
        final BluetoothSocket opening;
        try { opening = device.createRfcommSocketToServiceRecord(MDR_UUID); }
        catch (IOException | RuntimeException e) { fail("RFCOMM unavailable"); return; }
        socket = opening;
        connecting = true; status = "connecting";
        final int expectedGeneration = ++generation;
        // Closing BluetoothSocket interrupts a blocked connect; the worker itself never blocks.
        worker.postDelayed(() -> {
            if (generation == expectedGeneration && connecting) fail("RFCOMM connection timed out");
        }, 15_000);
        connector.execute(() -> {
            boolean connected;
            try { opening.connect(); connected = true; }
            catch (IOException | RuntimeException e) { connected = false; }
            final boolean success = connected;
            worker.post(() -> {
                if (generation != expectedGeneration) { closeSocket(opening); return; }
                connecting = false;
                if (!success) { fail("Could not connect to Sony control service"); return; }
                try {
                    // Hidden Bluetooth-module API is intentionally resolved here rather than
                    // widening its SDK stub solely to pass a platform app its connected fd.
                    ParcelFileDescriptor pfd = (ParcelFileDescriptor) BluetoothSocket.class
                            .getMethod("getParcelFileDescriptor").invoke(opening);
                    if (pfd == null) throw new IOException("Missing RFCOMM descriptor");
                    nativeHandle = SonyNative.create(pfd.getFd()); // Native duplicates ownership.
                    if (nativeHandle == 0) throw new IOException("Cannot initialize MDR transport");
                    beginRequest(0, "initializing");
                } catch (Exception | LinkageError e) { fail("Could not initialize libmdr"); }
            });
        });
    }

    private void beginRequest(int operation, String newStatus) {
        if (nativeHandle == 0) return;
        int result = SonyNative.request(nativeHandle, operation);
        if (result != 0) { fail("MDR request failed (" + result + ")"); return; }
        status = newStatus;
        requestDeadline = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS;
        worker.removeCallbacks(poll);
        worker.post(poll);
        publish();
    }
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (nativeHandle == 0) return;
            boolean changed = false;
            // Bound work per tick even if the headset sends a notification burst.
            for (int i = 0; i < 32 && nativeHandle != 0; i++) {
                int event = SonyNative.poll(nativeHandle);
                if (event < 0) {
                    String reason = new String(SonyNative.error(nativeHandle), StandardCharsets.UTF_8);
                    if (shutdownRequested) { finishShutdown(); return; }
                    fail(reason.isEmpty() ? "MDR connection failed (" + event + ")" : reason);
                    return;
                }
                if (event == 0) break;
                if (event == 16) { beginRequest(1, "syncing"); return; }
                if (event == 17) {
                    synchronizedState = true; awaitingReadback = false;
                    status = "ready"; requestDeadline = 0; attempts = 0;
                    nextSync = SystemClock.elapsedRealtime() + 60_000;
                    changed = true;
                } else if (event == 18) {
                    if (shutdownRequested) { finishShutdown(); return; }
                    // Commit completion is an ACK, not proof that firmware retained every value.
                    // Query only the written control group, preserving cached capabilities.
                    // The native readback waits for parameter reports, not just their ACKs.
                    beginRequest(3, "applying"); return;
                } else if (event == 22) pendingSync = true;
                else changed = true;
            }
            if (nativeHandle == 0) return;
            long now = SystemClock.elapsedRealtime();
            if (requestDeadline > 0 && now >= requestDeadline) {
                fail("MDR operation timed out"); return;
            }
            if (changed && synchronizedState && !awaitingReadback) readState();
            if (SonyNative.ready(nativeHandle) && !awaitingReadback && synchronizedState
                    && (pendingSync || now >= nextSync)) {
                pendingSync = false; beginRequest(1, "syncing"); return;
            }
            worker.postDelayed(this, SonyNative.ready(nativeHandle) ? 100 : 20);
        }
    };
    private void readState() {
        try {
            reported = new JSONObject(new String(SonyNative.snapshot(nativeHandle), StandardCharsets.UTF_8));
            SonyMetadataPublisher.publish(device, reported);
        } catch (JSONException | RuntimeException e) {
            error = "Could not read headset state";
        }
        publish();
    }
    private void finishShutdown() {
        closeSession(); manuallyStopped = true; status = "disconnected"; publish();
    }
    private void fail(String reason) {
        closeSession();
        // Keep protocol diagnostics bounded and local. Never publish MACs or raw packets in logs.
        error = reason.length() > 240 ? reason.substring(0, 240) : reason;
        status = "retrying";
        long delay = RETRY_MS[Math.min(attempts++, RETRY_MS.length - 1)];
        nextAttempt = SystemClock.elapsedRealtime() + delay;
        Log.w(TAG, "Control session interrupted; retry in " + delay + " ms");
        worker.postDelayed(retry, delay);
        publish();
    }
    private void closeSession() {
        generation++;
        worker.removeCallbacks(poll); worker.removeCallbacks(retry);
        if (nativeHandle != 0) { SonyNative.destroy(nativeHandle); nativeHandle = 0; }
        closeSocket(socket); socket = null;
        connecting = false; synchronizedState = false; awaitingReadback = false;
        pendingSync = false; shutdownRequested = false; requestDeadline = 0;
    }
    private static void closeSocket(BluetoothSocket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }
    private void publish() {
        try {
            JSONObject state = new JSONObject(reported.toString());
            state.put("address", device == null ? "" : device.getAddress());
            String model = reported.optString("model");
            String name = device == null ? "" : device.getName();
            state.put("name", model.isEmpty() ? (name == null ? "Sony" : name) : model);
            state.put("status", status); state.put("error", error);
            state.put("ready", synchronizedState && nativeHandle != 0);
            state.put("busy", !status.equals("ready"));
            String encoded = state.toString();
            if (encoded.equals(published)) return;
            published = encoded;
            int count = callbacks.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    try { callbacks.getBroadcastItem(i).onStateChanged(encoded); }
                    catch (RemoteException ignored) {}
                }
            } finally { callbacks.finishBroadcast(); }
        } catch (JSONException | RuntimeException e) { Log.w(TAG, "Could not publish control state", e); }
    }
}
