// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class SonyControlService extends Service {
    private SonyController controller() {
        return ((HaotianSonyApplication) getApplication()).controller();
    }
    private final ISonyControlService.Stub binder = new ISonyControlService.Stub() {
        @Override public String getState() { return controller().getState(); }
        @Override public void registerCallback(ISonyStateCallback callback) { controller().register(callback); }
        @Override public void unregisterCallback(ISonyStateCallback callback) { controller().unregister(callback); }
        @Override public void selectDevice(String address) { controller().select(address); }
        @Override public void refresh(String address) { controller().refresh(address); }
        @Override public void setValue(String address, String key, int value, String target) {
            controller().setValue(address, key, value, target);
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
}
