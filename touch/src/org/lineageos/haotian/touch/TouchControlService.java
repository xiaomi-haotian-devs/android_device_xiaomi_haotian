/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.haotian.touch;

import android.app.ActivityTaskManager;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

/** Applies haotian-specific touch sampling and wake-gesture policy through Xiaomi TouchFeature. */
public final class TouchControlService extends Service {
    private static final String TAG = "HaotianTouchControl";

    private static final String SETTING_SAMPLING_MODE =
            "haotian_touch_sampling_rate_mode";
    private static final String SETTING_FIXED_RATE =
            "haotian_touch_sampling_rate_fixed";
    private static final String SETTING_APP_RATES =
            "haotian_touch_sampling_rate_app_config";
    private static final String SETTING_WATER_PROTECTION =
            "haotian_touch_water_protection";
    private static final String SETTING_GLOVE_RECOGNITION =
            "haotian_touch_glove_recognition";

    private static final int SAMPLING_MODE_AUTOMATIC = 0;
    private static final int SAMPLING_MODE_FIXED = 1;
    private static final int RATE_NORMAL = 135;
    private static final int RATE_HIGH = 240;
    private static final int RATE_MAXIMUM = 300;

    // Xiaomi's THP HAL report-rate command. The Synaptics driver accepts the rate in hertz.
    private static final int TOUCH_MODE_THP_REPORT_RATE = 1011;
    // Xiaomi kernel DATA_MODE_14 / Touch_Doubletap_Mode.
    private static final int TOUCH_MODE_DOUBLE_TAP = 14;
    // Xiaomi TouchFeature cloud controls used by the stock TouchService.
    private static final int TOUCH_MODE_WATER_PROTECTION = 100;
    private static final int TOUCH_MODE_GLOVE_RECOGNITION = 101;
    private static final int WATER_DISABLED = 0;
    private static final int WATER_ENABLED = 1;
    private static final int GLOVE_DISABLED = 0;
    // Stock uses value 2 for automatic glove recognition; both 1 and 2 enable the algorithm.
    private static final int GLOVE_AUTOMATIC = 2;

    private static final long RETRY_DELAY_MS = 2000;
    private static final int BOOT_RETRY_COUNT = 20;
    private static final int EVENT_RETRY_COUNT = 3;
    private static final int USER_ALL = UserHandle.USER_ALL;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final TouchFeatureClient mTouchFeatureClient = new TouchFeatureClient();
    private final ArrayMap<String, Integer> mAppRates = new ArrayMap<>();

    private PowerManager mPowerManager;
    private int mLastAppliedRate = -1;
    private int mLastDoubleTapValue = -1;
    private int mLastWaterProtectionValue = -1;
    private int mLastGloveRecognitionValue = -1;
    private int mRemainingRetries;
    private boolean mForceApply;

