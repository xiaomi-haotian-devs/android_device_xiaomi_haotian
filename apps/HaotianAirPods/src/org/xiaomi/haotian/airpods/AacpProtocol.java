/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.airpods;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Clean-room encoder/parser for the small AACP/RTBuddy subset used by Apple motion sensors.
 *
 * <p>The wire facts are documented by the LibrePods AAP definitions, the apple-wireshark
 * RTBuddy protobuf schema and independent captures in FIocker/aap-head-tracker. No source from
 * those GPL projects is incorporated here.</p>
 */
final class AacpProtocol {
    static final int PSM = 0x1001;
    static final int SERVICE_DEVMOTION6 = 16;
    static final int SERVICE_LEGACY_MOTION = 14;
    static final int SERVICE_OBSERVED_DYNAMIC = 6;

    private static final byte[] AACP_PREFIX = {0x04, 0x00, 0x04, 0x00};
    private static final byte[] DEVMOTION6 =
            "devmotion6".getBytes(StandardCharsets.US_ASCII);
    private static final int OPCODE_BATTERY = 0x04;
    private static final int OPCODE_EAR_DETECTION = 0x06;
    private static final int OPCODE_CONTROL = 0x09;
    private static final int OPCODE_STEM_PRESS = 0x19;
    private static final int OPCODE_RENAME = 0x1a;
    private static final int CONTROL_LISTENING_MODE = 0x0d;

    private AacpProtocol() {
    }

