/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** AOSP Quick Settings entry that cycles through the accessory's available listening modes. */
public final class AirPodsNoiseControlTileService extends TileService {
    static final ComponentName COMPONENT = new ComponentName(
            "org.xiaomi.haotian.airpods",
            "org.xiaomi.haotian.airpods.AirPodsNoiseControlTileService");
    private static final String ACTION_STATE_CHANGED =
            AirPodsController.ACTION_STATE_CHANGED;
    private static final ComponentName AUDIO_SERVICE = new ComponentName(
            "org.xiaomi.haotian.airpods",
            "org.xiaomi.haotian.airpods.AirPodsControlService");

    private IAirPodsService audioService;
    private AirPodsState state = new AirPodsState();
    private boolean bound;
    private boolean receiverRegistered;
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            audioService = IAirPodsService.Stub.asInterface(binder);
            refreshState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            audioService = null;
            state = new AirPodsState();
            updateTile();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (!receiverRegistered) {
            try {
                registerReceiver(stateReceiver, new IntentFilter(ACTION_STATE_CHANGED),
                        Context.RECEIVER_NOT_EXPORTED);
                receiverRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        if (!bound) {
            try {
                bound = bindService(new Intent().setComponent(AUDIO_SERVICE), connection,
                        Context.BIND_AUTO_CREATE);
            } catch (RuntimeException ignored) {
                bound = false;
            }
        }
        if (bound) {
            refreshState();
        } else {
            state = new AirPodsState();
            updateTile();
        }
    }

    @Override
    public void onStopListening() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        if (bound) {
            try {
                unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
        bound = false;
        audioService = null;
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        IAirPodsService service = audioService;
        AirPodsState current = state;
        if (service == null || current == null || !current.connected
                || current.deviceAddress.isEmpty()) {
            return;
        }
        int next = nextMode(current);
        try {
            if (service.setAirPodsNoiseControlMode(current.deviceAddress, next)) {
                AirPodsState optimistic = new AirPodsState(current);
                optimistic.noiseControlMode = next;
                state = optimistic;
                updateTile();
            }
        } catch (RemoteException ignored) {
        }
    }

    private void refreshState() {
        IAirPodsService service = audioService;
        if (service == null) {
            updateTile();
            return;
        }
        try {
            AirPodsState updated = service.getAirPodsState();
            state = updated == null ? new AirPodsState() : updated;
        } catch (RemoteException exception) {
            state = new AirPodsState();
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        AirPodsState current = state;
        boolean connected = current != null && current.connected;
        tile.setState(connected ? Tile.STATE_ACTIVE : Tile.STATE_UNAVAILABLE);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_airpods));
        if (connected) {
            tile.setLabel(getText(modeLabel(current.noiseControlMode)));
            CharSequence device = current.displayName.isEmpty()
                    ? getText(R.string.app_name) : current.displayName;
            tile.setSubtitle(device);
        } else {
            tile.setLabel(getText(R.string.noise_control));
            tile.setSubtitle(getText(R.string.disconnected));
        }
        tile.updateTile();
    }

    private static int nextMode(AirPodsState state) {
        boolean allowOff = enabled(state.getControlValue(0x34));
        int[] modes = allowOff
                ? new int[] {AirPodsState.NOISE_OFF, AirPodsState.NOISE_TRANSPARENCY,
                        AirPodsState.NOISE_ADAPTIVE, AirPodsState.NOISE_CANCELLATION}
                : new int[] {AirPodsState.NOISE_TRANSPARENCY,
                        AirPodsState.NOISE_ADAPTIVE, AirPodsState.NOISE_CANCELLATION};
        for (int index = 0; index < modes.length; index++) {
            if (modes[index] == state.noiseControlMode) {
                return modes[(index + 1) % modes.length];
            }
        }
        return modes[0];
    }

    private static int modeLabel(int mode) {
        switch (mode) {
            case AirPodsState.NOISE_OFF: return R.string.noise_off;
            case AirPodsState.NOISE_CANCELLATION: return R.string.noise_cancellation;
            case AirPodsState.NOISE_TRANSPARENCY: return R.string.transparency;
            case AirPodsState.NOISE_ADAPTIVE: return R.string.adaptive;
            default: return R.string.noise_control;
        }
    }

    private static boolean enabled(byte[] value) {
        return value != null && value.length > 0 && value[0] == 1;
    }
}
