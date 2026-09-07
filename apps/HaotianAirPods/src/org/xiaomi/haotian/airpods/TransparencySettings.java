/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Lossless editor for LibrePods' ATT 0x18 transparency characteristic.
 *
 * <p>The untouched EQ and optional own-voice fields are retained for every partial edit so a
 * Settings control cannot accidentally reset data configured by another device.</p>
 */
final class TransparencySettings {
    static final int EQ_BANDS = 8;
    private static final int BASE_LENGTH = 100;
    private static final int LEFT_EQ_OFFSET = 4;
    private static final int LEFT_AMPLIFICATION_OFFSET = 36;
    private static final int LEFT_TONE_OFFSET = 40;
    private static final int LEFT_CONVERSATION_BOOST_OFFSET = 44;
    private static final int LEFT_AMBIENT_REDUCTION_OFFSET = 48;
    private static final int RIGHT_EQ_OFFSET = 52;
    private static final int RIGHT_AMPLIFICATION_OFFSET = 84;
    private static final int RIGHT_TONE_OFFSET = 88;
    private static final int RIGHT_CONVERSATION_BOOST_OFFSET = 92;
    private static final int RIGHT_AMBIENT_REDUCTION_OFFSET = 96;

    private final byte[] value;

    private TransparencySettings(byte[] value) {
        this.value = value;
    }

    static TransparencySettings parse(byte[] value) {
        if (value == null || value.length < BASE_LENGTH) return null;
        byte[] copied = Arrays.copyOf(value, value.length);
        for (int offset = 0; offset < BASE_LENGTH; offset += Float.BYTES) {
            if (!isFinite(getFloat(copied, offset))) return null;
        }
        return new TransparencySettings(copied);
    }

    boolean isEnabled() {
        return getFloat(value, 0) > 0.5f;
    }

    float amplification() {
        return clamp((getFloat(value, LEFT_AMPLIFICATION_OFFSET)
                + getFloat(value, RIGHT_AMPLIFICATION_OFFSET)) / 2f, -1f, 1f);
    }

    float balance() {
        return clamp(getFloat(value, RIGHT_AMPLIFICATION_OFFSET)
                - getFloat(value, LEFT_AMPLIFICATION_OFFSET), -1f, 1f);
    }

    float tone() {
        return clamp(getFloat(value, LEFT_TONE_OFFSET), -1f, 1f);
    }

    float ambientNoiseReduction() {
        return clamp(getFloat(value, LEFT_AMBIENT_REDUCTION_OFFSET), 0f, 1f);
    }

    boolean conversationBoost() {
        return getFloat(value, LEFT_CONVERSATION_BOOST_OFFSET) > 0.5f;
    }

    float equalizerBand(int band) {
        checkBand(band);
        return clamp(getFloat(value, LEFT_EQ_OFFSET + band * Float.BYTES), 0f, 100f);
    }

    TransparencySettings withEnabled(boolean enabled) {
        byte[] edited = copyValue();
        putFloat(edited, 0, enabled ? 1f : 0f);
        return new TransparencySettings(edited);
    }

    TransparencySettings withAmplification(float amplification) {
        return withAmplificationAndBalance(clamp(amplification, -1f, 1f), balance());
    }

    TransparencySettings withBalance(float balance) {
        return withAmplificationAndBalance(amplification(), clamp(balance, -1f, 1f));
    }

    TransparencySettings withTone(float tone) {
        byte[] edited = copyValue();
        float requested = clamp(tone, -1f, 1f);
        putFloat(edited, LEFT_TONE_OFFSET, requested);
        putFloat(edited, RIGHT_TONE_OFFSET, requested);
        return new TransparencySettings(edited);
    }

    TransparencySettings withAmbientNoiseReduction(float reduction) {
        byte[] edited = copyValue();
        float requested = clamp(reduction, 0f, 1f);
        putFloat(edited, LEFT_AMBIENT_REDUCTION_OFFSET, requested);
        putFloat(edited, RIGHT_AMBIENT_REDUCTION_OFFSET, requested);
        return new TransparencySettings(edited);
    }

    TransparencySettings withConversationBoost(boolean enabled) {
        byte[] edited = copyValue();
        float requested = enabled ? 1f : 0f;
        putFloat(edited, LEFT_CONVERSATION_BOOST_OFFSET, requested);
        putFloat(edited, RIGHT_CONVERSATION_BOOST_OFFSET, requested);
        return new TransparencySettings(edited);
    }

    TransparencySettings withEqualizerBand(int band, float level) {
        checkBand(band);
        byte[] edited = copyValue();
        float requested = clamp(level, 0f, 100f);
        putFloat(edited, LEFT_EQ_OFFSET + band * Float.BYTES, requested);
        putFloat(edited, RIGHT_EQ_OFFSET + band * Float.BYTES, requested);
        return new TransparencySettings(edited);
    }

    byte[] toByteArray() {
        return copyValue();
    }

    boolean hasValue(byte[] reported) {
        return Arrays.equals(value, reported);
    }

    private TransparencySettings withAmplificationAndBalance(float amplification,
            float balance) {
        byte[] edited = copyValue();
        // Preserve LibrePods' mapping: balance adds gain only to the selected side.
        float left = amplification + (balance < 0f ? -balance : 0f);
        float right = amplification + (balance > 0f ? balance : 0f);
        putFloat(edited, LEFT_AMPLIFICATION_OFFSET, left);
        putFloat(edited, RIGHT_AMPLIFICATION_OFFSET, right);
        return new TransparencySettings(edited);
    }

    private byte[] copyValue() {
        return Arrays.copyOf(value, value.length);
    }

    private static float getFloat(byte[] value, int offset) {
        return ByteBuffer.wrap(value, offset, Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private static void putFloat(byte[] value, int offset, float requested) {
        ByteBuffer.wrap(value, offset, Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putFloat(requested);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void checkBand(int band) {
        if (band < 0 || band >= EQ_BANDS) {
            throw new IllegalArgumentException("Invalid transparency EQ band " + band);
        }
    }
}
