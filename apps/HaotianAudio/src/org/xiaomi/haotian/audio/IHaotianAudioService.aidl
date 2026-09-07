/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.os.IBinder;
import org.xiaomi.haotian.audio.AudioDeviceProfile;

/** Signature-only control plane. The service is the sole owner of vendor audio effects. */
interface IHaotianAudioService {
    int getApiVersion();
    String getActiveDeviceKey();
    AudioDeviceProfile getActiveProfile();
    AudioDeviceProfile getProfile(String deviceKey);
    void saveProfile(in AudioDeviceProfile profile, boolean applyIfActive);
    void requestApply();
    boolean areEffectsAvailable();
    boolean isHeadTrackerAvailable();

    // Restricted compatibility surface for the patched stock MiSound workflows.
    void setSoundIdProfile(String deviceKey, String profileId, in float[] gains, boolean enabled);
    void setHearingProfile(String deviceKey, in float[] leftGains, in float[] rightGains,
            boolean enabled);
    void setEarScanProfile(String deviceKey, in float[] filterValues, boolean enabled);
    void setHeadsetModel(String deviceKey, int modelId, String modelName);

    // Route-scoped attenuation in the playback chain. The request is removed if its owner dies.
    // Keep extension methods appended so transaction ids of the compatibility surface stay stable.
    void requestTransientMediaDuck(IBinder ownerToken, String deviceKey, float volume);
    void releaseTransientMediaDuck(IBinder ownerToken);
}
