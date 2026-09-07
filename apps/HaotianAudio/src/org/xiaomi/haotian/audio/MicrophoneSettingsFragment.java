/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

/** Independent microphone configuration and diagnostics page. */
public final class MicrophoneSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final long STATUS_REFRESH_INTERVAL_MS = 1000;

    private static final String KEY_MODE = "microphone_recording_mode";
    private static final String KEY_WIND = "microphone_wind_reduction";
    private static final String KEY_TRACKING = "microphone_source_tracking";
    private static final String KEY_WIDTH = "microphone_focus_width";
    private static final String KEY_CAPTURE = "microphone_capture_status";
    private static final String KEY_FORMAT = "microphone_input_format";
    private static final String KEY_DEVICE = "microphone_active_device";
    private static final String KEY_OZO = "microphone_ozo_status";
    private static final String KEY_ROUTE = "microphone_route_status";
    private static final String KEY_TOP = "microphone_top_status";
    private static final String KEY_BOTTOM_AUX = "microphone_bottom_aux_status";
    private static final String KEY_BACK = "microphone_back_status";
    private static final String KEY_BOTTOM_MAIN = "microphone_bottom_main_status";

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            refreshLiveStatus();
            statusHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
        }
    };

    private MicrophoneConfigStore configStore;
    private MicrophoneStatusReporter statusReporter;
    private ListPreference modePreference;
    private SwitchPreferenceCompat windPreference;
    private SwitchPreferenceCompat trackingPreference;
    private SeekBarPreference widthPreference;
    private Preference capturePreference;
    private Preference formatPreference;
    private Preference devicePreference;
    private Preference ozoPreference;
    private Preference routePreference;
    private Preference topPreference;
    private Preference bottomAuxPreference;
    private Preference backPreference;
    private Preference bottomMainPreference;

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        setPreferencesFromResource(R.xml.microphone_settings, rootKey);
        configStore = new MicrophoneConfigStore(requireContext().getContentResolver());
        statusReporter = new MicrophoneStatusReporter(requireContext());

        modePreference = requirePreference(KEY_MODE);
        windPreference = requirePreference(KEY_WIND);
        trackingPreference = requirePreference(KEY_TRACKING);
        widthPreference = requirePreference(KEY_WIDTH);
        capturePreference = requirePreference(KEY_CAPTURE);
        formatPreference = requirePreference(KEY_FORMAT);
        devicePreference = requirePreference(KEY_DEVICE);
        ozoPreference = requirePreference(KEY_OZO);
        routePreference = requirePreference(KEY_ROUTE);
        topPreference = requirePreference(KEY_TOP);
        bottomAuxPreference = requirePreference(KEY_BOTTOM_AUX);
        backPreference = requirePreference(KEY_BACK);
        bottomMainPreference = requirePreference(KEY_BOTTOM_MAIN);

        modePreference.setOnPreferenceChangeListener(this);
        windPreference.setOnPreferenceChangeListener(this);
        trackingPreference.setOnPreferenceChangeListener(this);
        widthPreference.setOnPreferenceChangeListener(this);
        reloadConfiguration();
        refreshLiveStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadConfiguration();
        statusHandler.removeCallbacks(statusRefresh);
        statusHandler.post(statusRefresh);
    }

    @Override
    public void onPause() {
        statusHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == modePreference) {
            configStore.setMode(String.valueOf(newValue));
        } else if (preference == windPreference) {
            configStore.setWindReductionEnabled((Boolean) newValue);
        } else if (preference == trackingPreference) {
            configStore.setSourceTrackingEnabled((Boolean) newValue);
        } else if (preference == widthPreference) {
            configStore.setFocusWidth((Integer) newValue);
        } else {
            return false;
        }
        statusHandler.post(this::reloadConfiguration);
        return true;
    }

    private void reloadConfiguration() {
        String mode = configStore.getMode();
        modePreference.setValue(mode);
        modePreference.setSummary(modePreference.getEntry());
        windPreference.setChecked(configStore.isWindReductionEnabled());
        trackingPreference.setChecked(configStore.isSourceTrackingEnabled());
        widthPreference.setValue(configStore.getFocusWidth());

        boolean directional = MicrophoneConfigStore.MODE_FRONT.equals(mode)
                || MicrophoneConfigStore.MODE_BACK.equals(mode)
                || MicrophoneConfigStore.MODE_DUAL.equals(mode)
                || MicrophoneConfigStore.MODE_AUDIO_ZOOM.equals(mode);
        trackingPreference.setEnabled(MicrophoneConfigStore.MODE_AUDIO_ZOOM.equals(mode));
        widthPreference.setEnabled(directional);
    }

    private void refreshLiveStatus() {
        MicrophoneStatusReporter.Snapshot snapshot = statusReporter.capture();
        capturePreference.setSummary(snapshot.capture);
        formatPreference.setSummary(snapshot.format);
        devicePreference.setSummary(snapshot.device);
        ozoPreference.setSummary(snapshot.ozo);
        routePreference.setSummary(snapshot.route);
        int microphoneState = !snapshot.recording
                ? R.string.microphone_physical_idle
                : snapshot.deviceChannels >= 4
                        ? R.string.microphone_physical_active
                        : R.string.microphone_physical_subset_unknown;
        updatePhysicalStatus(topPreference, R.string.microphone_top_summary, microphoneState);
        updatePhysicalStatus(bottomAuxPreference, R.string.microphone_bottom_aux_summary,
                microphoneState);
        updatePhysicalStatus(backPreference, R.string.microphone_back_summary, microphoneState);
        updatePhysicalStatus(bottomMainPreference, R.string.microphone_bottom_main_summary,
                microphoneState);

        if (!MicrophoneConfigStore.MODE_STANDARD.equals(configStore.getMode())
                && !snapshot.ozoReady) {
            modePreference.setSummary(getString(R.string.microphone_mode_saved_waiting,
                    modePreference.getEntry()));
        } else {
            modePreference.setSummary(modePreference.getEntry());
        }
    }

    private void updatePhysicalStatus(Preference preference, int mapping, int state) {
        preference.setSummary(getString(R.string.microphone_physical_status_format,
                getString(mapping), getString(state)));
    }

    @SuppressWarnings("unchecked")
    private <T extends Preference> T requirePreference(String key) {
        Preference preference = findPreference(key);
        if (preference == null) throw new IllegalStateException("Missing preference: " + key);
        return (T) preference;
    }
}
