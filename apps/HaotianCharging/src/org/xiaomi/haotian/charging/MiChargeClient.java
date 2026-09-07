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
    private static final int GET_WIRELESS_CHARGING_STATUS = 30;
    private static final int GET_WIRELESS_FW_STATUS = 31;
    private static final int IS_WIRELESS_FW_UPDATE_SUPPORTED = 38;
    private static final int SET_MI_CHARGE_PATH = 43;
    private static final int SET_UPDATE_WIRELESS_FW = 48;
    private static final int SET_WIRELESS_CHARGING_ENABLED = 49;
    private static final int SET_WLS_TX_SPEED = 50;

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
        return getStringInt(GET_BATTERY_CHARGE_FULL) / 1000;
    }

    int getCycleCount() throws RemoteException {
        return getStringInt(GET_CYCLE_COUNT);
    }

    int getBatterySoh() throws RemoteException {
        return getStringInt(GET_BATTERY_SOH);
    }

    int getChargingPowerMax() throws RemoteException {
        return getStringInt(GET_CHARGING_POWER_MAX);
    }

    boolean isWirelessChargingEnabled() throws RemoteException {
        String value = callReplyString(GET_WIRELESS_CHARGING_STATUS, null);
        if (value == null) {
            throw new RemoteException("MiCharge returned no wireless charging state");
        }
        switch (value.trim()) {
            case "0": return false;
            case "1": return true;
            default:
                throw new RemoteException(
                        "MiCharge returned an invalid wireless charging state: " + value);
        }
    }

    boolean setWirelessChargingEnabled(boolean enabled) throws RemoteException {
        return callReplyInt(SET_WIRELESS_CHARGING_ENABLED,
                data -> data.writeBoolean(enabled)) == 0;
    }

    int getWirelessFirmwareStatus() throws RemoteException {
        return getStringInt(GET_WIRELESS_FW_STATUS);
    }

    boolean isWirelessFirmwareUpdateSupported() throws RemoteException {
        return callReplyInt(IS_WIRELESS_FW_UPDATE_SUPPORTED, null) != 0;
    }

    boolean setBypassCharging(boolean enabled) throws RemoteException {
        return setMiChargePath("smart_chg", enabled ? "1025" : "1024");
    }

    boolean setWirelessQuietMode(boolean enabled) throws RemoteException {
        return setMiChargePath("smart_chg", enabled ? "0x81" : "0x80");
    }

    boolean setWirelessTransmitterSpeed(int speed) throws RemoteException {
        if (speed < 0 || speed > 10) {
            throw new IllegalArgumentException("Wireless transmitter speed must be 0..10");
        }
        return callSetString(SET_WLS_TX_SPEED, Integer.toString(speed));
    }

    private boolean setMiChargePath(String name, String value) throws RemoteException {
        return callReplyInt(SET_MI_CHARGE_PATH, data -> {
            data.writeString(name);
            data.writeString(value);
        }) == 0;
    }

    boolean requestWirelessFirmwareUpdate() throws RemoteException {
        // This is HyperOS' user-request command. The kernel compares the
        // installed wireless IC firmware with the image embedded in the
        // Nuvolta module and flashes that local image when necessary.
        return callSetString(SET_UPDATE_WIRELESS_FW, "98");
    }

    private int getStringInt(int code) throws RemoteException {
        String value = callReplyString(code, null);
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.decode(value.trim());
        } catch (NumberFormatException e) {
            throw new RemoteException("MiCharge returned an invalid integer for "
                    + code + ": " + value);
        }
    }

    private boolean callSetString(int code, String value) throws RemoteException {
        return callReplyInt(code, data -> data.writeString(value)) == 0;
    }

    private int callReplyInt(int code, ParcelWriter writer) throws RemoteException {
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
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private String callReplyString(int code, ParcelWriter writer) throws RemoteException {
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
            return reply.readString();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
