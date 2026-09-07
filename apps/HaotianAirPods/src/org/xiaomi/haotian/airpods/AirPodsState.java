/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.airpods;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/** Read-only snapshot of the active Apple AACP accessory session. */
public final class AirPodsState implements Parcelable {
    public static final int WEAR_UNKNOWN = 0;
    public static final int WEAR_IN_EAR = 1;
    public static final int WEAR_OUT_OF_EAR = 2;
    public static final int WEAR_IN_CASE = 3;
    public static final int WEAR_DISCONNECTED = 4;

    public static final int NOISE_UNKNOWN = 0;
    public static final int NOISE_OFF = 1;
    public static final int NOISE_CANCELLATION = 2;
    public static final int NOISE_TRANSPARENCY = 3;
    public static final int NOISE_ADAPTIVE = 4;

    public static final int LONG_PRESS_NOISE_CONTROL = 1;
    public static final int LONG_PRESS_VOICE_ASSISTANT = 5;

    public static final int BATTERY_UNKNOWN = -1;
    public static final int BATTERY_STATUS_UNKNOWN = 0;
    public static final int BATTERY_STATUS_CHARGING = 1;
    public static final int BATTERY_STATUS_NOT_CHARGING = 2;
    public static final int BATTERY_STATUS_DISCONNECTED = 4;
    public static final int BATTERY_STATUS_OPTIMIZED_CHARGING = 5;

    public boolean connected;
    public String deviceKey = "";
    public String deviceAddress = "";
    public String displayName = "";
    public String modelNumber = "";
    public String manufacturer = "";
    public String serialNumber = "";
    public String leftSerialNumber = "";
    public String rightSerialNumber = "";
    public String version1 = "";
    public String version2 = "";
    public String version3 = "";
    public String hardwareRevision = "";
    public int leftBattery = BATTERY_UNKNOWN;
    public int rightBattery = BATTERY_UNKNOWN;
    public int caseBattery = BATTERY_UNKNOWN;
    public int leftBatteryStatus = BATTERY_STATUS_UNKNOWN;
    public int rightBatteryStatus = BATTERY_STATUS_UNKNOWN;
    public int caseBatteryStatus = BATTERY_STATUS_UNKNOWN;
    public int leftWear = WEAR_UNKNOWN;
    public int rightWear = WEAR_UNKNOWN;
    public int noiseControlMode = NOISE_UNKNOWN;
    /** Companion-owned actions; AirPods only report intercepted stem events. */
    public int leftLongPressAction = LONG_PRESS_NOISE_CONTROL;
    public int rightLongPressAction = LONG_PRESS_NOISE_CONTROL;
    /** Raw AACP control values reported by the accessory, keyed by matching array index. */
    public int[] controlIdentifiers = new int[0];
    public byte[][] controlValues = new byte[0][];
    /** Opaque values read from the accessory's secondary Classic ATT channel. */
    public boolean attConnected;
    public int[] attHandles = new int[0];
    public byte[][] attValues = new byte[0][];
    /** Opaque bodies for low-rate application AACP messages retained by the channel owner. */
    public int[] aacpMessageOpcodes = new int[0];
    public byte[][] aacpMessageValues = new byte[0][];
    /** Per-opcode counters identify repeated opaque messages with byte-identical bodies. */
    public long[] aacpMessageSequences = new long[0];
    public long updatedElapsedRealtimeNanos;

    public AirPodsState() {
    }

