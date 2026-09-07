/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio.provider;

import android.os.ParcelFileDescriptor;
import org.xiaomi.haotian.audio.provider.HeadphoneOutput;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProviderCallback;

/** Vendor-neutral bridge implemented by a privileged headphone companion. */
interface IHeadphonePoseProvider {
    int getApiVersion();
    String getProviderId();
    boolean supports(in HeadphoneOutput output);
    boolean start(in HeadphoneOutput output, in ParcelFileDescriptor poseBuffer,
            IHeadphonePoseProviderCallback callback);
    void stop();
    void setPoseStreamingEnabled(boolean enabled);
    boolean recenter();
}
