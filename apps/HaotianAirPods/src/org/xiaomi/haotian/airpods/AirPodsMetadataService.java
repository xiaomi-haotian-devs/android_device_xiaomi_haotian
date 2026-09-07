/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-FileCopyrightText: LibrePods contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.xiaomi.haotian.airpods;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Publishes standard Android Bluetooth metadata from the local AACP session. */
public final class AirPodsMetadataService extends JobService {
    private static final String TAG = "HaotianAirPods";
    private static final int METADATA_JOB_ID = 0x48415033; // "HAP3"
    // BluetoothDevice.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS is a hidden module API and is not
    // present in the platform classpath used by this app. Keep this in sync with
    // BluetoothDevice and SettingsLib's Fast Pair metadata key.
    private static final int METADATA_FAST_PAIR_CUSTOMIZED_FIELDS = 25;
    private static final ComponentName AUDIO_SERVICE = new ComponentName(
            "org.xiaomi.haotian.airpods",
            "org.xiaomi.haotian.airpods.AirPodsControlService");
    private static final String[] ICON_READERS = {
            "com.android.settings", "com.android.systemui", "com.android.bluetooth",
            "com.google.android.bluetooth", "com.google.android.settings.intelligence",
            "com.android.settings.intelligence"
    };
    private static final String[] LEGACY_DEVICE_SETTINGS_TAGS = {
            "DEVICE_SETTINGS_CONFIG_PACKAGE_NAME",
            "DEVICE_SETTINGS_CONFIG_CLASS",
            "DEVICE_SETTINGS_CONFIG_ACTION"
    };

