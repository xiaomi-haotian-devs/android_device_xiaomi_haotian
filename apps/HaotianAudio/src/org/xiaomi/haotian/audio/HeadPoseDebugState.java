/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.os.SystemClock;

/**
 * Process-local, read-only tap on the normalized head-pose stream.
 *
 * <p>The producer runs at the headset sample rate, so this class stores primitives only and does
 * not allocate per frame. The debug UI creates a snapshot at its much lower drawing rate. Nothing
 * here participates in the Sensors HAL or audio rendering path.</p>
 */
final class HeadPoseDebugState {
    private static final Object LOCK = new Object();
    private static final long RECENT_FRAME_NANOS = 1_500_000_000L;
    private static final int PUBLISH_HISTORY_SIZE = 128;

    private static String outputName = "";
    private static String outputKey = "";
    private static String providerId = "";
    private static String error = "";
    private static long frameTimestampNanos;
    private static long packetReceivedTimestampNanos;
    private static long decodedTimestampNanos;
    private static long receivedTimestampNanos;
    private static long publishStartTimestampNanos;
    private static long publishEndTimestampNanos;
    private static long publishedSequence;
    private static long halConsumerSequence;
    private static long halSourcePublishTimestampNanos;
    private static long halReadTimestampNanos;
    private static long halPostTimestampNanos;
    private static long effectCallbackTimestampNanos;
    private static long effectSourceTimestampNanos;
    private static final long[] PUBLISH_SEQUENCES = new long[PUBLISH_HISTORY_SIZE];
    private static final long[] PUBLISH_TIMESTAMPS = new long[PUBLISH_HISTORY_SIZE];
    private static long previousFrameTimestampNanos;
    private static long sequence;
    private static float sampleRateHz;
    private static float qx;
    private static float qy;
    private static float qz;
    private static float qw = 1f;
    private static float vx;
    private static float vy;
    private static float vz;
    private static float confidence;
    private static int discontinuityCount;

    private HeadPoseDebugState() {}

    static void onOutputChanged(AudioOutputIdentity output) {
        synchronized (LOCK) {
            String newKey = output == null ? "" : output.deviceKey;
            if (!outputKey.equals(newKey)) {
                providerId = "";
                error = "";
                frameTimestampNanos = 0;
                packetReceivedTimestampNanos = 0;
                decodedTimestampNanos = 0;
                receivedTimestampNanos = 0;
                publishStartTimestampNanos = 0;
                publishEndTimestampNanos = 0;
                publishedSequence = 0;
                halConsumerSequence = 0;
                halSourcePublishTimestampNanos = 0;
                halReadTimestampNanos = 0;
                halPostTimestampNanos = 0;
                effectCallbackTimestampNanos = 0;
                effectSourceTimestampNanos = 0;
                for (int i = 0; i < PUBLISH_HISTORY_SIZE; i++) {
                    PUBLISH_SEQUENCES[i] = 0;
                    PUBLISH_TIMESTAMPS[i] = 0;
                }
                previousFrameTimestampNanos = 0;
                sequence = 0;
                sampleRateHz = 0f;
                qx = qy = qz = vx = vy = vz = confidence = 0f;
                qw = 1f;
                discontinuityCount = 0;
            }
            outputKey = newKey;
            outputName = output == null ? "" : output.displayName;
        }
    }

