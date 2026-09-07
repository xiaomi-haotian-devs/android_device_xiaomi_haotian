/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import org.xiaomi.haotian.audio.HeadPoseFrame;

/** Internal transport contract. A single instance owns the AirPods AACP session. */
interface NormalizedHeadPoseProvider {
    final class WearState {
        static final WearState UNKNOWN = new WearState(false, false, false);

        final boolean known;
        final boolean leftWorn;
        final boolean rightWorn;

        WearState(boolean known, boolean leftWorn, boolean rightWorn) {
            this.known = known;
            this.leftWorn = leftWorn;
            this.rightWorn = rightWorn;
        }
    }

    interface Sink {
        void onPose(HeadPoseFrame frame);
        void onProviderError(String providerId, String deviceKey, String reason);
        default void onWearStateChanged(WearState state) {
        }
    }

    String getProviderId();
    boolean supports(AudioOutputIdentity output);
    void start(AudioOutputIdentity output, Sink sink);
    void stop();
    void setPoseStreamingEnabled(boolean enabled);
    default boolean recenter() {
        return false;
    }
    default void close() {
        stop();
    }
}