    public AirPodsState(AirPodsState other) {
        connected = other.connected;
        deviceKey = nonNull(other.deviceKey);
        deviceAddress = nonNull(other.deviceAddress);
        displayName = nonNull(other.displayName);
        modelNumber = nonNull(other.modelNumber);
        manufacturer = nonNull(other.manufacturer);
        serialNumber = nonNull(other.serialNumber);
        leftSerialNumber = nonNull(other.leftSerialNumber);
        rightSerialNumber = nonNull(other.rightSerialNumber);
        version1 = nonNull(other.version1);
        version2 = nonNull(other.version2);
        version3 = nonNull(other.version3);
        hardwareRevision = nonNull(other.hardwareRevision);
        leftBattery = other.leftBattery;
        rightBattery = other.rightBattery;
        caseBattery = other.caseBattery;
        leftBatteryStatus = other.leftBatteryStatus;
        rightBatteryStatus = other.rightBatteryStatus;
        caseBatteryStatus = other.caseBatteryStatus;
        leftWear = other.leftWear;
        rightWear = other.rightWear;
        noiseControlMode = other.noiseControlMode;
        leftLongPressAction = other.leftLongPressAction;
        rightLongPressAction = other.rightLongPressAction;
        controlIdentifiers = Arrays.copyOf(other.controlIdentifiers,
                other.controlIdentifiers.length);
        controlValues = copyValues(other.controlValues);
        attConnected = other.attConnected;
        attHandles = Arrays.copyOf(other.attHandles, other.attHandles.length);
        attValues = copyValues(other.attValues);
        aacpMessageOpcodes = Arrays.copyOf(other.aacpMessageOpcodes,
                other.aacpMessageOpcodes.length);
        aacpMessageValues = copyValues(other.aacpMessageValues);
        aacpMessageSequences = Arrays.copyOf(other.aacpMessageSequences,
                other.aacpMessageSequences.length);
        updatedElapsedRealtimeNanos = other.updatedElapsedRealtimeNanos;
    }

