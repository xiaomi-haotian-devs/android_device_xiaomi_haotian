/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Signature-protected, low-rate settings facade over the process-wide controller. */
public final class MiBudsControlService extends Service {
    private MiBudsController controller() {
        return ((HaotianMiBudsApplication) getApplication()).controller();
    }

    private final IMiBudsControlService.Stub binder = new IMiBudsControlService.Stub() {
        @Override
        public String getState() {
            return controller().getPublishedState();
        }

        @Override
        public void registerCallback(IMiBudsStateCallback callback) {
            controller().registerCallback(callback);
        }

        @Override
        public void unregisterCallback(IMiBudsStateCallback callback) {
            controller().unregisterCallback(callback);
        }

        @Override
        public void refresh() {
            controller().refresh();
        }

        @Override
        public void setLocalSpatialEnabled(boolean enabled) {
            controller().setLocalSpatialEnabled(enabled);
        }

        @Override
        public void setLocalHeadTrackingEnabled(boolean enabled) {
            controller().setLocalHeadTrackingEnabled(enabled);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
