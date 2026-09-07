/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.app.Application;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/** Owns the process-wide AirPods transport and local policy state. */
public final class HaotianAirPodsApplication extends Application {
    private static final String TAG = "HaotianAirPods";
    private static final long REBIND_DELAY_MS = 1_000;
    private static final ComponentName CONTROL_SERVICE = new ComponentName(
            "org.xiaomi.haotian.airpods",
            "org.xiaomi.haotian.airpods.AirPodsControlService");
    private static HaotianAirPodsApplication instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private IAirPodsService audioService;
    private boolean bindingOrBound;
    private AirPodsController controller;
    private AirPodsProximityKeyStore proximityKeyStore;
    private AirPodsBleScanner bleScanner;
    private SmartRoutingManager smartRouting;
    private volatile AirPodsNearbyState nearbyState;
    private AudioManager audioManager;
    private boolean mediaPlaying;
    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    boolean playing = false;
                    for (AudioPlaybackConfiguration config : configs) {
                        AudioAttributes attributes = config.getAudioAttributes();
                        int contentType = attributes == null
                                ? AudioAttributes.CONTENT_TYPE_UNKNOWN
                                : attributes.getContentType();
                        if (config.isActive()
                                && (contentType == AudioAttributes.CONTENT_TYPE_MUSIC
                                || contentType == AudioAttributes.CONTENT_TYPE_MOVIE)) {
                            playing = true;
                            break;
                        }
                    }
                    boolean changed = playing != mediaPlaying;
                    mediaPlaying = playing;
                    if (changed && smartRouting != null) {
                        smartRouting.onLocalMediaPlaybackChanged(playing);
                    }
                }
            };
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                bleScanner.start();
                refreshConnectedA2dp();
            } else if (state == BluetoothAdapter.STATE_OFF) {
                bleScanner.stop();
                controller.onBluetoothDisabled();
            }
        }
    };
    private final BroadcastReceiver a2dpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (BluetoothDevice.ACTION_UUID.equals(intent.getAction())
                    || BluetoothDevice.ACTION_NAME_CHANGED.equals(intent.getAction())) {
                if (device != null && device.isConnected()) {
                    controller.onA2dpConnectionChanged(device, true);
                }
                return;
            }
            if (!BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            controller.onA2dpConnectionChanged(device, state == BluetoothProfile.STATE_CONNECTED);
        }
    };
    private final Runnable bind = this::bindAudioService;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            audioService = IAirPodsService.Stub.asInterface(binder);
            refreshLocalPolicies();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            audioService = null;
            if (smartRouting != null) smartRouting.onAudioServiceDisconnected();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            audioService = null;
            if (smartRouting != null) smartRouting.onAudioServiceDisconnected();
            releaseBinding();
            handler.postDelayed(bind, REBIND_DELAY_MS);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            audioService = null;
            releaseBinding();
            handler.postDelayed(bind, REBIND_DELAY_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        controller = new AirPodsController(this);
        proximityKeyStore = new AirPodsProximityKeyStore(this);
        smartRouting = new SmartRoutingManager(this, proximityKeyStore,
                () -> bleScanner.restartForKeys());
        bleScanner = new AirPodsBleScanner(this, proximityKeyStore, state -> {
            nearbyState = state;
            smartRouting.onNearbyState(state);
            AirPodsMetadataService.schedule(this);
        });
        registerReceiver(bluetoothStateReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED);
        IntentFilter a2dpFilter = new IntentFilter();
        a2dpFilter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        a2dpFilter.addAction(BluetoothDevice.ACTION_UUID);
        a2dpFilter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        registerReceiver(a2dpReceiver, a2dpFilter, Context.RECEIVER_EXPORTED);
        audioManager = getSystemService(AudioManager.class);
        if (audioManager != null) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
        }
        bleScanner.start();
        refreshConnectedA2dp();
        bindAudioService();
    }

    static void requestPolicyRefresh() {
        HaotianAirPodsApplication application = instance;
        if (application != null) application.refreshLocalPolicies();
    }

    static AirPodsNearbyState getNearbyState() {
        HaotianAirPodsApplication application = instance;
        return application == null ? null : application.nearbyState;
    }

    static void onPhoneStateChanged(String state) {
        HaotianAirPodsApplication application = instance;
        if (application != null && application.smartRouting != null) {
            application.smartRouting.onPhoneStateChanged(state);
        }
    }

    private void bindAudioService() {
        handler.removeCallbacks(bind);
        if (bindingOrBound) return;
        try {
            bindingOrBound = bindService(new Intent().setComponent(CONTROL_SERVICE), connection,
                    Context.BIND_AUTO_CREATE);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not bind AirPods policy client", exception);
            bindingOrBound = false;
        }
        if (!bindingOrBound) handler.postDelayed(bind, REBIND_DELAY_MS);
    }

    private void refreshConnectedA2dp() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        try {
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        if (profile == BluetoothProfile.A2DP && proxy instanceof BluetoothA2dp) {
                            for (BluetoothDevice device : proxy.getConnectedDevices()) {
                                controller.onA2dpConnectionChanged(device, true);
                            }
                        }
                    } finally {
                        adapter.closeProfileProxy(profile, proxy);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.A2DP);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not query connected A2DP devices", exception);
        }
    }

    private void refreshLocalPolicies() {
        IAirPodsService service = audioService;
        if (service == null) {
            bindAudioService();
            return;
        }
        try {
            AirPodsState state = service.getAirPodsState();
            WearMediaPolicy.onState(this, state);
            ConversationAwarenessMediaPolicy.onState(this, state);
            smartRouting.onAirPodsState(service, state);
        } catch (RemoteException exception) {
            Log.w(TAG, "Could not refresh AirPods local policies", exception);
        }
    }

    @Override
    public void onTerminate() {
        ConversationAwarenessMediaPolicy.shutdown(this);
        if (bleScanner != null) bleScanner.stop();
        if (audioManager != null) audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(a2dpReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        releaseBinding();
        if (controller != null) controller.close();
        controller = null;
        instance = null;
        super.onTerminate();
    }

    private void releaseBinding() {
        if (!bindingOrBound) return;
        try {
            unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
        bindingOrBound = false;
    }

    AirPodsController getController() {
        if (controller == null) throw new IllegalStateException("AirPods controller unavailable");
        return controller;
    }
}