    private AirPodsState(Parcel in) {
        connected = in.readInt() != 0;
        deviceKey = nonNull(in.readString());
        deviceAddress = nonNull(in.readString());
        displayName = nonNull(in.readString());
        modelNumber = nonNull(in.readString());
        manufacturer = nonNull(in.readString());
        serialNumber = nonNull(in.readString());
        leftSerialNumber = nonNull(in.readString());
        rightSerialNumber = nonNull(in.readString());
        version1 = nonNull(in.readString());
        version2 = nonNull(in.readString());
        version3 = nonNull(in.readString());
        hardwareRevision = nonNull(in.readString());
        leftBattery = in.readInt();
        rightBattery = in.readInt();
        caseBattery = in.readInt();
        leftBatteryStatus = in.readInt();
        rightBatteryStatus = in.readInt();
        caseBatteryStatus = in.readInt();
        leftWear = in.readInt();
        rightWear = in.readInt();
        noiseControlMode = in.readInt();
        leftLongPressAction = in.readInt();
        rightLongPressAction = in.readInt();
        int controlCount = in.readInt();
        if (controlCount < 0 || controlCount > 256) {
            throw new BadParcelableException("Invalid AACP control count " + controlCount);
        }
        controlIdentifiers = new int[controlCount];
        controlValues = new byte[controlCount][];
        for (int i = 0; i < controlCount; i++) {
            controlIdentifiers[i] = in.readInt();
            byte[] value = in.createByteArray();
            controlValues[i] = value == null ? new byte[0] : value;
        }
        attConnected = in.readInt() != 0;
        int attCount = in.readInt();
        if (attCount < 0 || attCount > 256) {
            throw new BadParcelableException("Invalid ATT value count " + attCount);
        }
        attHandles = new int[attCount];
        attValues = new byte[attCount][];
        for (int i = 0; i < attCount; i++) {
            attHandles[i] = in.readInt();
            byte[] value = in.createByteArray();
            attValues[i] = value == null ? new byte[0] : value;
        }
        int messageCount = in.readInt();
        if (messageCount < 0 || messageCount > 32) {
            throw new BadParcelableException("Invalid AACP message count " + messageCount);
        }
        aacpMessageOpcodes = new int[messageCount];
        aacpMessageValues = new byte[messageCount][];
        aacpMessageSequences = new long[messageCount];
        for (int i = 0; i < messageCount; i++) {
            aacpMessageOpcodes[i] = in.readInt();
            byte[] value = in.createByteArray();
            aacpMessageValues[i] = value == null ? new byte[0] : value;
            aacpMessageSequences[i] = in.readLong();
        }
        updatedElapsedRealtimeNanos = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(connected ? 1 : 0);
        out.writeString(deviceKey);
        out.writeString(deviceAddress);
        out.writeString(displayName);
        out.writeString(modelNumber);
        out.writeString(manufacturer);
        out.writeString(serialNumber);
        out.writeString(leftSerialNumber);
        out.writeString(rightSerialNumber);
        out.writeString(version1);
        out.writeString(version2);
        out.writeString(version3);
        out.writeString(hardwareRevision);
        out.writeInt(leftBattery);
        out.writeInt(rightBattery);
        out.writeInt(caseBattery);
        out.writeInt(leftBatteryStatus);
        out.writeInt(rightBatteryStatus);
        out.writeInt(caseBatteryStatus);
        out.writeInt(leftWear);
        out.writeInt(rightWear);
        out.writeInt(noiseControlMode);
        out.writeInt(leftLongPressAction);
        out.writeInt(rightLongPressAction);
        int controlCount = Math.min(controlIdentifiers.length, controlValues.length);
        out.writeInt(controlCount);
        for (int i = 0; i < controlCount; i++) {
            out.writeInt(controlIdentifiers[i]);
            out.writeByteArray(controlValues[i]);
        }
        out.writeInt(attConnected ? 1 : 0);
        int attCount = Math.min(attHandles.length, attValues.length);
        out.writeInt(attCount);
        for (int i = 0; i < attCount; i++) {
            out.writeInt(attHandles[i]);
            out.writeByteArray(attValues[i]);
        }
        int messageCount = Math.min(Math.min(aacpMessageOpcodes.length,
                aacpMessageValues.length), aacpMessageSequences.length);
        out.writeInt(messageCount);
        for (int i = 0; i < messageCount; i++) {
            out.writeInt(aacpMessageOpcodes[i]);
            out.writeByteArray(aacpMessageValues[i]);
            out.writeLong(aacpMessageSequences[i]);
        }
        out.writeLong(updatedElapsedRealtimeNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AirPodsState> CREATOR = new Creator<AirPodsState>() {
        @Override
        public AirPodsState createFromParcel(Parcel in) {
            return new AirPodsState(in);
        }

        @Override
        public AirPodsState[] newArray(int size) {
            return new AirPodsState[size];
        }
    };

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    public byte[] getControlValue(int identifier) {
        int count = Math.min(controlIdentifiers.length, controlValues.length);
        for (int i = 0; i < count; i++) {
            if (controlIdentifiers[i] == identifier) {
                return Arrays.copyOf(controlValues[i], controlValues[i].length);
            }
        }
        return null;
    }

    public byte[] getAttValue(int handle) {
        int count = Math.min(attHandles.length, attValues.length);
        for (int i = 0; i < count; i++) {
            if (attHandles[i] == handle) {
                return Arrays.copyOf(attValues[i], attValues[i].length);
            }
        }
        return null;
    }

    public byte[] getAacpMessageValue(int opcode) {
        int count = Math.min(aacpMessageOpcodes.length, aacpMessageValues.length);
        for (int i = 0; i < count; i++) {
            if (aacpMessageOpcodes[i] == opcode) {
                return Arrays.copyOf(aacpMessageValues[i], aacpMessageValues[i].length);
            }
        }
        return null;
    }

    public long getAacpMessageSequence(int opcode) {
        int count = Math.min(aacpMessageOpcodes.length, aacpMessageSequences.length);
        for (int i = 0; i < count; i++) {
            if (aacpMessageOpcodes[i] == opcode) return aacpMessageSequences[i];
        }
        return 0;
    }

    private static byte[][] copyValues(byte[][] values) {
        byte[][] result = new byte[values.length][];
        for (int i = 0; i < values.length; i++) {
            byte[] value = values[i];
            result[i] = value == null ? new byte[0] : Arrays.copyOf(value, value.length);
        }
        return result;
    }
}
