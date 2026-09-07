/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */
package com.qualcomm.qti.lpa;

import android.os.Parcel;
import android.os.Parcelable;

/** Wire-compatible view of Qualcomm's uimlpalibrary profile parcelable. */
public final class UimLpaProfile implements Parcelable {
    public static final Creator<UimLpaProfile> CREATOR = new Creator<UimLpaProfile>() {
        @Override
        public UimLpaProfile createFromParcel(Parcel in) {
            return new UimLpaProfile(in);
        }

        @Override
        public UimLpaProfile[] newArray(int size) {
            return new UimLpaProfile[size];
        }
    };

    private String mProfileName;
    private String mNickname;
    private boolean mProfileState;
    private byte[] mIccid;
    private String mSpnName;
    private int mIconType;
    private byte[] mIcon;
    private int mProfileClass;
    private int mProfilePolicyMask;

    public UimLpaProfile() {}

    private UimLpaProfile(Parcel in) {
        if (in.readInt() != 0) {
            mProfileName = in.readString();
        }
        if (in.readInt() != 0) {
            mNickname = in.readString();
        }
        mProfileState = in.readInt() != 0;

        int iccidLength = in.readInt();
        if (iccidLength > 0) {
            mIccid = new byte[iccidLength];
            in.readByteArray(mIccid);
        }

        if (in.readInt() != 0) {
            mSpnName = in.readString();
        }
        mIconType = in.readInt();

        int iconLength = in.readInt();
        if (iconLength > 0) {
            mIcon = new byte[iconLength];
            in.readByteArray(mIcon);
        }
        mProfileClass = in.readInt();
        mProfilePolicyMask = in.readInt();
    }

    public String getProfileName() {
        return mProfileName;
    }

    public String getNickname() {
        return mNickname;
    }

    public boolean getProfileState() {
        return mProfileState;
    }

    public byte[] getIccid() {
        return mIccid == null ? null : mIccid.clone();
    }

    public String getSpnName() {
        return mSpnName;
    }

    public int getIconType() {
        return mIconType;
    }

    public byte[] getIcon() {
        return mIcon == null ? null : mIcon.clone();
    }

    public int getProfileClass() {
        return mProfileClass;
    }

    public int getProfilePolicyMask() {
        return mProfilePolicyMask;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (mProfileName != null) {
            out.writeInt(1);
            out.writeString(mProfileName);
        } else {
            out.writeInt(0);
        }
        if (mNickname != null) {
            out.writeInt(1);
            out.writeString(mNickname);
        } else {
            out.writeInt(0);
        }
        out.writeInt(mProfileState ? 1 : 0);
        if (mIccid != null) {
            out.writeInt(mIccid.length);
            out.writeByteArray(mIccid);
        } else {
            out.writeInt(0);
        }
        if (mSpnName != null) {
            out.writeInt(1);
            out.writeString(mSpnName);
        } else {
            out.writeInt(0);
        }
        out.writeInt(mIconType);
        if (mIcon != null) {
            out.writeInt(mIcon.length);
            out.writeByteArray(mIcon);
        } else {
            out.writeInt(0);
        }
        out.writeInt(mProfileClass);
        out.writeInt(mProfilePolicyMask);
    }
}
