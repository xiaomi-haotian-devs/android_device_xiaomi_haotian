/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio.provider;

/** Low-rate events only. Pose samples are written to the shared-memory ring. */
oneway interface IHeadphonePoseProviderCallback {
    void onWearStateChanged(String deviceKey, int leftState, int rightState,
            long timestampNanos);
    void onProviderError(String deviceKey, String reason);
}
