/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.media;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.ref.WeakReference;

import org.xiaomi.haotian.camera.audio.OzoController;

/**
 * Haotian-only compatibility implementation of Xiaomi's misspelled
 * AudioParaManger API. Keep this surface binary-compatible with MiuiCamera;
 * all device-specific implementation details live outside android.media.
 */
public final class AudioParaManger {
    private static final String TAG = "HaotianOzoAudio";
    private static final String CAMERA_PACKAGE = "com.android.camera";

    public static final int NONE = 0;

    private final Context mContext;
    private final OzoController mController;

    private int[] mCurrentAudioConfig = {1, 0, 0xC, 0, 2, 1};
    private int mRecordType = 1;
    private boolean mWindNoise;
    private int mMaxVisual = 6;

    public AudioParaManger(MediaRecorder recorder, Context context) {
        mContext = context;
        mController = new OzoController(
                context.getSystemService(AudioManager.class),
                new WeakReference<>(recorder), null);
    }

    public AudioParaManger(AudioRecord recorder, Context context) {
        mContext = context;
        mController = new OzoController(
                context.getSystemService(AudioManager.class), null,
                new WeakReference<>(recorder));
    }

    public static boolean isAudioZoomSupported(Context context) {
        return context != null
                && CAMERA_PACKAGE.equalsIgnoreCase(context.getPackageName())
                && SystemProperties.getBoolean("ro.vendor.audio.zoom.support", false)
                && SystemProperties.getInt("ro.vendor.audio.zoom.type", 0) == 2;
    }

    public boolean init(int recType, int shot, int scene, double level, double azimuth,
            double elevation, double width, double height, boolean windNoise) {
        if (!isAudioZoomSupported(mContext)) {
            Log.w(TAG, "Rejecting OZO initialization outside Xiaomi Camera");
            return false;
        }

        mRecordType = recType;
        mWindNoise = windNoise;
        mCurrentAudioConfig = loadAudioConfig(recType, windNoise);
        mController.configure(recType, shot, scene, level, azimuth, elevation, width,
                height, windNoise, mMaxVisual, mCurrentAudioConfig[1]);
        return true;
    }

    public void createAudioObject(EventListener listener) {
        mController.createAudioSession(listener, null);
    }

    public void createAudioObject(EventListener listener, TuneListener tuneListener) {
        mController.createAudioSession(listener, tuneListener);
    }

    public void createOzo(EventListener listener) {
        mController.createOzoSession(listener, null);
    }

    public void createOzo(EventListener listener, TuneListener tuneListener) {
        mController.createOzoSession(listener, tuneListener);
    }

    public boolean createAudioEffects(int sessionId) {
        return mController.createAudioEffect(sessionId);
    }

    // Xiaomi kept this typo in the public ABI; MiuiCamera reflects this name.
    public boolean creatOzoEffect(int sessionId) {
        return mController.createOzoEffect(sessionId);
    }

    public void prepare() {
        mController.prepare();
    }

    public void start() {
        // HyperOS only reports MQS telemetry here; recording is already active.
    }

    public void releaseAudioEffect() {
        mController.release();
    }

    public void releaseOzoEffect() {
        mController.release();
    }

    public int[] getAudioConfig() {
        return mCurrentAudioConfig.clone();
    }

    public float getAudioLatency() {
        // Two 1024-sample OZO frames at 48 kHz, matching the stock controller.
        return 42.666668f;
    }

    public boolean isUnite() {
        return SystemProperties.getInt("ro.vendor.audio.unite.record.type", 0) == 1;
    }

    public void setMaxSupportLevel(int level) {
        mMaxVisual = Math.max(1, Math.min(level, 6));
        mController.setMaxVisual(mMaxVisual);
    }

    public void setRecordType(int recType) {
        mRecordType = recType;
        mController.setRecordType(recType);
    }

    public void setAudioWindNoise(boolean enabled) {
        mWindNoise = enabled;
        mController.setWindNoise(enabled);
    }

    public void setUserMode(boolean enabled) {
        mController.setUserMode(enabled);
    }

    public void setUserGain(double level) {
        mController.setUserGain(level);
    }

    public void setAudioZoom(int beamIndex, boolean enabled) {
        mController.setZoomEnabled(beamIndex, enabled);
    }

    public void setAudioZoomLevel(double level) {
        mController.setZoomLevel(level);
    }

    // These one-argument methods are part of the v3 probe but are no-ops in
    // the corresponding HyperOS Nokia implementation.
    public void setAudioFocusAzimuth(double azimuth) {
    }

    public void setAudioFocusElevation(double elevation) {
    }

    public void setAudioFocusAzimuth(double azimuth, int zoomType) {
        mController.setFocusAzimuth(azimuth, zoomType);
    }

    public void setAudioFocusElevation(double elevation, int zoomType) {
        mController.setFocusElevation(elevation, zoomType);
    }

    public void setAudioFocusWidth(double width) {
        mController.setFocusWidth(width);
    }

    public void setAudioFocusHeight(double height) {
        mController.setFocusHeight(height);
    }

    public int setFocusRegion(int left, int top, int right, int bottom) {
        return -1;
    }

    public int setFocusRegion(int left, int top, int right, int bottom, int zoomType) {
        return -1;
    }

    public int setViewRegion(int left, int top, int right, int bottom) {
        return -1;
    }

    public int setViewRegion(int left, int top, int right, int bottom, int zoomType) {
        return -1;
    }

    public int setSensorAngleRange(int angle) {
        return -1;
    }

    private static int[] loadAudioConfig(int recType, boolean windNoise) {
        final String property;
        final String fallback;
        switch (recType) {
            case 0:
                property = "ro.vendor.audio.special.video.frontfacingrecord.type";
                fallback = "2,C,C,1,1,1";
                break;
            case 1:
                if (windNoise) {
                    property = "persist.vendor.audio.special.video.wnd_ns.type";
                    fallback = "3,C,0,1,1,2";
                } else {
                    property = "ro.vendor.audio.general.video.type";
                    fallback = "0,C,0,2,1,3";
                }
                break;
            case 3:
                property = "ro.vendor.audio.special.video.spatialsound.type";
                fallback = "2,C,C,1,1,1";
                break;
            case 2:
            case 4:
            case 5:
            case 6:
            default:
                property = "ro.vendor.audio.special.video.zoom.type";
                fallback = "2,C,C,1,1,1";
                break;
        }

        String[] values = SystemProperties.get(property, fallback).split(",");
        String[] defaults = fallback.split(",");
        int[] result = new int[6];
        result[0] = recType;
        for (int i = 0; i < 5; i++) {
            String value = i < values.length ? values[i] : defaults[i];
            try {
                result[i + 1] = Integer.parseInt(value, 16);
            } catch (NumberFormatException e) {
                result[i + 1] = Integer.parseInt(defaults[i], 16);
            }
        }
        Log.d(TAG, "Audio config: function=" + result[0] + " provider=" + result[1]
                + " channelMask=0x" + Integer.toHexString(result[2])
                + " indexMask=0x" + Integer.toHexString(result[3])
                + " mode=" + result[4] + " bits=" + result[5]);
        return result;
    }

    public interface EventListener {
        default void onAudioLevel(int left, int right) {
        }

        void onMicBlocking(int micIndex, int level);
    }

    public interface TuneListener {
        void onTuneAudioData(byte[] data);

        void onTuneCtrlData(byte[] data);
    }
}
