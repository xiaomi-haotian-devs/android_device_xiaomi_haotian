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
    private static final String DESCRIPTOR =
            "org.xiaomi.haotian.charging.IChargingControl";
    private static final int GET_ANTI_AGING = IBinder.FIRST_CALL_TRANSACTION;
    private static final int SET_ANTI_AGING = GET_ANTI_AGING + 1;
    private static final int GET_FAST_CHARGE = GET_ANTI_AGING + 2;
    private static final int SET_FAST_CHARGE = GET_ANTI_AGING + 3;
    private static final int GET_WIRELESS_DETAILS = GET_ANTI_AGING + 4;

    static final class WirelessDetails {
        final String firmwareVersion;
        final String transmitterUuid;
        final int adapterType;
        final int receiverVoltageMv;
        final int receiverCurrentMa;
        final int rectifierVoltageMv;
        final int dieTemperature;
        final int alignment;
        final int controlError;
        final int signalStrength;
        final int transmitterSpeed;
        final boolean fastCharge;
        final boolean carAdapter;

        WirelessDetails(String encoded) {
            String[] values = encoded == null ? new String[0]
                    : encoded.split("\\|", -1);
            firmwareVersion = stringAt(values, 0);
            transmitterUuid = stringAt(values, 1);
            adapterType = intAt(values, 2);
            receiverVoltageMv = intAt(values, 3);
            receiverCurrentMa = intAt(values, 4);
            rectifierVoltageMv = intAt(values, 5);
            dieTemperature = intAt(values, 6);
            alignment = intAt(values, 7);
            controlError = intAt(values, 8);
            signalStrength = intAt(values, 9);
            transmitterSpeed = intAt(values, 10);
            fastCharge = intAt(values, 11) != 0;
            carAdapter = intAt(values, 12) != 0;
        }

        private static String stringAt(String[] values, int index) {
            return index < values.length ? values[index] : "";
        }

        private static int intAt(String[] values, int index) {
            if (index >= values.length || values[index].isEmpty()) return 0;
            try {
                return Integer.decode(values[index]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

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

    WirelessDetails getWirelessDetails() throws RemoteException {
        IBinder service = ServiceManager.checkService(SERVICE);
        if (service == null) {
            throw new RemoteException("haotian charging controller is unavailable");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(GET_WIRELESS_DETAILS, data, reply, 0)) {
                throw new RemoteException("controller rejected wireless status request");
            }
            return new WirelessDetails(reply.readString());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int transact(int code, Boolean value) throws RemoteException {
        IBinder service = ServiceManager.checkService(SERVICE);
        if (service == null) {
            throw new RemoteException("haotian charging controller is unavailable");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
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
