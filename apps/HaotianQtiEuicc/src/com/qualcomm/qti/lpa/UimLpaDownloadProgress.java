/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */
package com.qualcomm.qti.lpa;

import android.os.Parcel;
import android.os.Parcelable;

/** Wire-compatible view of Qualcomm's uimlpalibrary download indication. */
public final class UimLpaDownloadProgress implements Parcelable {
    public static final Creator<UimLpaDownloadProgress> CREATOR =
            new Creator<UimLpaDownloadProgress>() {
                @Override
                public UimLpaDownloadProgress createFromParcel(Parcel in) {
                    return new UimLpaDownloadProgress(in);
                }

                @Override
                public UimLpaDownloadProgress[] newArray(int size) {
                    return new UimLpaDownloadProgress[size];
                }
            };

    private int mStatus;
    private int mCause;
    private int mProgress;
    private int mProfilePolicyMask;
    private boolean mUserConsent;
    private String mProfileName;
    private int mUserConsentType;

    public UimLpaDownloadProgress() {}

    private UimLpaDownloadProgress(Parcel in) {
        mStatus = in.readInt();
        mCause = in.readInt();
        mProgress = in.readInt();
        mProfilePolicyMask = in.readInt();
        mUserConsent = in.readByte() != 0;
        mProfileName = in.readString();
        mUserConsentType = in.readInt();
    }

    public int getStatus() {
        return mStatus;
    }

    public int getCause() {
        return mCause;
    }

    public int getProgress() {
        return mProgress;
    }

    public int getProfilePolicyMask() {
        return mProfilePolicyMask;
    }

    public boolean getUserConsent() {
        return mUserConsent;
    }

    public String getProfileName() {
        return mProfileName;
    }

    public int getUserConsentType() {
        return mUserConsentType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mStatus);
        out.writeInt(mCause);
        out.writeInt(mProgress);
        out.writeInt(mProfilePolicyMask);
        out.writeByte(mUserConsent ? (byte) 1 : (byte) 0);
        if (mProfileName != null) {
            out.writeString(mProfileName);
        } else {
            // Match Qualcomm's parcelable exactly. readString() consumes this as
            // an absent/empty value while preserving the following integer offset.
            out.writeInt(0);
        }
        out.writeInt(mUserConsentType);
    }
}
