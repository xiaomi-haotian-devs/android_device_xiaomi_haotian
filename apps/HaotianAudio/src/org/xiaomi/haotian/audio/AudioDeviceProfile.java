/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/** Complete desired audio state for one stable output device. */
public final class AudioDeviceProfile implements Parcelable {
    public static final int SCHEMA_VERSION = 1;
    public static final String BUILTIN_SPEAKER_KEY = "speaker:builtin";
    public static final String IMPLEMENTATION_DOLBY = "dolby";
    public static final String IMPLEMENTATION_MISOUND = "misound";
    public static final String IMPLEMENTATION_NONE = "none";
    public static final int HEAD_TRACKING_DISABLED = 0;
    public static final int HEAD_TRACKING_RELATIVE_DEVICE = 1;
    public static final int HEAD_TRACKING_RELATIVE_WORLD = 2;
    public static final int PERSONAL_AUDIO_BAND_COUNT = 6;
    public static final int EAR_SCAN_COEFFICIENT_COUNT = 1024;

    public int schemaVersion = SCHEMA_VERSION;
    public long revision;
    public String deviceKey = BUILTIN_SPEAKER_KEY;
    public String displayName = "Built-in speaker";
    public String implementation = IMPLEMENTATION_DOLBY;
    public boolean spatialEnabled;
    public int headTrackingMode = HEAD_TRACKING_DISABLED;

    public int dolbyProfile;
    public String dolbyEqPreset = "custom";
    public float[] dolbyBands = new float[10];

    public int miSoundProfile = 4;
    public boolean miSoundSurround;
    public String miSoundEqPreset = "custom";
    public float[] miSoundBands = new float[7];

    public String soundIdProfileId = "";
    public boolean soundIdEnabled;
    public float[] soundIdGains = new float[0];
    public boolean hearingProfileEnabled;
    public float[] hearingLeftGains = new float[0];
    public float[] hearingRightGains = new float[0];
    public boolean earScanEnabled;
    public float[] earScanFilterValues = new float[0];
    public int headsetModelId;
    public String headsetModelName = "";

    public AudioDeviceProfile() {
    }

    public AudioDeviceProfile(AudioDeviceProfile other) {
        schemaVersion = other.schemaVersion;
        revision = other.revision;
        deviceKey = other.deviceKey;
        displayName = other.displayName;
        implementation = other.implementation;
        spatialEnabled = other.spatialEnabled;
        headTrackingMode = other.headTrackingMode;
        dolbyProfile = other.dolbyProfile;
        dolbyEqPreset = other.dolbyEqPreset;
        dolbyBands = copy(other.dolbyBands);
        miSoundProfile = other.miSoundProfile;
        miSoundSurround = other.miSoundSurround;
        miSoundEqPreset = other.miSoundEqPreset;
        miSoundBands = copy(other.miSoundBands);
        soundIdProfileId = other.soundIdProfileId;
        soundIdEnabled = other.soundIdEnabled;
        soundIdGains = copy(other.soundIdGains);
        hearingProfileEnabled = other.hearingProfileEnabled;
        hearingLeftGains = copy(other.hearingLeftGains);
        hearingRightGains = copy(other.hearingRightGains);
        earScanEnabled = other.earScanEnabled;
        earScanFilterValues = copy(other.earScanFilterValues);
        headsetModelId = other.headsetModelId;
        headsetModelName = other.headsetModelName;
    }

    private AudioDeviceProfile(Parcel in) {
        schemaVersion = in.readInt();
        revision = in.readLong();
        deviceKey = in.readString();
        displayName = in.readString();
        implementation = in.readString();
        spatialEnabled = in.readInt() != 0;
        headTrackingMode = in.readInt();
        dolbyProfile = in.readInt();
        dolbyEqPreset = in.readString();
        dolbyBands = nonNull(in.createFloatArray());
        miSoundProfile = in.readInt();
        miSoundSurround = in.readInt() != 0;
        miSoundEqPreset = in.readString();
        miSoundBands = nonNull(in.createFloatArray());
        soundIdProfileId = in.readString();
        soundIdEnabled = in.readInt() != 0;
        soundIdGains = nonNull(in.createFloatArray());
        hearingProfileEnabled = in.readInt() != 0;
        hearingLeftGains = nonNull(in.createFloatArray());
        hearingRightGains = nonNull(in.createFloatArray());
        earScanEnabled = in.readInt() != 0;
        earScanFilterValues = nonNull(in.createFloatArray());
        headsetModelId = in.readInt();
        headsetModelName = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeLong(revision);
        dest.writeString(deviceKey);
        dest.writeString(displayName);
        dest.writeString(implementation);
        dest.writeInt(spatialEnabled ? 1 : 0);
        dest.writeInt(headTrackingMode);
        dest.writeInt(dolbyProfile);
        dest.writeString(dolbyEqPreset);
        dest.writeFloatArray(dolbyBands);
        dest.writeInt(miSoundProfile);
        dest.writeInt(miSoundSurround ? 1 : 0);
        dest.writeString(miSoundEqPreset);
        dest.writeFloatArray(miSoundBands);
        dest.writeString(soundIdProfileId);
        dest.writeInt(soundIdEnabled ? 1 : 0);
        dest.writeFloatArray(soundIdGains);
        dest.writeInt(hearingProfileEnabled ? 1 : 0);
        dest.writeFloatArray(hearingLeftGains);
        dest.writeFloatArray(hearingRightGains);
        dest.writeInt(earScanEnabled ? 1 : 0);
        dest.writeFloatArray(earScanFilterValues);
        dest.writeInt(headsetModelId);
        dest.writeString(headsetModelName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AudioDeviceProfile> CREATOR =
            new Creator<AudioDeviceProfile>() {
                @Override
                public AudioDeviceProfile createFromParcel(Parcel in) {
                    return new AudioDeviceProfile(in);
                }

                @Override
                public AudioDeviceProfile[] newArray(int size) {
                    return new AudioDeviceProfile[size];
                }
            };

    static float[] copy(float[] values) {
        return values == null ? new float[0] : Arrays.copyOf(values, values.length);
    }

    static float[] sized(float[] values, int size) {
        return values != null && values.length == size
                ? Arrays.copyOf(values, size) : new float[size];
    }

    private static float[] nonNull(float[] values) {
        return values == null ? new float[0] : values;
    }
}