    private IAirPodsService audioService;
    private boolean bound;
    private JobParameters pendingJob;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> finishJob(false);
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            audioService = IAirPodsService.Stub.asInterface(binder);
            refreshMetadata();
            finishJob(false);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            audioService = null;
            finishJob(false);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            audioService = null;
            finishJob(false);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            finishJob(false);
        }
    };

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(METADATA_JOB_ID,
                new ComponentName(context, AirPodsMetadataService.class))
                .setMinimumLatency(0)
                .setOverrideDeadline(1_000)
                .build();
        if (scheduler.schedule(job) == JobScheduler.RESULT_FAILURE) {
            Log.w(TAG, "Could not schedule AirPods metadata refresh");
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        pendingJob = params;
        handler.removeCallbacks(timeout);
        if (audioService != null) {
            refreshMetadata();
            pendingJob = null;
            return false;
        }
        Intent intent = new Intent().setComponent(AUDIO_SERVICE);
        if (!bound) {
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
        if (!bound) {
            pendingJob = null;
            return false;
        }
        handler.postDelayed(timeout, 5_000);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        handler.removeCallbacks(timeout);
        pendingJob = null;
        disconnect();
        return false;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        disconnect();
        super.onDestroy();
    }

    private void finishJob(boolean reschedule) {
        handler.removeCallbacks(timeout);
        JobParameters job = pendingJob;
        pendingJob = null;
        disconnect();
        if (job != null) jobFinished(job, reschedule);
    }

    private void disconnect() {
        if (bound) unbindService(connection);
        bound = false;
        audioService = null;
    }

    private void refreshMetadata() {
        IAirPodsService service = audioService;
        if (service == null) return;
        try {
            AirPodsState state = service.getAirPodsState();
            if (state == null) state = new AirPodsState();
            AirPodsNearbyState nearby = HaotianAirPodsApplication.getNearbyState();
            String deviceAddress = state.deviceAddress;
            if (deviceAddress.isEmpty() && nearby != null) {
                deviceAddress = nearby.classicAddress;
            }
            if (deviceAddress.isEmpty()) {
                deviceAddress = new AirPodsProximityKeyStore(this).getActiveDevice();
            }
            if (deviceAddress.isEmpty()) return;
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return;
            BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
            if (!state.modelNumber.isEmpty() || state.connected) publishIdentity(device, state);
            if (state.connected) {
                publishBattery(device, state);
            } else if (nearby != null
                    && deviceAddress.equalsIgnoreCase(nearby.classicAddress)) {
                publishBattery(device, nearby);
            } else {
                clearBattery(device);
            }
        } catch (Exception exception) {
            Log.w(TAG, "Could not publish AirPods metadata", exception);
        }
    }

    private void publishIdentity(BluetoothDevice device, AirPodsState state) throws IOException {
        // BtHelper and the Fast Pair provider leave their device-details integration metadata in
        // the Bluetooth database after the package has been removed.  Keeping those endpoints
        // makes Settings intermittently replace the local profile controls with a stale remote
        // layout, depending on whether the provider replies before the page finishes loading.
        // HaotianAirPods owns its entry directly in Settings, so explicitly retire the old
        // companion/slice/config-provider endpoints while preserving the useful identity and
        // battery metadata below.
        clearMetadata(device, BluetoothDevice.METADATA_COMPANION_APP);
        clearMetadata(device, BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI);
        removeLegacyDeviceSettingsMetadata(device);
        setString(device, BluetoothDevice.METADATA_MANUFACTURER_NAME,
                state.manufacturer.isEmpty() ? "Apple" : state.manufacturer);
        setString(device, BluetoothDevice.METADATA_MODEL_NAME, "AirPods Pro 3");
        device.setMetadata(BluetoothDevice.METADATA_DEVICE_TYPE,
                BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.getBytes(StandardCharsets.UTF_8));
        setString(device, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, "true");
        setString(device, BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD, "20");
        setString(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD, "20");
        setString(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD, "20");
        setString(device, BluetoothDevice.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD, "20");
        setUri(device, BluetoothDevice.METADATA_MAIN_ICON, iconUri(R.drawable.AirPods_Pro));
        setUri(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON,
                iconUri(R.drawable.AirPods_Pro_Left));
        setUri(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_ICON,
                iconUri(R.drawable.AirPods_Pro_Right));
        setUri(device, BluetoothDevice.METADATA_UNTETHERED_CASE_ICON,
                iconUri(R.drawable.AirPods_Pro_Case));
    }

    private static void publishBattery(BluetoothDevice device, AirPodsState state) {
        setInt(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY, state.leftBattery);
        setInt(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY, state.rightBattery);
        setInt(device, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY, state.caseBattery);
        setCharging(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING,
                state.leftBattery, state.leftBatteryStatus);
        setCharging(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING,
                state.rightBattery, state.rightBatteryStatus);
        setCharging(device, BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING,
                state.caseBattery, state.caseBatteryStatus);
        int main = minimumKnown(state.leftBattery, state.rightBattery);
        setInt(device, BluetoothDevice.METADATA_MAIN_BATTERY, main);
        if (main < 0) {
            clearMetadata(device, BluetoothDevice.METADATA_MAIN_CHARGING);
        } else {
            setBoolean(device, BluetoothDevice.METADATA_MAIN_CHARGING,
                    isCharging(state.leftBatteryStatus) && isCharging(state.rightBatteryStatus));
        }
    }

    private static void publishBattery(BluetoothDevice device, AirPodsNearbyState state) {
        setInt(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY, state.leftBattery);
        setInt(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY, state.rightBattery);
        setInt(device, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY, state.caseBattery);
        setBooleanOrClear(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING,
                state.leftBattery, state.leftCharging);
        setBooleanOrClear(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING,
                state.rightBattery, state.rightCharging);
        setBooleanOrClear(device, BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING,
                state.caseBattery, state.caseCharging);
        int main = minimumKnown(state.leftBattery, state.rightBattery);
        setInt(device, BluetoothDevice.METADATA_MAIN_BATTERY, main);
        if (main < 0) {
            clearMetadata(device, BluetoothDevice.METADATA_MAIN_CHARGING);
        } else {
            setBoolean(device, BluetoothDevice.METADATA_MAIN_CHARGING,
                    state.leftCharging && state.rightCharging);
        }
    }

    private static void clearBattery(BluetoothDevice device) {
        clearMetadata(device, BluetoothDevice.METADATA_MAIN_BATTERY);
        clearMetadata(device, BluetoothDevice.METADATA_MAIN_CHARGING);
        clearMetadata(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY);
        clearMetadata(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING);
        clearMetadata(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY);
        clearMetadata(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING);
        clearMetadata(device, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY);
        clearMetadata(device, BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING);
    }

    private Uri iconUri(int resource) throws IOException {
        File directory = new File(getFilesDir(), "icons");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("icons mkdir");
        String name = getResources().getResourceEntryName(resource) + ".png";
        File file = new File(directory, name);
        if (!file.exists()) {
            Drawable drawable = getDrawable(resource);
            if (drawable == null) throw new IOException("Missing icon " + resource);
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            int size = Math.max(width, height);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT);
            int left = (size - width) / 2;
            int top = (size - height) / 2;
            drawable.setBounds(left, top, left + width, top + height);
            drawable.draw(canvas);
            try (FileOutputStream stream = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            bitmap.recycle();
        }
        Uri uri = FileProvider.getUriForFile(this, "org.xiaomi.haotian.airpods.icons", file);
        for (String packageName : ICON_READERS) {
            grantUriPermission(packageName, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        return uri;
    }

    private static void setUri(BluetoothDevice device, int key, Uri uri) {
        setString(device, key, uri.toString());
    }

    private static void setBoolean(BluetoothDevice device, int key, boolean value) {
        setString(device, key, Boolean.toString(value));
    }

    private static void setCharging(BluetoothDevice device, int key, int level, int status) {
        if (level < 0 || status == AirPodsState.BATTERY_STATUS_UNKNOWN
                || status == AirPodsState.BATTERY_STATUS_DISCONNECTED) {
            clearMetadata(device, key);
        } else {
            setBoolean(device, key, isCharging(status));
        }
    }

    private static void setBooleanOrClear(
            BluetoothDevice device, int key, int level, boolean value) {
        if (level < 0) {
            clearMetadata(device, key);
        } else {
            setBoolean(device, key, value);
        }
    }

    private static boolean isCharging(int status) {
        return status == AirPodsState.BATTERY_STATUS_CHARGING
                || status == AirPodsState.BATTERY_STATUS_OPTIMIZED_CHARGING;
    }

    private static void setInt(BluetoothDevice device, int key, int value) {
        if (value >= 0) {
            setString(device, key, Integer.toString(value));
        } else {
            clearMetadata(device, key);
        }
    }

    private static void setString(BluetoothDevice device, int key, String value) {
        device.setMetadata(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void clearMetadata(BluetoothDevice device, int key) {
        // BluetoothDevice.setMetadata() does not accept null.  An empty value is persisted and is
        // treated as no endpoint by both URI and Fast Pair tag parsers.
        device.setMetadata(key, new byte[0]);
    }

    private static void removeLegacyDeviceSettingsMetadata(BluetoothDevice device) {
        byte[] raw = device.getMetadata(METADATA_FAST_PAIR_CUSTOMIZED_FIELDS);
        if (raw == null || raw.length == 0) return;
        String metadata = new String(raw, StandardCharsets.UTF_8);
        for (String tag : LEGACY_DEVICE_SETTINGS_TAGS) {
            metadata = removeTag(metadata, tag);
        }
        device.setMetadata(METADATA_FAST_PAIR_CUSTOMIZED_FIELDS,
                metadata.getBytes(StandardCharsets.UTF_8));
    }

    private static String removeTag(String value, String tag) {
        String opening = "<" + tag + ">";
        String closing = "</" + tag + ">";
        int start;
        while ((start = value.indexOf(opening)) >= 0) {
            int end = value.indexOf(closing, start + opening.length());
            if (end < 0) break;
            value = value.substring(0, start) + value.substring(end + closing.length());
        }
        return value;
    }

    private static int minimumKnown(int left, int right) {
        if (left < 0) return right;
        if (right < 0) return left;
        return Math.min(left, right);
    }
}
