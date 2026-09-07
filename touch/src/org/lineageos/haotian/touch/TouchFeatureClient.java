/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.haotian.touch;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

final class TouchFeatureClient {
    private static final String TAG = "HaotianTouchFeature";

    private static final String TOUCH_FEATURE_SERVICE =
            "vendor.xiaomi.hw.touchfeature.ITouchFeature/default";
    private static final String TOUCH_FEATURE_DESCRIPTOR =
            "vendor.xiaomi.hw.touchfeature.ITouchFeature";

    private static final int TOUCH_ID_DEFAULT = 0;
    private static final int MODE_EDGE_SUPPRESSION = 15;
    private static final int TRANSACTION_SET_MODE_LONG_VALUE = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int TRANSACTION_SET_MODE_VALUE = IBinder.FIRST_CALL_TRANSACTION + 8;

    boolean setModeValue(int mode, int value) {
        IBinder binder = ServiceManager.checkService(TOUCH_FEATURE_SERVICE);
        if (binder == null) {
            Log.w(TAG, "TouchFeature service is not available");
            return false;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOUCH_FEATURE_DESCRIPTOR);
            data.writeInt(TOUCH_ID_DEFAULT);
            data.writeInt(mode);
            data.writeInt(value);

            if (!binder.transact(TRANSACTION_SET_MODE_VALUE, data, reply, 0)) {
                Log.e(TAG, "setModeValue transact failed for mode " + mode);
                return false;
            }

            reply.readException();
            int ret = reply.readInt();
            if (ret != 0) {
                Log.w(TAG, "setModeValue returned " + ret + " for mode " + mode);
                return false;
            }
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Failed to set mode " + mode + " to " + value, e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    boolean setEdgeSuppression(int[] values) {
        IBinder binder = ServiceManager.checkService(TOUCH_FEATURE_SERVICE);
        if (binder == null) {
            Log.w(TAG, "TouchFeature service is not available");
            return false;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOUCH_FEATURE_DESCRIPTOR);
            data.writeInt(TOUCH_ID_DEFAULT);
            data.writeInt(MODE_EDGE_SUPPRESSION);
            data.writeInt(values.length);
            data.writeIntArray(values);

            if (!binder.transact(TRANSACTION_SET_MODE_LONG_VALUE, data, reply, 0)) {
                Log.e(TAG, "setModeLongValue transact failed");
                return false;
            }

            reply.readException();
            int ret = reply.readInt();
            if (ret != 0) {
                Log.w(TAG, "setModeLongValue returned " + ret);
                return false;
            }
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Failed to apply edge suppression", e);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
