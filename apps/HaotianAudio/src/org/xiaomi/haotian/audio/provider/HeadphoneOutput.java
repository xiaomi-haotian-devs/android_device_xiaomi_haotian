/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio.provider;

import android.os.Parcel;
import android.os.Parcelable;

/** Route identity passed to a headphone-specific companion service. */
public final class HeadphoneOutput implements Parcelable {
    public final String deviceKey;
    public final String displayName;
    public final int deviceType;
    public final String address;

    public HeadphoneOutput(String deviceKey, String displayName, int deviceType, String address) {
        this.deviceKey = nonNull(deviceKey);
        this.displayName = nonNull(displayName);
        this.deviceType = deviceType;
        this.address = nonNull(address);
    }

    private HeadphoneOutput(Parcel in) {
        deviceKey = nonNull(in.readString());
        displayName = nonNull(in.readString());
        deviceType = in.readInt();
        address = nonNull(in.readString());
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(deviceKey);
        out.writeString(displayName);
        out.writeInt(deviceType);
        out.writeString(address);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<HeadphoneOutput> CREATOR = new Creator<>() {
        @Override
        public HeadphoneOutput createFromParcel(Parcel in) {
            return new HeadphoneOutput(in);
        }

        @Override
        public HeadphoneOutput[] newArray(int size) {
            return new HeadphoneOutput[size];
        }
    };

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
