/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import org.xiaomi.haotian.audio.provider.HeadPoseRingWriter;
import org.xiaomi.haotian.audio.provider.HeadphoneOutput;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProvider;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProviderCallback;

/** Generic pose/wear provider consumed by HaotianAudio. */
public final class AirPodsHeadphoneProviderService extends Service {
    public static final String ACTION_PROVIDER =
            "org.xiaomi.haotian.audio.action.HEADPHONE_POSE_PROVIDER";

    private final IHeadphonePoseProvider.Stub binder = new IHeadphonePoseProvider.Stub() {
        private AirPodsController controller() {
            return ((HaotianAirPodsApplication) getApplication()).getController();
        }

        @Override
        public int getApiVersion() {
            return 1;
        }

        @Override
        public String getProviderId() {
            return "airpods-pro3-aacp";
        }

        @Override
        public boolean supports(HeadphoneOutput output) {
            return controller().supports(output);
        }

        @Override
        public boolean start(HeadphoneOutput output, ParcelFileDescriptor poseBuffer,
                IHeadphonePoseProviderCallback callback) {
            HeadPoseRingWriter writer = HeadPoseRingWriter.open(poseBuffer);
            return controller().attachAudio(output, writer, callback);
        }

        @Override
        public void stop() {
            controller().detachAudio();
        }

        @Override
        public void setPoseStreamingEnabled(boolean enabled) {
            controller().setAudioPoseStreamingEnabled(enabled);
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
        ((HaotianAirPodsApplication) getApplication()).getController().detachAudio();
        return false;
    }
}
