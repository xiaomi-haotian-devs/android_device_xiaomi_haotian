/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.xiaomi.haotian.airpods;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** LibrePods-compatible opaque bodies for AACP Smart Routing opcode 0x10. */
final class SmartRoutingPackets {
    static final int OPCODE = 0x10;

    private SmartRoutingPackets() {
    }

    static byte[] mediaInformationForNewDevice(String self, String target) {
        Builder out = begin(target);
        out.bytes(0x6c, 0x00, 0x01, 0xe5, 0x4a).text("playingApp").bytes(0x42)
                .text("NA").bytes(0x52).text("hostStreamingState").bytes(0x42)
                .text("NO").bytes(0x49).text("btAddress").bytes(0x51).text(validMac(self))
                .bytes(0x46).text("btName").bytes(0x47).text("Android").bytes(0x58)
                .text("otherDevice").text("AudioCategory").bytes(0x30, 0x64);
        return out.toByteArray(116);
    }

    static byte[] mediaInformation(String self, String target, boolean streaming) {
        Builder out = begin(target);
        out.bytes(0x82, 0x00, 0x01, 0xe5, 0x4a).text("PlayingApp").bytes(0x56)
                .text("com.google.ios.youtube").bytes(0x52).text("HostStreamingState")
                .bytes(0x42).text(streaming ? "YES" : "NO").bytes(0x49)
                .text("btAddress").bytes(0x51).text(validMac(self)).text("btName")
                .bytes(0x47).text("Android").bytes(0x58).text("otherDevice")
                .text("AudioCategory").bytes(0x31, 0x2d, 0x01);
        return out.toByteArray(138);
    }

    static byte[] hijackRequest(String target) {
        Builder out = begin(target);
        out.bytes(0x62, 0x00, 0x01, 0xe5, 0x4a).text("localscore")
                .bytes(0x30, 0x64, 0x46).text("reason").bytes(0x48).text("Hijackv2")
                .bytes(0x51).text("audioRoutingScore").bytes(0x31, 0x2d, 0x01, 0x5f)
                .text("audioRoutingSetOwnershipToFalse").bytes(0x01, 0x4b)
                .text("remotescore").bytes(0xa5);
        return out.toByteArray(106);
    }

    static byte[] showNearbyUi(String target) {
        Builder out = begin(target);
        out.bytes(0x7e, 0x00, 0x01, 0xe6, 0x5b).text("SmartRoutingKeyShowNearbyUI")
                .bytes(0x01, 0x4a).text("localscore").bytes(0x31, 0x2d, 0x01, 0x46)
                .text("reasonHhijackv2").bytes(0x51).text("audioRoutingScore")
                .bytes(0xa2, 0x5f).text("audioRoutingSetOwnershipToFalse").bytes(0x01, 0x4b)
                .text("remotescore").bytes(0xa2);
        return out.toByteArray(134);
    }

    static byte[] addTipiDevice(String self, String target) {
        Builder out = begin(target);
        out.bytes(0x52, 0x00, 0x01, 0xe5, 0x48).text("idleTime").bytes(0x08, 0x47)
                .text("newTipi").bytes(0x01, 0x49).text("btAddress").bytes(0x51)
                .text(validMac(self)).bytes(0x46).text("btName").bytes(0x47)
                .text("Android").bytes(0x50).text("nearbyAudioScore").bytes(0x0e);
        return out.toByteArray(90);
    }

    private static Builder begin(String target) {
        Builder out = new Builder();
        byte[] mac = macBytes(validMac(target));
        for (int i = mac.length - 1; i >= 0; i--) out.bytes(mac[i] & 0xff);
        return out;
    }

    private static String validMac(String value) {
        String mac = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            throw new IllegalArgumentException("Invalid Bluetooth address");
        }
        return mac;
    }

    private static byte[] macBytes(String value) {
        String[] parts = value.split(":");
        byte[] result = new byte[6];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return result;
    }

    private static final class Builder {
        private final ByteArrayOutputStream stream = new ByteArrayOutputStream(160);

        Builder bytes(int... values) {
            for (int value : values) stream.write(value & 0xff);
            return this;
        }

        Builder text(String value) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            stream.write(encoded, 0, encoded.length);
            return this;
        }

        byte[] toByteArray(int fixedSize) {
            if (stream.size() > fixedSize) {
                throw new IllegalStateException("Smart Routing packet exceeds fixed size");
            }
            while (stream.size() < fixedSize) stream.write(0);
            return stream.toByteArray();
        }
    }
}
