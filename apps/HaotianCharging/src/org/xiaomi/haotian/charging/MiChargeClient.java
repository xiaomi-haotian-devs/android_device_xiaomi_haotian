/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.charging;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

final class MiChargeClient {
    private static final String DESCRIPTOR = "vendor.xiaomi.hardware.micharge.IMiCharge";
    private static final String SERVICE = DESCRIPTOR + "/default";

    private static final int GET_BATTERY_CHARGE_FULL = 3;
    private static final int GET_CYCLE_COUNT = 5;
    private static final int GET_BATTERY_SOH = 8;
    private static final int GET_CHARGING_POWER_MAX = 14;
    private static final int GET_WIRELESS_REVERSE_STATUS = 32;
    private static final int GET_WIRELESS_FW_STATUS = 31;
    private static final int IS_WIRELESS_FW_UPDATE_SUPPORTED = 38;
    private static final int SET_MI_CHARGE_PATH = 43;
    private static final int SET_UPDATE_WIRELESS_FW = 48;

    interface ParcelWriter {
        void write(Parcel data);
    }

    private IBinder binder() {
        return ServiceManager.checkService(SERVICE);
    }

    boolean isAvailable() {
        return binder() != null;
    }

    int getBatteryChargeFullMah() throws RemoteException {
        return callInt(GET_BATTERY_CHARGE_FULL, null) / 1000;
    }

    int getCycleCount() throws RemoteException {
        return callInt(GET_CYCLE_COUNT, null);
    }

    int getBatterySoh() throws RemoteException {
        return callInt(GET_BATTERY_SOH, null);
    }

    int getChargingPowerMax() throws RemoteException {
        return callInt(GET_CHARGING_POWER_MAX, null);
    }

    int getWirelessReverseStatus() throws RemoteException {
        return callInt(GET_WIRELESS_REVERSE_STATUS, null);
    }

    int getWirelessFirmwareStatus() throws RemoteException {
        return callInt(GET_WIRELESS_FW_STATUS, null);
    }

    boolean isWirelessFirmwareUpdateSupported() throws RemoteException {
        return callBoolean(IS_WIRELESS_FW_UPDATE_SUPPORTED);
    }

    boolean setBypassCharging(boolean enabled) throws RemoteException {
        return setMiChargePath("smart_chg", enabled ? "1025" : "1024") == 0;
    }

    boolean setWirelessQuietMode(boolean enabled) throws RemoteException {
        return setMiChargePath("smart_chg", enabled ? "0x81" : "0x80") == 0;
    }

    int setMiChargePath(String name, String value) throws RemoteException {
        return callInt(SET_MI_CHARGE_PATH, data -> {
            data.writeString(name);
            data.writeString(value);
        });
    }

    boolean requestWirelessFirmwareUpdate() throws RemoteException {
        return callInt(SET_UPDATE_WIRELESS_FW, data -> data.writeInt(98)) == 0;
    }

    private int callInt(int code, ParcelWriter writer) throws RemoteException {
        IBinder service = binder();
        if (service == null) {
            throw new RemoteException("MiCharge service is unavailable");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (writer != null) {
                writer.write(data);
            }
            if (!service.transact(code, data, reply, 0)) {
                throw new RemoteException("MiCharge rejected transaction " + code);
            }
            reply.readException();
            return reply.dataAvail() >= Integer.BYTES ? reply.readInt() : 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean callBoolean(int code) throws RemoteException {
        IBinder service = binder();
        if (service == null) {
            throw new RemoteException("MiCharge service is unavailable");
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(code, data, reply, 0)) {
                throw new RemoteException("MiCharge rejected transaction " + code);
            }
            reply.readException();
            return reply.readBoolean();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
