/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

/**
 * Low-rate framework availability boundary for settings and effect ownership.
 *
 * Bluetooth protocol providers feed the device Sensors sub-HAL, which exposes a dynamic
 * TYPE_HEAD_TRACKER associated with the active audio device. Settings only consumes framework
 * availability and deliberately does not transport pose samples through this interface.
 */
public interface HeadTrackingProvider {
    boolean isTrackerAvailable();

    default void onActiveAudioDeviceChanged() {
    }
}
