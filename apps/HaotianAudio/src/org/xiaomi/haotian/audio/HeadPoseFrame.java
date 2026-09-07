/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

/**
 * Vendor-neutral head pose sample produced by a headphone-specific provider.
 *
 * <p>The quaternion rotates from the headset frame into the world frame. Angular velocity is in
 * radians per second in headset coordinates. This object is deliberately not Parcelable: pose
 * samples use the shared-memory/Sensors HAL data path, never Binder transactions.</p>
 */
public final class HeadPoseFrame {
    public final String deviceKey;
    public final String providerId;
    /** CLOCK_BOOTTIME timestamp taken immediately after the accessory pose packet was read. */
    public final long packetReceivedTimestampNanos;
    /** CLOCK_BOOTTIME timestamp taken after parsing and normalization completed. */
    public final long decodedTimestampNanos;
    public final long timestampNanos;
    public final float qx;
    public final float qy;
    public final float qz;
    public final float qw;
    public final float vx;
    public final float vy;
    public final float vz;
    public final float confidence;
    public final int discontinuityCount;

    public HeadPoseFrame(String deviceKey, String providerId, long packetReceivedTimestampNanos,
            long decodedTimestampNanos,
            float qx, float qy, float qz, float qw,
            float vx, float vy, float vz, float confidence, int discontinuityCount) {
        if (deviceKey == null || deviceKey.isEmpty()) {
            throw new IllegalArgumentException("deviceKey must not be empty");
        }
        if (providerId == null || providerId.isEmpty()) {
            throw new IllegalArgumentException("providerId must not be empty");
        }
        if (packetReceivedTimestampNanos <= 0
                || decodedTimestampNanos < packetReceivedTimestampNanos) {
            throw new IllegalArgumentException("Invalid frame timestamps");
        }
        requireFinite(qx, qy, qz, qw, vx, vy, vz, confidence);
        float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (length < 0.5f || length > 1.5f) {
            throw new IllegalArgumentException("Invalid orientation quaternion");
        }
        this.deviceKey = deviceKey;
        this.providerId = providerId;
        this.packetReceivedTimestampNanos = packetReceivedTimestampNanos;
        this.decodedTimestampNanos = decodedTimestampNanos;
        // Sensor timestamps use the earliest timestamp available in this process. This makes the
        // framework's timestamp delay include parsing/normalization instead of hiding it.
        this.timestampNanos = packetReceivedTimestampNanos;
        this.qx = qx / length;
        this.qy = qy / length;
        this.qz = qz / length;
        this.qw = qw / length;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.confidence = Math.max(0f, Math.min(1f, confidence));
        this.discontinuityCount = Math.max(0, discontinuityCount);
    }

    private static void requireFinite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite pose");
        }
    }
}
