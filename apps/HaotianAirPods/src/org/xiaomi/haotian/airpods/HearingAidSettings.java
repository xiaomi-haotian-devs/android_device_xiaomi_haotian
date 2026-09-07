/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Lossless editor for LibrePods' ATT 0x2a hearing-aid characteristic. */
final class HearingAidSettings {
    static final int EQ_BANDS = 8;
    static final int LEFT = 0;
    static final int RIGHT = 1;
    private static final int BASE_LENGTH = 104;
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
    private static final int OWN_VOICE_AMPLIFICATION_OFFSET = 100;

    private final byte[] value;

    private HearingAidSettings(byte[] value) {
        this.value = value;
    }

    static HearingAidSettings parse(byte[] value) {
        if (value == null || value.length < BASE_LENGTH) return null;
        byte[] copied = Arrays.copyOf(value, value.length);
        for (int offset = 4; offset < BASE_LENGTH; offset += Float.BYTES) {
            if (!Float.isFinite(getFloat(copied, offset))) return null;
        }
        return new HearingAidSettings(copied);
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

    float ownVoiceAmplification() {
        return clamp(getFloat(value, OWN_VOICE_AMPLIFICATION_OFFSET), -1f, 1f);
    }

    float equalizerBand(int channel, int band) {
        checkChannel(channel);
        checkBand(band);
        int base = channel == LEFT ? LEFT_EQ_OFFSET : RIGHT_EQ_OFFSET;
        return clamp(getFloat(value, base + band * Float.BYTES), 0f, 100f);
    }

    HearingAidSettings withAmplification(float amplification) {
        return withAmplificationAndBalance(clamp(amplification, -1f, 1f), balance());
    }

    HearingAidSettings withBalance(float balance) {
        return withAmplificationAndBalance(amplification(), clamp(balance, -1f, 1f));
    }

    HearingAidSettings withTone(float tone) {
        byte[] edited = copyForWrite();
        float requested = clamp(tone, -1f, 1f);
        putFloat(edited, LEFT_TONE_OFFSET, requested);
        putFloat(edited, RIGHT_TONE_OFFSET, requested);
        return new HearingAidSettings(edited);
    }

    HearingAidSettings withAmbientNoiseReduction(float reduction) {
        byte[] edited = copyForWrite();
        float requested = clamp(reduction, 0f, 1f);
        putFloat(edited, LEFT_AMBIENT_REDUCTION_OFFSET, requested);
        putFloat(edited, RIGHT_AMBIENT_REDUCTION_OFFSET, requested);
        return new HearingAidSettings(edited);
    }

    HearingAidSettings withConversationBoost(boolean enabled) {
        byte[] edited = copyForWrite();
        float requested = enabled ? 1f : 0f;
        putFloat(edited, LEFT_CONVERSATION_BOOST_OFFSET, requested);
        putFloat(edited, RIGHT_CONVERSATION_BOOST_OFFSET, requested);
        return new HearingAidSettings(edited);
    }

    HearingAidSettings withOwnVoiceAmplification(float amplification) {
        byte[] edited = copyForWrite();
        putFloat(edited, OWN_VOICE_AMPLIFICATION_OFFSET,
                clamp(amplification, -1f, 1f));
        return new HearingAidSettings(edited);
    }

    HearingAidSettings withEqualizerBand(int channel, int band, float level) {
        checkChannel(channel);
        checkBand(band);
        byte[] edited = copyForWrite();
        int base = channel == LEFT ? LEFT_EQ_OFFSET : RIGHT_EQ_OFFSET;
        putFloat(edited, base + band * Float.BYTES, clamp(level, 0f, 100f));
        return new HearingAidSettings(edited);
    }

    byte[] toByteArray() {
        return Arrays.copyOf(value, value.length);
    }

    boolean hasValue(byte[] reported) {
        if (reported == null || reported.length != value.length) return false;
        for (int index = 0; index < value.length; index++) {
            // Reads use marker 0x60 while LibrePods' writable form changes it to 0x64.
            if (index != 2 && value[index] != reported[index]) return false;
        }
        return true;
    }

    private HearingAidSettings withAmplificationAndBalance(float amplification,
            float balance) {
        byte[] edited = copyForWrite();
        float left = amplification + (balance < 0f ? -balance : 0f);
        float right = amplification + (balance > 0f ? balance : 0f);
        putFloat(edited, LEFT_AMPLIFICATION_OFFSET, left);
        putFloat(edited, RIGHT_AMPLIFICATION_OFFSET, right);
        return new HearingAidSettings(edited);
    }

    private byte[] copyForWrite() {
        byte[] copied = Arrays.copyOf(value, value.length);
        // LibrePods changes the response marker 0x60 to the write marker 0x64.
        copied[2] = 0x64;
        return copied;
    }

    private static float getFloat(byte[] value, int offset) {
        return ByteBuffer.wrap(value, offset, Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private static void putFloat(byte[] value, int offset, float requested) {
        ByteBuffer.wrap(value, offset, Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).putFloat(requested);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void checkChannel(int channel) {
        if (channel != LEFT && channel != RIGHT) {
            throw new IllegalArgumentException("Invalid hearing-aid channel " + channel);
        }
    }

    private static void checkBand(int band) {
        if (band < 0 || band >= EQ_BANDS) {
            throw new IllegalArgumentException("Invalid hearing-aid EQ band " + band);
        }
    }
}