    private final Runnable mApplyRunnable = new Runnable() {
        @Override
        public void run() {
            final boolean doubleTapSuccess = applyDoubleTapWake(mForceApply);
            final boolean samplingRateSuccess = applySamplingRate(mForceApply);
            final boolean environmentalSuccess = applyEnvironmentalAdaptation(mForceApply);
            mForceApply = false;
            if (doubleTapSuccess && samplingRateSuccess && environmentalSuccess) {
                mRemainingRetries = 0;
            } else if (mRemainingRetries > 0) {
                mRemainingRetries--;
                mHandler.postDelayed(this, RETRY_DELAY_MS);
            }
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            requestApply(false, EVENT_RETRY_COUNT);
        }
    };

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_SWITCHED.equals(action)
                    || Intent.ACTION_USER_UNLOCKED.equals(action)) {
                requestApply(true, EVENT_RETRY_COUNT);
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Reassert the independent double-tap bit after doze/FOD state transitions.
                mLastDoubleTapValue = -1;
                requestApply(false, EVENT_RETRY_COUNT);
            }
        }
    };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(() -> requestApply(false, EVENT_RETRY_COUNT));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mPowerManager = getSystemService(PowerManager.class);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        registerSettingsObservers();
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException | SecurityException e) {
            Log.e(TAG, "Failed to register task-stack listener", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        requestApply(true, BOOT_RETRY_COUNT);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mApplyRunnable);
        getContentResolver().unregisterContentObserver(mSettingsObserver);
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Failed to unregister task-stack listener", e);
        }
        try {
            unregisterReceiver(mStateReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "State receiver was already unregistered", e);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerSettingsObservers() {
        final ContentResolver resolver = getContentResolver();
        resolver.registerContentObserver(Settings.System.getUriFor(SETTING_SAMPLING_MODE),
                false, mSettingsObserver, USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(SETTING_FIXED_RATE),
                false, mSettingsObserver, USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(SETTING_APP_RATES),
                false, mSettingsObserver, USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(SETTING_WATER_PROTECTION),
                false, mSettingsObserver, USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(SETTING_GLOVE_RECOGNITION),
                false, mSettingsObserver, USER_ALL);
        resolver.registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.DOUBLE_TAP_TO_WAKE), false, mSettingsObserver, USER_ALL);
    }

    private void requestApply(boolean force, int retryCount) {
        mForceApply |= force;
        mRemainingRetries = Math.max(mRemainingRetries, retryCount);
        mHandler.removeCallbacks(mApplyRunnable);
        mHandler.post(mApplyRunnable);
    }

    private boolean applyDoubleTapWake(boolean force) {
        final int value = Settings.Secure.getIntForUser(getContentResolver(),
                Settings.Secure.DOUBLE_TAP_TO_WAKE, 0, UserHandle.USER_CURRENT) != 0 ? 1 : 0;
        if (!force && value == mLastDoubleTapValue) return true;

        if (!mTouchFeatureClient.setModeValue(TOUCH_MODE_DOUBLE_TAP, value)) return false;
        mLastDoubleTapValue = value;
        Log.i(TAG, "Applied double-tap wake=" + value);
        return true;
    }

    private boolean applySamplingRate(boolean force) {
        // The panel driver rejects the report-rate command outside PWR_ON. SCREEN_ON will retry.
        if (mPowerManager != null && !mPowerManager.isInteractive()) return true;

        final int rate = getDesiredSamplingRate();
        if (!force && rate == mLastAppliedRate) return true;

        if (!mTouchFeatureClient.setModeValue(TOUCH_MODE_THP_REPORT_RATE, rate)) return false;
        mLastAppliedRate = rate;
        Log.i(TAG, "Applied touch sampling rate=" + rate + " Hz");
        return true;
    }

    private boolean applyEnvironmentalAdaptation(boolean force) {
        final ContentResolver resolver = getContentResolver();
        final int waterValue = Settings.System.getIntForUser(resolver,
                SETTING_WATER_PROTECTION, 1, UserHandle.USER_CURRENT) != 0
                ? WATER_ENABLED : WATER_DISABLED;
        final int gloveValue = Settings.System.getIntForUser(resolver,
                SETTING_GLOVE_RECOGNITION, 1, UserHandle.USER_CURRENT) != 0
                ? GLOVE_AUTOMATIC : GLOVE_DISABLED;

        boolean waterSuccess = true;
        if (force || waterValue != mLastWaterProtectionValue) {
            waterSuccess = mTouchFeatureClient.setModeValue(
                    TOUCH_MODE_WATER_PROTECTION, waterValue);
            if (waterSuccess) {
                mLastWaterProtectionValue = waterValue;
                Log.i(TAG, "Applied water-mistouch protection=" + waterValue);
            }
        }

        boolean gloveSuccess = true;
        if (force || gloveValue != mLastGloveRecognitionValue) {
            gloveSuccess = mTouchFeatureClient.setModeValue(
                    TOUCH_MODE_GLOVE_RECOGNITION, gloveValue);
            if (gloveSuccess) {
                mLastGloveRecognitionValue = gloveValue;
                Log.i(TAG, "Applied automatic glove recognition=" + gloveValue);
            }
        }
        return waterSuccess && gloveSuccess;
    }

    private int getDesiredSamplingRate() {
        final int mode = Settings.System.getIntForUser(getContentResolver(),
                SETTING_SAMPLING_MODE, SAMPLING_MODE_AUTOMATIC, UserHandle.USER_CURRENT);
        if (mode == SAMPLING_MODE_FIXED) {
            return sanitizeRate(Settings.System.getIntForUser(getContentResolver(),
                    SETTING_FIXED_RATE, RATE_NORMAL, UserHandle.USER_CURRENT));
        }

        readAppRates();
        final String foregroundPackage = getForegroundPackage();
        if (TextUtils.isEmpty(foregroundPackage)) return RATE_NORMAL;
        return mAppRates.getOrDefault(foregroundPackage, RATE_NORMAL);
    }

    private String getForegroundPackage() {
        try {
            final ActivityTaskManager.RootTaskInfo task =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return task != null && task.topActivity != null
                    ? task.topActivity.getPackageName() : null;
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Unable to query foreground package; using normal rate", e);
            return null;
        }
    }

    private void readAppRates() {
        mAppRates.clear();
        final String encoded = Settings.System.getStringForUser(getContentResolver(),
                SETTING_APP_RATES, UserHandle.USER_CURRENT);
        if (TextUtils.isEmpty(encoded)) return;

        for (String item : encoded.split(";")) {
            final String[] fields = item.split(",");
            if (fields.length != 2 || TextUtils.isEmpty(fields[0])) continue;
            try {
                final int rate = sanitizeRate(Integer.parseInt(fields[1]));
                if (rate != RATE_NORMAL) mAppRates.put(fields[0], rate);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static int sanitizeRate(int rate) {
        return rate == RATE_HIGH || rate == RATE_MAXIMUM ? rate : RATE_NORMAL;
    }
}
