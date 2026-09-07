// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import java.util.function.Consumer;

/** Lifecycle-scoped Binder client, also shared by the optional QS tile. */
final class SonyControlClient {
    private final Context context;
    private final Consumer<SonyState> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean bound;
    private ISonyControlService service;
    private ISonyStateCallback callback;
    private int generation;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!started) return;
            service = ISonyControlService.Stub.asInterface(binder);
            final int epoch = ++generation;
            callback = new ISonyStateCallback.Stub() {
                @Override public void onStateChanged(String state) {
                    main.post(() -> {
                        if (started && service != null && generation == epoch)
                            listener.accept(new SonyState(state));
                    });
                }
            };
            try { service.registerCallback(callback); }
            catch (RemoteException e) { disconnected(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { disconnected(); }
        @Override public void onBindingDied(ComponentName name) {
            unbind(); disconnected();
            if (started) main.postDelayed(SonyControlClient.this::bind, 1000);
        }
        @Override public void onNullBinding(ComponentName name) { onBindingDied(name); }
    };
    SonyControlClient(Context context, Consumer<SonyState> listener) {
        this.context = context; this.listener = listener;
    }
    void start() { started = true; bind(); }
    void stop() {
        generation++;
        started = false; main.removeCallbacksAndMessages(null);
        if (service != null) try { service.unregisterCallback(callback); } catch (RemoteException ignored) {}
        unbind(); service = null;
    }
    private void bind() {
        if (!started || bound) return;
        try {
            bound = context.bindServiceAsUser(new Intent(context, SonyControlService.class),
                    connection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
        } catch (RuntimeException e) { bound = false; }
        if (!bound) {
            disconnected();
            main.postDelayed(this::bind, 2000);
        }
    }
    private void unbind() {
        if (bound) try { context.unbindService(connection); } catch (IllegalArgumentException ignored) {}
        bound = false;
    }
    private void disconnected() {
        generation++;
        service = null;
        if (started) listener.accept(new SonyState("{}"));
    }
    void select(String address) {
        if (service != null) try { service.selectDevice(address); } catch (RemoteException e) { disconnected(); }
    }
    void refresh(String address) {
        if (service != null) try { service.refresh(address); } catch (RemoteException e) { disconnected(); }
    }
    void set(String address, String key, int value, String target) {
        if (service != null) try { service.setValue(address, key, value, target); }
        catch (RemoteException e) { disconnected(); }
    }
}
