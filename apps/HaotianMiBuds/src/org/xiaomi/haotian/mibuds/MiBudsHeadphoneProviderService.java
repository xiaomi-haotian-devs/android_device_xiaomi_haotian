/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import org.xiaomi.haotian.audio.provider.HeadPoseRingWriter;
import org.xiaomi.haotian.audio.provider.HeadphoneOutput;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProvider;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProviderCallback;

/** Signature-protected generic pose provider consumed by HaotianAudio. */
public final class MiBudsHeadphoneProviderService extends Service {
    private MiBudsController controller() {
        return ((HaotianMiBudsApplication) getApplication()).controller();
    }

    private final IHeadphonePoseProvider.Stub binder = new IHeadphonePoseProvider.Stub() {
        @Override
        public int getApiVersion() {
            return 1;
        }

        @Override
        public String getProviderId() {
            return MiBudsController.PROVIDER_ID;
        }

        @Override
        public boolean supports(HeadphoneOutput output) {
            return controller().supports(output);
        }

        @Override
        public boolean start(HeadphoneOutput output, ParcelFileDescriptor poseBuffer,
                IHeadphonePoseProviderCallback callback) {
            HeadPoseRingWriter writer = HeadPoseRingWriter.open(poseBuffer);
            return controller().attachProvider(output, writer, callback);
        }

        @Override
        public void stop() {
            controller().detachProvider();
        }

        @Override
        public void setPoseStreamingEnabled(boolean enabled) {
            controller().setPoseStreamingEnabled(enabled);
        }

        @Override
        public boolean recenter() {
            return controller().recenter();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Binder clients can disappear without first issuing stop(). Always drop
        // the shared-memory writer and the earbud's high-rate pose request.
        controller().detachProvider();
        return false;
    }
}
