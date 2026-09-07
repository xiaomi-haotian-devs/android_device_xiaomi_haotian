/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

/** Adapter implemented once for each supported Bluetooth headphone pose protocol. */
public interface NormalizedHeadPoseProvider {
    /**
     * Normalized in-ear state supplied by a headphone-specific transport.
     *
     * <p>An unknown state deliberately permits tracking. This keeps providers without wear
     * detection compatible, while a provider can suspend pose streaming as soon as either ear is
     * known to be out.</p>
     */
    final class WearState {
        public static final WearState UNKNOWN = new WearState(false, false, false);

        public final boolean known;
        public final boolean leftWorn;
        public final boolean rightWorn;

        public WearState(boolean known, boolean leftWorn, boolean rightWorn) {
            this.known = known;
            this.leftWorn = leftWorn;
            this.rightWorn = rightWorn;
        }

        public boolean permitsHeadTracking() {
            return !known || (leftWorn && rightWorn);
        }
    }

    interface Sink {
        /** Must return quickly; implementations should forward into a non-Binder queue. */
        void onPose(HeadPoseFrame frame);

        void onProviderError(String providerId, String deviceKey, String reason);

        /** Low-rate route metadata; no pose samples are transported by this callback. */
        default void onOutputChanged(AudioOutputIdentity output) {
        }

        /** Low-rate wear state. Pose providers must not synthesize an unknown state as removed. */
        default void onWearStateChanged(WearState state) {
        }
    }

    String getProviderId();

    boolean supports(AudioOutputIdentity output);

    void start(AudioOutputIdentity output, Sink sink);

    void stop();

    /**
     * Starts or stops high-rate pose production without tearing down a future low-rate wear
     * monitor. A separately packaged companion may keep its accessory transport alive for local
     * features while this switch disables high-rate pose production for spatial audio.
     */
    void setPoseStreamingEnabled(boolean enabled);

    /** Permanently releases provider threads when the owning service is destroyed. */
    default void close() {
        stop();
    }

    /** Re-establishes the current physical direction as the provider's forward reference. */
    default boolean recenter() {
        return false;
    }
}