    static void onPose(HeadPoseFrame frame, long receivedNanos, long sharedSequence,
            long publishStartNanos, long publishEndNanos,
            long consumerSequence, long consumerReadNanos, long consumerPostNanos) {
        synchronized (LOCK) {
            if (!outputKey.isEmpty() && !outputKey.equals(frame.deviceKey)) return;
            if (previousFrameTimestampNanos > 0
                    && frame.timestampNanos > previousFrameTimestampNanos) {
                float instantaneousRate = 1_000_000_000f
                        / (frame.timestampNanos - previousFrameTimestampNanos);
                if (instantaneousRate >= 1f && instantaneousRate <= 1000f) {
                    sampleRateHz = sampleRateHz == 0f
                            ? instantaneousRate
                            : sampleRateHz * 0.9f + instantaneousRate * 0.1f;
                }
            }
            previousFrameTimestampNanos = frame.timestampNanos;
            frameTimestampNanos = frame.timestampNanos;
            packetReceivedTimestampNanos = frame.packetReceivedTimestampNanos;
            decodedTimestampNanos = frame.decodedTimestampNanos;
            receivedTimestampNanos = receivedNanos;
            publishStartTimestampNanos = publishStartNanos;
            publishEndTimestampNanos = publishEndNanos;
            publishedSequence = sharedSequence;
            if (sharedSequence > 0 && publishEndNanos > 0) {
                int slot = (int) (sharedSequence % PUBLISH_HISTORY_SIZE);
                PUBLISH_SEQUENCES[slot] = sharedSequence;
                PUBLISH_TIMESTAMPS[slot] = publishEndNanos;
            }
            if (consumerSequence > 0 && consumerReadNanos > 0) {
                int slot = (int) (consumerSequence % PUBLISH_HISTORY_SIZE);
                if (PUBLISH_SEQUENCES[slot] == consumerSequence) {
                    halConsumerSequence = consumerSequence;
                    halSourcePublishTimestampNanos = PUBLISH_TIMESTAMPS[slot];
                    halReadTimestampNanos = consumerReadNanos;
                    halPostTimestampNanos = consumerPostNanos;
                }
            }
            providerId = frame.providerId;
            outputKey = frame.deviceKey;
            error = "";
            qx = frame.qx;
            qy = frame.qy;
            qz = frame.qz;
            qw = frame.qw;
            vx = frame.vx;
            vy = frame.vy;
            vz = frame.vz;
            confidence = frame.confidence;
            discontinuityCount = frame.discontinuityCount;
            sequence++;
        }
    }

    /** Records the framework callback emitted after the native effect accepted a new pose. */
    static void onEffectPoseCallback() {
        long callbackNanos = SystemClock.elapsedRealtimeNanos();
        synchronized (LOCK) {
            effectCallbackTimestampNanos = callbackNanos;
            effectSourceTimestampNanos = frameTimestampNanos;
        }
    }

    static void onProviderError(String provider, String deviceKey, String reason) {
        synchronized (LOCK) {
            if (!outputKey.isEmpty() && !outputKey.equals(deviceKey)) return;
            providerId = provider == null ? "" : provider;
            error = reason == null ? "" : reason;
        }
    }

    static void clear() {
        onOutputChanged(null);
    }

    static Snapshot snapshot() {
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        synchronized (LOCK) {
            long ageNanos = receivedTimestampNanos == 0
                    ? Long.MAX_VALUE : Math.max(0, nowNanos - receivedTimestampNanos);
            long transportNanos = frameTimestampNanos == 0 || receivedTimestampNanos == 0
                    ? -1 : Math.max(0, receivedTimestampNanos - frameTimestampNanos);
            return new Snapshot(outputName, outputKey, providerId, error, sequence,
                    ageNanos, transportNanos, sampleRateHz, nowNanos,
                    packetReceivedTimestampNanos, decodedTimestampNanos,
                    receivedTimestampNanos, publishStartTimestampNanos,
                    publishEndTimestampNanos, publishedSequence,
                    halConsumerSequence, halSourcePublishTimestampNanos,
                    halReadTimestampNanos, halPostTimestampNanos,
                    effectCallbackTimestampNanos, effectSourceTimestampNanos,
                    qx, qy, qz, qw, vx, vy, vz, confidence, discontinuityCount);
        }
    }

    static boolean hasRecentFrame() {
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        synchronized (LOCK) {
            return receivedTimestampNanos != 0
                    && nowNanos - receivedTimestampNanos <= RECENT_FRAME_NANOS;
        }
    }

