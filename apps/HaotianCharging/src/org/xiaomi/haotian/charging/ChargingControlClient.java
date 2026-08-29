/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.charging;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

final class ChargingControlClient {
    private static final String SERVICE = "haotian_charging";
    private static final int GET_ANTI_AGING = IBinder.FIRST_CALL_TRANSACTION;
    private static final int SET_ANTI_AGING = GET_ANTI_AGING + 1;
    private static final int GET_FAST_CHARGE = GET_ANTI_AGING + 2;
    private static final int SET_FAST_CHARGE = GET_ANTI_AGING + 3;

    boolean isAvailable() {
        return ServiceManager.checkService(SERVICE) != null;
    }

    boolean isAntiAgingEnabled() throws RemoteException {
        return transact(GET_ANTI_AGING, null) != 0;
    }

    boolean setAntiAgingEnabled(boolean enabled) throws RemoteException {
        return transact(SET_ANTI_AGING, enabled) == 0;
    }

    boolean isFastChargeEnabled() throws RemoteException {
        return transact(GET_FAST_CHARGE, null) != 0;
    }

    boolean setFastChargeEnabled(boolean enabled) throws RemoteException {
        return transact(SET_FAST_CHARGE, enabled) == 0;
    }

    private int transact(int code, Boolean value) throws RemoteException {
        IBinder service = ServiceManager.checkService(SERVICE);
        if (service == null) {
            throw new RemoteException("haotian charging controller is unavailable");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (value != null) {
                data.writeInt(value ? 1 : 0);
            }
            if (!service.transact(code, data, reply, 0)) {
                throw new RemoteException("controller rejected transaction " + code);
            }
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