    static byte[] handshake() {
        return new byte[] {
                0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
    }

    static byte[] notificationRequest() {
        return new byte[] {
                0x04, 0x00, 0x04, 0x00, 0x0f, 0x00,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        };
    }

    /** Advertises the phone-side AACP notification features before requesting motion data. */
    static byte[] featureFlags() {
        return new byte[] {
                0x04, 0x00, 0x04, 0x00, 0x4d, 0x00,
                (byte) 0xd7, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
    }

    /** Requests both the identity-resolving and encryption proximity keys. */
    static byte[] proximityKeysRequest() {
        return new byte[] {
                0x04, 0x00, 0x04, 0x00, 0x30, 0x00, 0x05, 0x00
        };
    }

    static byte[] listeningMode(int mode) {
        if (mode < AirPodsState.NOISE_OFF || mode > AirPodsState.NOISE_ADAPTIVE) {
            throw new IllegalArgumentException("Unsupported listening mode " + mode);
        }
        return new byte[] {
                0x04, 0x00, 0x04, 0x00, (byte) OPCODE_CONTROL, 0x00,
                (byte) CONTROL_LISTENING_MODE, (byte) mode, 0x00, 0x00, 0x00
        };
    }

    /** Encodes the fixed-width payload used by AACP control notifications and requests. */
    static byte[] controlCommand(int identifier, byte[] value) {
        if (identifier < 0 || identifier > 0xff) {
            throw new IllegalArgumentException("Invalid control identifier " + identifier);
        }
        if (value == null || value.length < 1 || value.length > 4) {
            throw new IllegalArgumentException("AACP controls require one to four value bytes");
        }
        byte[] packet = new byte[11];
        System.arraycopy(AACP_PREFIX, 0, packet, 0, AACP_PREFIX.length);
        packet[4] = (byte) OPCODE_CONTROL;
        packet[6] = (byte) identifier;
        System.arraycopy(value, 0, packet, 7, value.length);
        return packet;
    }

    static ControlCommand controlCommandFromNotification(byte[] packet) {
        if (!hasOpcode(packet, OPCODE_CONTROL) || packet.length < 8) return null;
        int identifier = packet[6] & 0xff;
        int available = Math.min(4, packet.length - 7);
        if (available <= 0) return null;
        int length = available;
        while (length > 1 && packet[7 + length - 1] == 0) length--;
        return new ControlCommand(identifier, Arrays.copyOfRange(packet, 7, 7 + length));
    }

    static byte[] rename(String name) {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 1 || encoded.length > 255) {
            throw new IllegalArgumentException("AirPods name must contain 1 to 255 UTF-8 bytes");
        }
        ByteArrayOutputStream packet = new ByteArrayOutputStream(9 + encoded.length);
        packet.write(AACP_PREFIX, 0, AACP_PREFIX.length);
        packet.write(OPCODE_RENAME);
        packet.write(0);
        packet.write(1);
        packet.write(encoded.length);
        packet.write(0);
        packet.write(encoded, 0, encoded.length);
        return packet.toByteArray();
    }

    /** Wraps an opaque AACP opcode body; feature-specific contents are owned by the client. */
    static byte[] message(int opcode, byte[] body) {
        if (opcode < 0 || opcode > 0xff || body == null || body.length > 512) {
            throw new IllegalArgumentException("Invalid AACP message");
        }
        byte[] packet = new byte[6 + body.length];
        System.arraycopy(AACP_PREFIX, 0, packet, 0, AACP_PREFIX.length);
        packet[4] = (byte) opcode;
        System.arraycopy(body, 0, packet, 6, body.length);
        return packet;
    }

    static byte[] messageBody(byte[] packet, int opcode) {
        if (!hasOpcode(packet, opcode) || packet.length < 6) return null;
        return Arrays.copyOfRange(packet, 6, packet.length);
    }

    static int listeningModeFromNotification(byte[] packet) {
        if (!hasOpcode(packet, OPCODE_CONTROL) || packet.length < 8
                || (packet[6] & 0xff) != CONTROL_LISTENING_MODE) {
            return AirPodsState.NOISE_UNKNOWN;
        }
        int mode = packet[7] & 0xff;
        return mode >= AirPodsState.NOISE_OFF && mode <= AirPodsState.NOISE_ADAPTIVE
                ? mode : AirPodsState.NOISE_UNKNOWN;
    }

    static int[] earStateFromNotification(byte[] packet) {
        if (!hasOpcode(packet, OPCODE_EAR_DETECTION) || packet.length < 8) return null;
        return new int[] {decodeWear(packet[6] & 0xff), decodeWear(packet[7] & 0xff)};
    }

    /** Returns the press type and bud identifiers from a forwarded stem event. */
    static StemPress stemPressFromNotification(byte[] packet) {
        if (!hasOpcode(packet, OPCODE_STEM_PRESS) || packet.length < 8) return null;
        int pressType = packet[6] & 0xff;
        int bud = packet[7] & 0xff;
        if (pressType < 0x05 || pressType > 0x08 || (bud != 0x01 && bud != 0x02)) {
            return null;
        }
        return new StemPress(pressType, bud);
    }

    /** Returns component, level and charging-state triples from an AACP battery notification. */
    static int[][] batteryFromNotification(byte[] packet) {
        if (!hasOpcode(packet, OPCODE_BATTERY) || packet.length < 7) return null;
        int count = packet[6] & 0xff;
        if (count < 1 || count > 4 || packet.length < 7 + count * 5) return null;
        int[][] result = new int[count][3];
        for (int index = 0; index < count; index++) {
            int offset = 7 + index * 5;
            result[index][0] = packet[offset] & 0xff;
            result[index][1] = packet[offset + 2] & 0xff;
            result[index][2] = packet[offset + 3] & 0xff;
        }
        return result;
    }

    private static boolean hasOpcode(byte[] packet, int opcode) {
        return isAacpPacket(packet) && packet.length >= 6 && (packet[4] & 0xff) == opcode;
    }

    private static int decodeWear(int raw) {
        switch (raw) {
            case 0:
                return AirPodsState.WEAR_IN_EAR;
            case 1:
                return AirPodsState.WEAR_OUT_OF_EAR;
            case 2:
                return AirPodsState.WEAR_IN_CASE;
            default:
                return AirPodsState.WEAR_DISCONNECTED;
        }
    }

    static byte[] motionSetting(int sequence, int service, boolean enabled) {
        return motionSetting(sequence, service, enabled,
                service == SERVICE_DEVMOTION6 || service == SERVICE_OBSERVED_DYNAMIC);
    }

    static byte[] motionSetting(int sequence, int service, boolean enabled, boolean highRate) {
        ByteArrayOutputStream setting = new ByteArrayOutputStream();
        writeVarintField(setting, 1, service);
        writeVarintField(setting, 2, 2);
        byte[] interval;
        if (!enabled) {
            interval = new byte[] {0x01, 0x00, 0x00, 0x00, 0x00};
        } else if (highRate) {
            interval = new byte[] {0x01, 0x20, 0x4e, 0x00, 0x00}; // 20,000 us / 50 Hz
        } else {
            interval = new byte[] {0x01, 0x40, (byte) 0x9c, 0x00, 0x00}; // 40,000 us / 25 Hz
        }
        writeBytesField(setting, 3, interval);

        ByteArrayOutputStream protobuf = new ByteArrayOutputStream();
        writeVarintField(protobuf, 1, sequence & 0x7fffffff);
        writeBytesField(protobuf, 8, setting.toByteArray());

        byte[] payload = protobuf.toByteArray();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(AACP_PREFIX, 0, AACP_PREFIX.length);
        packet.write(0x17);
        packet.write(0x00);
        writeLe32(packet, 0x00100000);
        writeLe16(packet, payload.length);
        packet.write(payload, 0, payload.length);
        return packet.toByteArray();
    }

    static boolean isAacpPacket(byte[] packet) {
        if (packet == null || packet.length < AACP_PREFIX.length) return false;
        for (int i = 0; i < AACP_PREFIX.length; i++) {
            if (packet[i] != AACP_PREFIX[i]) return false;
        }
        return true;
    }

    static boolean isInformationPacket(byte[] packet) {
        return isAacpPacket(packet) && packet.length > 8 && (packet[4] & 0xff) == 0x1d;
    }

    static String findModelNumber(byte[] packet) {
        DeviceInformation information = deviceInformation(packet);
        return information == null ? "" : information.modelNumber;
    }

    /** Parses the NUL-delimited identity fields emitted after the information preamble. */
    static DeviceInformation deviceInformation(byte[] packet) {
        if (!isInformationPacket(packet)) return null;
        int index = findTerminator(packet, 6);
        if (index < 0) return null;
        List<String> strings = new ArrayList<>();
        while (index < packet.length) {
            while (index < packet.length && packet[index] == 0) index++;
            if (index >= packet.length) break;
            int end = findTerminator(packet, index);
            if (end < 0) break;
            strings.add(new String(packet, index, end - index, StandardCharsets.UTF_8));
            index = end + 1;
        }
        // Current firmware places one small binary/preamble field before the accessory name.
        // Locate the hardware model instead of assuming a fixed preamble length.
        int modelIndex = -1;
        for (int i = 0; i < strings.size(); i++) {
            if (isModelNumber(strings.get(i))) {
                modelIndex = i;
                break;
            }
        }
        if (modelIndex < 1) return null;
        return new DeviceInformation(
                strings.get(modelIndex - 1),
                strings.get(modelIndex),
                stringAt(strings, modelIndex + 1),
                stringAt(strings, modelIndex + 2),
                stringAt(strings, modelIndex + 3),
                stringAt(strings, modelIndex + 4),
                stringAt(strings, modelIndex + 5),
                stringAt(strings, modelIndex + 6),
                stringAt(strings, modelIndex + 7),
                stringAt(strings, modelIndex + 8),
                stringAt(strings, modelIndex + 9));
    }

    static boolean isAirPodsPro3Model(String model) {
        return "A3063".equals(model) || "A3064".equals(model) || "A3065".equals(model);
    }

    /** Returns descriptor service IDs whose descriptor explicitly names devmotion6. */
    static Set<Integer> discoverMotionServices(byte[] packet) {
        Set<Integer> services = new LinkedHashSet<>();
        CommandPayload outer = parseRtBuddy(packet, false);
        if (outer == null) return services;
        ProtoReader reader = new ProtoReader(outer.payload, 0, outer.payload.length);
        ProtoField field;
        while ((field = reader.next()) != null) {
            if (field.number != 5 || field.wireType != 2) continue;
            ProtoReader descriptor = new ProtoReader(field.bytes, 0, field.bytes.length);
            int service = -1;
            byte[] description = null;
            ProtoField nested;
            while ((nested = descriptor.next()) != null) {
                if (nested.number == 1 && nested.wireType == 0) {
                    service = (int) nested.varint;
                } else if (nested.number == 2 && nested.wireType == 2) {
                    description = nested.bytes;
                }
            }
            if (service >= 0 && containsAscii(description, DEVMOTION6)) services.add(service);
        }

        // Some firmware returns the descriptor as a plist outside field 5. Accept its explicit
        // devmotion6 name only when an unambiguous protobuf service descriptor is also present.
        if (services.isEmpty() && containsAscii(packet, DEVMOTION6)) {
            Set<Integer> descriptors = descriptorServiceIds(outer.payload);
            if (descriptors.size() == 1) services.add(descriptors.iterator().next());
        }
        return services;
    }

    static CommandPayload commandPayload(byte[] packet) {
        return parseRtBuddy(packet, true);
    }

    private static CommandPayload parseRtBuddy(byte[] packet, boolean commandOnly) {
        if (!isAacpPacket(packet) || packet.length < 12 || (packet[4] & 0xff) != 0x17) {
            return null;
        }
        int descriptor = le32(packet, 6);
        int declared = le16(packet, 10);
        int length = Math.min(declared, packet.length - 12);
        if (length < 0 || (commandOnly && descriptor != 0x00100000)) return null;
        byte[] protobuf = new byte[length];
        System.arraycopy(packet, 12, protobuf, 0, length);
        if (!commandOnly) return new CommandPayload(-1, protobuf);

        ProtoReader reader = new ProtoReader(protobuf, 0, protobuf.length);
        ProtoField field;
        while ((field = reader.next()) != null) {
            if (field.number != 7 || field.wireType != 2) continue;
            ProtoReader command = new ProtoReader(field.bytes, 0, field.bytes.length);
            int service = -1;
            byte[] payload = null;
            ProtoField nested;
            while ((nested = command.next()) != null) {
                if (nested.number == 1 && nested.wireType == 0) {
                    service = (int) nested.varint;
                } else if (nested.number == 3 && nested.wireType == 2) {
                    payload = nested.bytes;
                }
            }
            if (service >= 0 && payload != null) return new CommandPayload(service, payload);
        }
        return null;
    }

    private static Set<Integer> descriptorServiceIds(byte[] protobuf) {
        Set<Integer> result = new LinkedHashSet<>();
        ProtoReader reader = new ProtoReader(protobuf, 0, protobuf.length);
        ProtoField field;
        while ((field = reader.next()) != null) {
            if (field.number != 5 || field.wireType != 2) continue;
            ProtoReader descriptor = new ProtoReader(field.bytes, 0, field.bytes.length);
            ProtoField nested;
            while ((nested = descriptor.next()) != null) {
                if (nested.number == 1 && nested.wireType == 0) {
                    result.add((int) nested.varint);
                    break;
                }
            }
        }
        return result;
    }

    private static boolean containsAscii(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || haystack.length < needle.length) return false;
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                int a = haystack[i + j] & 0xff;
                int b = needle[j] & 0xff;
                if (a >= 'A' && a <= 'Z') a += 'a' - 'A';
                if (a != b) continue outer;
            }
            return true;
        }
        return false;
    }

    private static boolean isModelNumber(String value) {
        if (value == null || value.length() != 5 || value.charAt(0) != 'A') return false;
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return true;
    }

    private static String stringAt(List<String> strings, int index) {
        return index >= 0 && index < strings.size() ? strings.get(index) : "";
    }

    private static int findTerminator(byte[] data, int start) {
        for (int i = Math.max(0, start); i < data.length; i++) {
            if (data[i] == 0) return i;
        }
        return -1;
    }

    private static int le16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int le32(byte[] data, int offset) {
        return le16(data, offset) | (le16(data, offset + 2) << 16);
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        writeLe16(out, value);
        writeLe16(out, value >>> 16);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, ((long) field << 3));
        writeVarint(out, value);
    }

    private static void writeBytesField(ByteArrayOutputStream out, int field, byte[] value) {
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    static final class CommandPayload {
        final int service;
        final byte[] payload;

        CommandPayload(int service, byte[] payload) {
            this.service = service;
            this.payload = payload;
        }
    }

    static final class ControlCommand {
        final int identifier;
        final byte[] value;

        ControlCommand(int identifier, byte[] value) {
            this.identifier = identifier;
            this.value = value;
        }
    }

    static final class StemPress {
        final int pressType;
        final int bud;

        StemPress(int pressType, int bud) {
            this.pressType = pressType;
            this.bud = bud;
        }
    }

    static final class DeviceInformation {
        final String name;
        final String modelNumber;
        final String manufacturer;
        final String serialNumber;
        final String version1;
        final String version2;
        final String hardwareRevision;
        final String updaterIdentifier;
        final String leftSerialNumber;
        final String rightSerialNumber;
        final String version3;

        DeviceInformation(String name, String modelNumber, String manufacturer,
                String serialNumber, String version1, String version2, String hardwareRevision,
                String updaterIdentifier, String leftSerialNumber, String rightSerialNumber,
                String version3) {
            this.name = name;
            this.modelNumber = modelNumber;
            this.manufacturer = manufacturer;
            this.serialNumber = serialNumber;
            this.version1 = version1;
            this.version2 = version2;
            this.hardwareRevision = hardwareRevision;
            this.updaterIdentifier = updaterIdentifier;
            this.leftSerialNumber = leftSerialNumber;
            this.rightSerialNumber = rightSerialNumber;
            this.version3 = version3;
        }
    }

    private static final class ProtoField {
        int number;
        int wireType;
        long varint;
        byte[] bytes;
    }

    /** Bounds-checked protobuf reader: malformed discovery traffic is rejected, never guessed. */
    private static final class ProtoReader {
        private final byte[] data;
        private final int end;
        private int offset;

        ProtoReader(byte[] data, int offset, int length) {
            this.data = data;
            this.offset = offset;
            this.end = Math.min(data.length, offset + Math.max(0, length));
        }

        ProtoField next() {
            long key = readVarint();
            if (key < 0) return null;
            ProtoField field = new ProtoField();
            field.number = (int) (key >>> 3);
            field.wireType = (int) (key & 7);
            if (field.number <= 0) return null;
            switch (field.wireType) {
                case 0:
                    field.varint = readVarint();
                    return field.varint < 0 ? null : field;
                case 1:
                    return skipFixed(field, 8);
                case 2:
                    long rawLength = readVarint();
                    if (rawLength < 0 || rawLength > end - offset) return null;
                    int length = (int) rawLength;
                    field.bytes = new byte[length];
                    System.arraycopy(data, offset, field.bytes, 0, length);
                    offset += length;
                    return field;
                case 5:
                    return skipFixed(field, 4);
                default:
                    return null;
            }
        }

        private ProtoField skipFixed(ProtoField field, int length) {
            if (end - offset < length) return null;
            offset += length;
            return field;
        }

        private long readVarint() {
            long value = 0;
            for (int shift = 0; shift < 64 && offset < end; shift += 7) {
                int current = data[offset++] & 0xff;
                value |= (long) (current & 0x7f) << shift;
                if ((current & 0x80) == 0) return value;
            }
            return -1;
        }
    }
}
