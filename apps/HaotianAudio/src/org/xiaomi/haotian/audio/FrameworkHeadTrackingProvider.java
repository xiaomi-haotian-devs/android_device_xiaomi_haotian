/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.media.Spatializer;

final class FrameworkHeadTrackingProvider implements HeadTrackingProvider {
    private final Spatializer spatializer;

    FrameworkHeadTrackingProvider(Spatializer spatializer) {
        this.spatializer = spatializer;
    }

    @Override
    public boolean isTrackerAvailable() {
        try {
            return spatializer != null && spatializer.isHeadTrackerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
