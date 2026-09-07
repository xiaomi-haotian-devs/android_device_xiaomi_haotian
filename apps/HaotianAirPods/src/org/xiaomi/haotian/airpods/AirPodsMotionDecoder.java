/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.airpods;

import org.xiaomi.haotian.audio.HeadPoseFrame;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Normalizes RTBuddy devmotion6 reports into the Android head coordinate frame. */
final class AirPodsMotionDecoder {
    private static final float Q15 = 32768f;
    private static final int CALIBRATION_SAMPLES = 10;
    // AACP motion reports arrive in short bursts. A quarter-second transport gap is common and
    // must not be advertised as a new reference frame to Android's pose controller.
    private static final long GAP_NANOS = 750_000_000L;
    private static final float MAX_CALIBRATION_ANGLE_RAD = 0.35f;
    private static final float MAX_ANGULAR_SPEED_RAD_S = 20f;
    private static final float VELOCITY_FILTER_SECONDS = 0.04f;

    private final String deviceKey;
    private final String providerId;
    private final List<Quaternion> calibration = new ArrayList<>();

    private Quaternion calibrationReference;
    private Quaternion neutral;
    private Quaternion previousRaw;
    private Quaternion previousOutput;
    private long previousTimestamp;
    private float filteredVx;
    private float filteredVy;
    private float filteredVz;
    private int discontinuityCount;

    AirPodsMotionDecoder(String deviceKey, String providerId) {
        this.deviceKey = deviceKey;
        this.providerId = providerId;
    }

    HeadPoseFrame decode(byte[] packet) {
        long packetReceivedNanos = SystemClock.elapsedRealtimeNanos();
        AacpProtocol.CommandPayload command = AacpProtocol.commandPayload(packet);
        return decode(command, packetReceivedNanos);
    }

    synchronized HeadPoseFrame decode(AacpProtocol.CommandPayload command) {
        return decode(command, SystemClock.elapsedRealtimeNanos());
    }

    synchronized HeadPoseFrame decode(AacpProtocol.CommandPayload command,
            long packetReceivedNanos) {
        if (command == null || command.payload.length < 58
                || command.payload[0] != 1 || command.payload[9] != 3) {
            return null;
        }

        byte[] payload = command.payload;
        float sx = signedLe16(payload, 20) / Q15;
        float sy = signedLe16(payload, 22) / Q15;
        float sz = signedLe16(payload, 24) / Q15;
        float vectorLengthSquared = sx * sx + sy * sy + sz * sz;
        if (!Float.isFinite(vectorLengthSquared) || vectorLengthSquared > 1.05f) {
            return null;
        }

        float sw = (float) Math.sqrt(Math.max(0f, 1f - vectorLengthSquared));
        Quaternion raw = new Quaternion(sx, sy, sz, sw).normalized();
        // The transport omits w and conventionally reconstructs its positive root. Continuity is
        // still selected against the preceding full quaternion: near 180 degrees this may make w
        // negative, which is correct and avoids the positive-w sign jump.
        if (previousRaw != null && raw.dot(previousRaw) < 0f) raw = raw.negated();
        previousRaw = raw;

        long timestamp = packetReceivedNanos;
        if (previousTimestamp != 0 && timestamp - previousTimestamp > GAP_NANOS) {
            discontinuityCount++;
            previousOutput = null;
            filteredVx = filteredVy = filteredVz = 0f;
        }

        if (neutral == null) {
            collectCalibration(raw);
            previousTimestamp = timestamp;
            return null;
        }

        // devmotion6 reports the inverse of the headset-to-reference orientation consumed by
        // HeadPoseFrame. Invert the calibrated delta as a complete quaternion instead of
        // mirroring only yaw: the latter is not a valid basis transform and breaks combined
        // yaw/pitch/roll motion. This also makes angular velocity inherit the corrected direction.
        Quaternion relative = raw.conjugate().multiply(neutral).normalized();
        // RTBuddy devmotion6 uses Y-up axes. The cyclic, handedness-preserving basis map is:
        // source Z -> Android head X (right ear), source X -> Android Y (nose), source Y -> Z (up).
        Quaternion output = new Quaternion(relative.z, relative.x, relative.y, relative.w);
        if (previousOutput != null && output.dot(previousOutput) < 0f) output = output.negated();

        float vx = 0f;
        float vy = 0f;
        float vz = 0f;
        // The two signed values at offsets 28 and 30 are dynamic acceleration projections, not a
        // gravity vector. AirPods Pro 3 reports naturally cross zero while the orientation stream
        // remains valid, so they must not gate calibration or pose publication.
        float confidence = 0.82f;
        if (previousOutput != null && previousTimestamp != 0) {
            float dt = (timestamp - previousTimestamp) * 1e-9f;
            if (dt >= 0.005f && dt <= 0.1f) {
                Quaternion delta = previousOutput.conjugate().multiply(output).normalized();
                if (delta.w < 0f) delta = delta.negated();
                float sinHalf = length(delta.x, delta.y, delta.z);
                float angle = 2f * (float) Math.atan2(sinHalf, Math.max(0f, delta.w));
                float scale = sinHalf > 1e-6f ? angle / (sinHalf * dt) : 2f / dt;
                float rawVx = delta.x * scale;
                float rawVy = delta.y * scale;
                float rawVz = delta.z * scale;
                float speed = length(rawVx, rawVy, rawVz);
                if (!Float.isFinite(speed) || speed > MAX_ANGULAR_SPEED_RAD_S) {
                    discontinuityCount++;
                    filteredVx = filteredVy = filteredVz = 0f;
                    previousOutput = output;
                    previousTimestamp = timestamp;
                    return null;
                }
                float alpha = dt / (VELOCITY_FILTER_SECONDS + dt);
                filteredVx += alpha * (rawVx - filteredVx);
                filteredVy += alpha * (rawVy - filteredVy);
                filteredVz += alpha * (rawVz - filteredVz);
                vx = filteredVx;
                vy = filteredVy;
                vz = filteredVz;
                confidence = Math.min(confidence, dt <= 0.035f ? 0.9f : 0.76f);
            }
        }

        previousOutput = output;
        previousTimestamp = timestamp;
        long decodedNanos = SystemClock.elapsedRealtimeNanos();
        return new HeadPoseFrame(deviceKey, providerId, packetReceivedNanos, decodedNanos,
                output.x, output.y, output.z, output.w,
                vx, vy, vz, confidence, discontinuityCount & 0xff);
    }

