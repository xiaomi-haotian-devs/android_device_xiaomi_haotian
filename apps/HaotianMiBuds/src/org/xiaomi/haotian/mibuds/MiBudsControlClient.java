/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

/** Activity-scoped client; the single-user service remains the sole transport owner. */
final class MiBudsControlClient implements ServiceConnection {
    interface Listener {
        void onState(String stateJson);
    }

    private static final ComponentName SERVICE = new ComponentName(
            "org.xiaomi.haotian.mibuds",
            "org.xiaomi.haotian.mibuds.MiBudsControlService");

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Runnable rebind = this::bindIfNeeded;
    private IMiBudsControlService service;
    private boolean started;
    private boolean bound;

    private final IMiBudsStateCallback callback = new IMiBudsStateCallback.Stub() {
        @Override
        public void onStateChanged(String stateJson) {
            main.post(() -> listener.onState(stateJson));
        }
    };

    MiBudsControlClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        started = true;
        main.removeCallbacks(rebind);
        bindIfNeeded();
    }

    void stop() {
        started = false;
        main.removeCallbacks(rebind);
        IMiBudsControlService current = service;
        service = null;
        if (current != null) {
            try {
                current.unregisterCallback(callback);
            } catch (RemoteException ignored) {
            }
        }
        if (bound) {
            context.unbindService(this);
            bound = false;
        }
    }

    private void bindIfNeeded() {
        if (!started || bound) return;
        bound = context.bindService(new Intent().setComponent(SERVICE), this,
                Context.BIND_AUTO_CREATE);
    }

    void refresh() {
        IMiBudsControlService current = service;
        if (current == null) return;
        try {
            current.refresh();
        } catch (RemoteException ignored) {
        }
    }

    void setLocalSpatialEnabled(boolean enabled) {
        IMiBudsControlService current = service;
        if (current == null) return;
        try {
            current.setLocalSpatialEnabled(enabled);
        } catch (RemoteException ignored) {
        }
    }

    void setLocalHeadTrackingEnabled(boolean enabled) {
        IMiBudsControlService current = service;
        if (current == null) return;
        try {
            current.setLocalHeadTrackingEnabled(enabled);
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!started) return;
        IMiBudsControlService connected = IMiBudsControlService.Stub.asInterface(binder);
        if (connected == null) return;
        service = connected;
        try {
            connected.registerCallback(callback);
            listener.onState(connected.getState());
        } catch (RemoteException exception) {
            service = null;
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    @Override
    public void onBindingDied(ComponentName name) {
        service = null;
        if (bound) {
            context.unbindService(this);
            bound = false;
        }
        if (started) main.postDelayed(rebind, 1_000);
    }

    @Override
    public void onNullBinding(ComponentName name) {
        service = null;
        if (bound) {
            context.unbindService(this);
            bound = false;
        }
    }
}
