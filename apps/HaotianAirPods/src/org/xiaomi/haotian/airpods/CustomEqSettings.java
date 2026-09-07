/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

/** Feature-level representation of LibrePods' AACP 0x63 custom equalizer message. */
final class CustomEqSettings {
    private static final int BODY_LENGTH = 7;

    final int state;
    final int low;
    final int mid;
    final int high;

    private CustomEqSettings(int state, int low, int mid, int high) {
        this.state = state;
        this.low = low;
        this.mid = mid;
        this.high = high;
    }

    static CustomEqSettings parse(byte[] body) {
        if (body == null || body.length < BODY_LENGTH) return null;
        int declaredLength = (body[0] & 0xff) | ((body[1] & 0xff) << 8);
        int state = body[3] & 0xff;
        int low = body[4] & 0xff;
        int mid = body[5] & 0xff;
        int high = body[6] & 0xff;
        if (declaredLength != 5 || body[2] != 1 || (state != 1 && state != 2)
                || !isBand(low) || !isBand(mid) || !isBand(high)) {
            return null;
        }
        return new CustomEqSettings(state, low, mid, high);
    }

    static CustomEqSettings defaults() {
        return new CustomEqSettings(1, 50, 50, 50);
    }

    CustomEqSettings withEnabled(boolean enabled) {
        return new CustomEqSettings(enabled ? 2 : 1, low, mid, high);
    }

    CustomEqSettings withBands(int requestedLow, int requestedMid, int requestedHigh) {
        if (!isBand(requestedLow) || !isBand(requestedMid) || !isBand(requestedHigh)) {
            throw new IllegalArgumentException("Custom EQ bands must be between 0 and 100");
        }
        return new CustomEqSettings(state, requestedLow, requestedMid, requestedHigh);
    }

    byte[] toBody() {
        return new byte[] {
                0x05, 0x00, 0x01, (byte) state,
                (byte) low, (byte) mid, (byte) high
        };
    }

    boolean isEnabled() {
        return state == 2;
    }

    private static boolean isBand(int value) {
        return value >= 0 && value <= 100;
    }
}
