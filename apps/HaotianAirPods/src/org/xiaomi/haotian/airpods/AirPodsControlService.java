/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Same-package Binder facade; all Bluetooth ownership remains in AirPodsController. */
public final class AirPodsControlService extends Service {
    private final IAirPodsService.Stub binder = new IAirPodsService.Stub() {
        private AirPodsController controller() {
            return ((HaotianAirPodsApplication) getApplication()).getController();
        }

        @Override
        public AirPodsState getAirPodsState() {
            return controller().getState();
        }

        @Override
        public boolean setAirPodsNoiseControlMode(String address, int mode) {
            return controller().setNoiseControlMode(address, mode);
        }

        @Override
        public boolean setAirPodsControlValue(String address, int identifier, byte[] value) {
            return controller().setControlValue(address, identifier, value);
        }

        @Override
        public boolean setAirPodsLongPressAction(String address, boolean left, int action) {
            return controller().setLongPressAction(address, left, action);
        }

        @Override
        public boolean renameAirPods(String address, String name) {
            return controller().rename(address, name);
        }

        @Override
        public boolean refreshAirPodsAttValue(String address, int handle) {
            return controller().refreshAttValue(address, handle);
        }

        @Override
        public boolean setAirPodsAttValue(String address, int handle, byte[] value) {
            return controller().setAttValue(address, handle, value);
        }

        @Override
        public boolean sendAirPodsAacpMessage(String address, int opcode, byte[] body) {
            return controller().sendAacpMessage(address, opcode, body);
        }

        @Override
        public boolean setAirPodsControlConnectionEnabled(String address, boolean enabled) {
            return controller().setControlConnectionEnabled(address, enabled);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
