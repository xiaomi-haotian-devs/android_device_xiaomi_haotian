/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import java.util.ArrayDeque;

/**
 * Detects LibrePods-style nod/shake gestures from Android head-tracker rotation vectors.
 *
 * <p>The platform sensor reports an axis-angle vector in radians. Rotation around the right-ear
 * axis is a nod; rotation around the top-of-head axis is a shake.</p>
 */
final class HeadGestureDetector {
    interface Listener {
        void onGesture(boolean accepted);
    }

    private static final long MAX_SAMPLE_GAP_NS = 350_000_000L;
    private static final long EXTREME_WINDOW_NS = 2_200_000_000L;
    private static final long MIN_GESTURE_DURATION_NS = 180_000_000L;
    private static final float SMOOTHING_ALPHA = 0.45f;
    private static final float DIRECTION_CHANGE_RADIANS = 0.003f;
    private static final float EXTREME_RADIANS = 0.12f;
    private static final float MIN_AVERAGE_AMPLITUDE_RADIANS = 0.15f;
    private static final int REQUIRED_EXTREMES = 3;

    private final Listener listener;
    private final AxisHistory nod = new AxisHistory();
    private final AxisHistory shake = new AxisHistory();
    private long lastTimestampNanos;
    private int discontinuity = -1;
    private boolean initialized;
    private boolean detected;

    HeadGestureDetector(Listener listener) {
        this.listener = listener;
    }

    void accept(long timestampNanos, float[] values) {
        if (detected || values == null || values.length < 3 || timestampNanos <= 0) return;
        int reportedDiscontinuity = values.length > 6 ? Math.round(values[6]) : 0;
        if (lastTimestampNanos != 0
                && (timestampNanos <= lastTimestampNanos
                        || timestampNanos - lastTimestampNanos > MAX_SAMPLE_GAP_NS)) {
            resetHistories();
        }
        if (discontinuity >= 0 && reportedDiscontinuity != discontinuity) {
            resetHistories();
        }
        discontinuity = reportedDiscontinuity;
        lastTimestampNanos = timestampNanos;

        float nodValue = values[0];
        float shakeValue = values[2];
        if (!Float.isFinite(nodValue) || !Float.isFinite(shakeValue)
                || Math.abs(nodValue) > Math.PI || Math.abs(shakeValue) > Math.PI) {
            return;
        }
        if (!initialized) {
            nod.initialize(nodValue);
            shake.initialize(shakeValue);
            initialized = true;
            return;
        }
        nod.accept(nodValue, timestampNanos);
        shake.accept(shakeValue, timestampNanos);

        boolean nodDetected = nod.isGesture(timestampNanos)
                && nod.averageAmplitude() > shake.recentAmplitude() * 1.15f;
        boolean shakeDetected = shake.isGesture(timestampNanos)
                && shake.averageAmplitude() > nod.recentAmplitude() * 1.15f;
        if (!nodDetected && !shakeDetected) return;
        detected = true;
        listener.onGesture(nodDetected);
    }

    private void resetHistories() {
        nod.clear();
        shake.clear();
        initialized = false;
    }

    private static final class AxisHistory {
        private final ArrayDeque<Extreme> extremes = new ArrayDeque<>();
        private float filtered;
        private float previous;
        private float recentAmplitude;
        private int direction;

        void initialize(float value) {
            filtered = value;
            previous = value;
            recentAmplitude = Math.abs(value);
            direction = 0;
            extremes.clear();
        }

        void accept(float value, long timestampNanos) {
            filtered += SMOOTHING_ALPHA * (value - filtered);
            recentAmplitude = Math.max(Math.abs(filtered), recentAmplitude * 0.94f);
            float delta = filtered - previous;
            int newDirection = delta > DIRECTION_CHANGE_RADIANS ? 1
                    : delta < -DIRECTION_CHANGE_RADIANS ? -1 : direction;
            if (direction != 0 && newDirection != direction
                    && Math.abs(previous) >= EXTREME_RADIANS) {
                extremes.addLast(new Extreme(previous, timestampNanos));
            }
            direction = newDirection;
            previous = filtered;
            prune(timestampNanos);
        }

        boolean isGesture(long nowNanos) {
            prune(nowNanos);
            if (extremes.size() < REQUIRED_EXTREMES) return false;
            Extreme[] values = extremes.toArray(new Extreme[0]);
            int start = values.length - REQUIRED_EXTREMES;
            if (values[values.length - 1].timestampNanos
                    - values[start].timestampNanos < MIN_GESTURE_DURATION_NS) {
                return false;
            }
            for (int i = start + 1; i < values.length; i++) {
                if (Math.signum(values[i - 1].value) == Math.signum(values[i].value)) {
                    return false;
                }
            }
            return averageAmplitude() >= MIN_AVERAGE_AMPLITUDE_RADIANS;
        }

        float averageAmplitude() {
            if (extremes.isEmpty()) return 0f;
            float sum = 0f;
            int count = 0;
            for (Extreme extreme : extremes) {
                sum += Math.abs(extreme.value);
                count++;
            }
            return sum / count;
        }

        float recentAmplitude() {
            return recentAmplitude;
        }

        void clear() {
            extremes.clear();
            filtered = 0f;
            previous = 0f;
            recentAmplitude = 0f;
            direction = 0;
        }

        private void prune(long nowNanos) {
            while (!extremes.isEmpty()
                    && nowNanos - extremes.peekFirst().timestampNanos > EXTREME_WINDOW_NS) {
                extremes.removeFirst();
            }
        }
    }

    private static final class Extreme {
        final float value;
        final long timestampNanos;

        Extreme(float value, long timestampNanos) {
            this.value = value;
            this.timestampNanos = timestampNanos;
        }
    }
}