    static final class Snapshot {
        final String outputName;
        final String outputKey;
        final String providerId;
        final String error;
        final long sequence;
        final long ageNanos;
        final long transportNanos;
        final float sampleRateHz;
        final long captureTimestampNanos;
        final long packetReceivedTimestampNanos;
        final long decodedTimestampNanos;
        final long sinkReceivedTimestampNanos;
        final long publishStartTimestampNanos;
        final long publishEndTimestampNanos;
        final long publishedSequence;
        final long halConsumerSequence;
        final long halSourcePublishTimestampNanos;
        final long halReadTimestampNanos;
        final long halPostTimestampNanos;
        final long effectCallbackTimestampNanos;
        final long effectSourceTimestampNanos;
        final float qx;
        final float qy;
        final float qz;
        final float qw;
        final float vx;
        final float vy;
        final float vz;
        final float confidence;
        final int discontinuityCount;

        Snapshot(String outputName, String outputKey, String providerId, String error,
                long sequence, long ageNanos, long transportNanos, float sampleRateHz,
                long captureTimestampNanos, long packetReceivedTimestampNanos,
                long decodedTimestampNanos, long sinkReceivedTimestampNanos,
                long publishStartTimestampNanos, long publishEndTimestampNanos,
                long publishedSequence, long halConsumerSequence,
                long halSourcePublishTimestampNanos, long halReadTimestampNanos,
                long halPostTimestampNanos, long effectCallbackTimestampNanos,
                long effectSourceTimestampNanos,
                float qx, float qy, float qz, float qw,
                float vx, float vy, float vz, float confidence, int discontinuityCount) {
            this.outputName = outputName;
            this.outputKey = outputKey;
            this.providerId = providerId;
            this.error = error;
            this.sequence = sequence;
            this.ageNanos = ageNanos;
            this.transportNanos = transportNanos;
            this.sampleRateHz = sampleRateHz;
            this.captureTimestampNanos = captureTimestampNanos;
            this.packetReceivedTimestampNanos = packetReceivedTimestampNanos;
            this.decodedTimestampNanos = decodedTimestampNanos;
            this.sinkReceivedTimestampNanos = sinkReceivedTimestampNanos;
            this.publishStartTimestampNanos = publishStartTimestampNanos;
            this.publishEndTimestampNanos = publishEndTimestampNanos;
            this.publishedSequence = publishedSequence;
            this.halConsumerSequence = halConsumerSequence;
            this.halSourcePublishTimestampNanos = halSourcePublishTimestampNanos;
            this.halReadTimestampNanos = halReadTimestampNanos;
            this.halPostTimestampNanos = halPostTimestampNanos;
            this.effectCallbackTimestampNanos = effectCallbackTimestampNanos;
            this.effectSourceTimestampNanos = effectSourceTimestampNanos;
            this.qx = qx;
            this.qy = qy;
            this.qz = qz;
            this.qw = qw;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.confidence = confidence;
            this.discontinuityCount = discontinuityCount;
        }

        boolean hasOutput() {
            return !outputKey.isEmpty();
        }

        /** Advance diagnostic age without pretending that polling produced another sensor frame. */
        Snapshot atTime(long nowNanos) {
            long age = ageNanos == Long.MAX_VALUE ? Long.MAX_VALUE
                    : ageNanos + Math.max(0, nowNanos - captureTimestampNanos);
            return new Snapshot(outputName, outputKey, providerId, error, sequence,
                    age, transportNanos, sampleRateHz, nowNanos,
                    packetReceivedTimestampNanos, decodedTimestampNanos, sinkReceivedTimestampNanos,
                    publishStartTimestampNanos, publishEndTimestampNanos, publishedSequence,
                    halConsumerSequence, halSourcePublishTimestampNanos, halReadTimestampNanos,
                    halPostTimestampNanos, effectCallbackTimestampNanos, effectSourceTimestampNanos,
                    qx, qy, qz, qw, vx, vy, vz, confidence, discontinuityCount);
        }

        boolean hasFrame() {
            return sequence > 0;
        }

        boolean isStale() {
            return ageNanos > 750_000_000L;
        }
    }
}
