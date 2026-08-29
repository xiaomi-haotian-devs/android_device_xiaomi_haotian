/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.finddevice.v2;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Minimal, local-only compatibility endpoint for Xiaomi Account.
 *
 * HyperOS' account settings queries this explicit component only to decide
 * whether the Find Device row is enabled. Shipping the real Find Device client
 * would also import its cloud control, location and device-administration
 * stack. This endpoint implements the stable Binder wire format used by
 * FindDeviceStatusManager and reports the unsupported feature as disabled.
 */
public final class FindDeviceStatusManagerService extends Service {
    private static final String SERVICE_DESCRIPTOR =
            "miui.cloud.finddevice.IFindDeviceStatusManagerAsync";
    private static final String INFO_CALLBACK_DESCRIPTOR =
            "miui.cloud.finddevice.IFindDeviceInfoCallback";

    private static final int TRANSACTION_GET_FIND_DEVICE_INFO = 1;

    private final Binder mBinder = new Binder() {
        {
            attachInterface(null, SERVICE_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(SERVICE_DESCRIPTOR);
                return true;
            }

            if (code >= 1 && code <= 8) {
                data.enforceInterface(SERVICE_DESCRIPTOR);

                if (code == TRANSACTION_GET_FIND_DEVICE_INFO) {
                    IBinder callback = data.readStrongBinder();
                    data.enforceNoDataAvail();
                    sendDisabledInfo(callback);
                    reply.writeNoException();
                    // Xiaomi's async interface returns false when the request
                    // was accepted and true when the service is unavailable.
                    reply.writeInt(0);
                    return true;
                }

                // Mutating and server-backed operations are intentionally not
                // implemented. Report "out of service" without blocking a
                // caller that would otherwise wait for an async callback.
                reply.writeNoException();
                reply.writeInt(1);
                return true;
            }

            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private static void sendDisabledInfo(IBinder callback) throws RemoteException {
        if (callback == null) {
            return;
        }

        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(INFO_CALLBACK_DESCRIPTOR);
            data.writeInt(1); // Non-null FindDeviceInfo parcelable.
            data.writeInt(0); // isOpen
            data.writeInt(0); // isLocked
            data.writeString(null); // sessionUserId
            data.writeString(null); // displayId
            data.writeString(null); // fid
            data.writeString(null); // email
            data.writeString(null); // phone
            data.writeString(null); // findToken
            callback.transact(1, data, null, IBinder.FLAG_ONEWAY);
        } finally {
            data.recycle();
        }
    }
}
