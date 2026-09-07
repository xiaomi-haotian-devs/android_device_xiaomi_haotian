/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import org.xiaomi.haotian.audio.HeadPoseFrame;

/** Recognizes nod/shake call gestures from the process-wide AirPods pose stream. */
public final class HeadGestureService extends Service {
    static final String ACTION_START = "org.xiaomi.haotian.airpods.action.START_HEAD_GESTURES";
    private static final String NOTIFICATION_CHANNEL = "airpods_head_gestures";
    private static final int NOTIFICATION_ID = 0x48475033; // "HGP3"
    private AirPodsController controller;
    private boolean poseRegistered;
    private boolean handled;
    private HeadGestureDetector detector;
    private final AirPodsController.PoseListener poseListener = this::onPose;

    @Override
    public void onCreate() {
        super.onCreate();
        detector = new HeadGestureDetector(this::handleGesture);
        controller = ((HaotianAirPodsApplication) getApplication()).getController();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        if (intent == null || !ACTION_START.equals(intent.getAction()) || !isRinging()
                || !getPreferences().getBoolean(HeadGestureReceiver.PREF_ENABLED, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        requestPoseStream();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releasePoseStream();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void requestPoseStream() {
        if (controller == null || !isRinging()) {
            stopSelf();
            return;
        }
        if (!controller.addPoseListener(poseListener)) {
            stopSelf();
            return;
        }
        poseRegistered = true;
    }

    private void releasePoseStream() {
        if (!poseRegistered || controller == null) return;
        controller.removePoseListener(poseListener);
        poseRegistered = false;
    }

    private void onPose(HeadPoseFrame frame) {
        float x = -frame.qx;
        float y = -frame.qy;
        float z = -frame.qz;
        float w = frame.qw;
        if (w < 0f) {
            x = -x;
            y = -y;
            z = -z;
            w = -w;
        }
        float sinHalf = (float) Math.sqrt(x * x + y * y + z * z);
        float angle = 2f * (float) Math.atan2(sinHalf, Math.max(0f, w));
        float scale = sinHalf > 1.0e-7f ? angle / sinHalf : 2f;
        detector.accept(frame.timestampNanos,
                new float[] {x * scale, y * scale, z * scale, 0f, 0f, 0f,
                        frame.discontinuityCount & 0xff});
    }

    @SuppressWarnings("deprecation")
    private void handleGesture(boolean accepted) {
        if (handled || !isRinging()
                || checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                        != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        handled = true;
        TelecomManager telecom = getSystemService(TelecomManager.class);
        if (telecom != null) {
            if (accepted) {
                telecom.acceptRingingCall();
            } else {
                telecom.endCall();
            }
            Toast.makeText(this, accepted ? R.string.call_answered_by_gesture
                    : R.string.call_rejected_by_gesture, Toast.LENGTH_SHORT).show();
        }
        stopSelf();
    }

    @SuppressWarnings("deprecation")
    private boolean isRinging() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        TelephonyManager telephony = getSystemService(TelephonyManager.class);
        return telephony != null && telephony.getCallState()
                == TelephonyManager.CALL_STATE_RINGING;
    }

    private android.content.SharedPreferences getPreferences() {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
    }

    private Notification createNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL,
                    getString(R.string.head_gestures), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, AirPodsSettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_airpods)
                .setContentTitle(getText(R.string.head_gestures))
                .setContentText(getText(R.string.head_gestures_listening))
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .build();
    }
}