    synchronized void recenter() {
        calibration.clear();
        calibrationReference = null;
        neutral = null;
        previousOutput = null;
        previousRaw = null;
        previousTimestamp = 0;
        filteredVx = filteredVy = filteredVz = 0f;
        discontinuityCount++;
    }

    private void collectCalibration(Quaternion raw) {
        if (calibrationReference == null) {
            calibrationReference = raw;
        }
        Quaternion aligned = raw.dot(calibrationReference) < 0f ? raw.negated() : raw;
        float angularDistance = calibrationReference.conjugate()
                .multiply(aligned).normalized().angle();
        if (angularDistance > MAX_CALIBRATION_ANGLE_RAD) {
            calibration.clear();
            calibrationReference = aligned;
        }
        calibration.add(aligned);
        if (calibration.size() < CALIBRATION_SAMPLES) return;

        float x = 0f, y = 0f, z = 0f, w = 0f;
        for (Quaternion value : calibration) {
            Quaternion sample = value.dot(calibrationReference) < 0f ? value.negated() : value;
            x += sample.x;
            y += sample.y;
            z += sample.z;
            w += sample.w;
        }
        neutral = new Quaternion(x, y, z, w).normalized();
        previousOutput = null;
        discontinuityCount++;
    }

    private static short signedLe16(byte[] data, int offset) {
        return (short) ((data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8));
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static final class Quaternion {
        final float x;
        final float y;
        final float z;
        final float w;

        Quaternion(float x, float y, float z, float w) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
        }

        Quaternion normalized() {
            float norm = (float) Math.sqrt(dot(this));
            return norm > 1e-8f ? new Quaternion(x / norm, y / norm, z / norm, w / norm)
                    : new Quaternion(0f, 0f, 0f, 1f);
        }

        Quaternion conjugate() {
            return new Quaternion(-x, -y, -z, w);
        }

        Quaternion negated() {
            return new Quaternion(-x, -y, -z, -w);
        }

        Quaternion multiply(Quaternion other) {
            return new Quaternion(
                    w * other.x + x * other.w + y * other.z - z * other.y,
                    w * other.y - x * other.z + y * other.w + z * other.x,
                    w * other.z + x * other.y - y * other.x + z * other.w,
                    w * other.w - x * other.x - y * other.y - z * other.z);
        }

        float dot(Quaternion other) {
            return x * other.x + y * other.y + z * other.z + w * other.w;
        }

        float angle() {
            Quaternion value = w < 0 ? negated() : this;
            return 2f * (float) Math.atan2(length(value.x, value.y, value.z),
                    Math.max(0f, value.w));
        }
    }
}
