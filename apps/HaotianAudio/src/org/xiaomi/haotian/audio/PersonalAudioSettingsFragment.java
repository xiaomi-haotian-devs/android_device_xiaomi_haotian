/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.Arrays;
import java.util.Locale;

/** Per-output SoundID, hearing, ear-canal and headphone-model controls. */
public final class PersonalAudioSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    static final int SCREEN_SOUND_ID = 0;
    static final int SCREEN_HEARING = 1;
    static final int SCREEN_EAR_CANAL = 2;
    static final int SCREEN_HEADSET_MODEL = 3;
    private static final String ARG_SCREEN = "screen";

    private static final String KEY_INTRO = "personal_intro";
    private static final String KEY_SOUND_ID_CATEGORY = "sound_id_category";
    private static final String KEY_HEARING_CATEGORY = "hearing_category";
    private static final String KEY_EAR_SCAN_CATEGORY = "ear_scan_category";
    private static final String KEY_HEADSET_MODEL_CATEGORY = "headset_model_category";
    private static final String KEY_SOUND_ID_ENABLED = "sound_id_enabled";
    private static final String KEY_SOUND_ID_RESET = "sound_id_reset";
    private static final String KEY_SOUND_ID_BANDS = "sound_id_bands";
    private static final String KEY_SOUND_ID_BAND_PREFIX = "sound_id_band_";
    private static final String KEY_HEARING_ENABLED = "hearing_enabled";
    private static final String KEY_HEARING_LINK = "hearing_link_channels";
    private static final String KEY_HEARING_RESET = "hearing_reset";
    private static final String KEY_HEARING_LEFT_BANDS = "hearing_left_bands";
    private static final String KEY_HEARING_RIGHT_BANDS = "hearing_right_bands";
    private static final String KEY_HEARING_LEFT_PREFIX = "hearing_left_";
    private static final String KEY_HEARING_RIGHT_PREFIX = "hearing_right_";
    private static final String KEY_EAR_SCAN_STATUS = "ear_scan_status_detail";
    private static final String KEY_EAR_SCAN_ENABLED = "ear_scan_enabled";
    private static final String KEY_EAR_SCAN_MEASURE = "ear_scan_measure";
    private static final String KEY_EAR_SCAN_IMPORT = "ear_scan_import";
    private static final String KEY_EAR_SCAN_CLEAR = "ear_scan_clear";
    private static final String KEY_HEADSET_MODEL = "headset_model_select";
    private static final String STATE_HEARING_CHANNELS_LINKED = "hearing_channels_linked";
    private static final String STATE_HEARING_LINK_INITIALIZED = "hearing_link_initialized";

    private static final String[] PERSONAL_FREQUENCIES = {
        "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz"
    };

    private final SeekBarPreference[] soundIdBands =
            new SeekBarPreference[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
    private final SeekBarPreference[] hearingLeftBands =
            new SeekBarPreference[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
    private final SeekBarPreference[] hearingRightBands =
            new SeekBarPreference[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];

    private IHaotianAudioService service;
    private AudioDeviceProfile profile;
    private boolean serviceBound;
    private boolean receiverRegistered;
    private boolean updatingUi;
    private boolean hearingChannelsLinked = true;
    private boolean hearingLinkInitialized;

    private SwitchPreferenceCompat soundIdEnabled;
    private SwitchPreferenceCompat hearingEnabled;
    private SwitchPreferenceCompat hearingLink;
    private SwitchPreferenceCompat earScanEnabled;
    private Preference earScanStatus;
    private Preference earScanMeasure;
    private Preference earScanClear;
    private EditTextPreference earScanImport;
    private ListPreference headsetModel;

    static PersonalAudioSettingsFragment newInstance(int screen) {
        PersonalAudioSettingsFragment fragment = new PersonalAudioSettingsFragment();
        Bundle arguments = new Bundle();
        arguments.putInt(ARG_SCREEN, screen);
        fragment.setArguments(arguments);
        return fragment;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IHaotianAudioService.Stub.asInterface(binder);
            reload();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            setControlsEnabled(false);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!HaotianAudioService.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            if (intent.hasExtra(HaotianAudioService.EXTRA_APPLY_SUCCEEDED)
                    && !intent.getBooleanExtra(
                            HaotianAudioService.EXTRA_APPLY_SUCCEEDED, false)) {
                Toast.makeText(context, R.string.apply_failed,
                        Toast.LENGTH_SHORT).show();
            }
            reload();
        }
    };

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        if (state != null) {
            hearingChannelsLinked = state.getBoolean(STATE_HEARING_CHANNELS_LINKED, true);
            hearingLinkInitialized = state.getBoolean(STATE_HEARING_LINK_INITIALIZED, false);
        }
        setPreferencesFromResource(R.xml.personal_audio_settings, rootKey);
        int screen = getArguments() == null ? SCREEN_SOUND_ID
                : getArguments().getInt(ARG_SCREEN, SCREEN_SOUND_ID);
        configureScreen(screen);
        soundIdEnabled = requirePreference(KEY_SOUND_ID_ENABLED);
        hearingEnabled = requirePreference(KEY_HEARING_ENABLED);
        hearingLink = requirePreference(KEY_HEARING_LINK);
        earScanEnabled = requirePreference(KEY_EAR_SCAN_ENABLED);
        earScanStatus = requirePreference(KEY_EAR_SCAN_STATUS);
        earScanMeasure = requirePreference(KEY_EAR_SCAN_MEASURE);
        earScanImport = requirePreference(KEY_EAR_SCAN_IMPORT);
        earScanClear = requirePreference(KEY_EAR_SCAN_CLEAR);
        headsetModel = requirePreference(KEY_HEADSET_MODEL);

        bindPreference(soundIdEnabled);
        bindPreference(hearingEnabled);
        bindPreference(hearingLink);
        bindPreference(earScanEnabled);
        bindPreference(earScanImport);
        bindPreference(headsetModel);
        headsetModel.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());

        createBandPreferences(requirePreference(KEY_SOUND_ID_BANDS), soundIdBands,
                KEY_SOUND_ID_BAND_PREFIX);
        createBandPreferences(requirePreference(KEY_HEARING_LEFT_BANDS), hearingLeftBands,
                KEY_HEARING_LEFT_PREFIX);
        createBandPreferences(requirePreference(KEY_HEARING_RIGHT_BANDS), hearingRightBands,
                KEY_HEARING_RIGHT_PREFIX);

        requirePreference(KEY_SOUND_ID_RESET).setOnPreferenceClickListener(preference -> {
            if (profile == null) return true;
            AudioDeviceProfile updated = new AudioDeviceProfile(profile);
            updated.soundIdGains = new float[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
            submitProfile(updated);
            return true;
        });
        requirePreference(KEY_HEARING_RESET).setOnPreferenceClickListener(preference -> {
            if (profile == null) return true;
            AudioDeviceProfile updated = new AudioDeviceProfile(profile);
            updated.hearingLeftGains =
                    new float[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
            updated.hearingRightGains =
                    new float[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
            submitProfile(updated);
            return true;
        });
        earScanMeasure.setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.ear_canal_scan)
                    .setMessage(R.string.ear_scan_hardware_dialog)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        });
        earScanClear.setOnPreferenceClickListener(preference -> {
            if (profile == null) return true;
            AudioDeviceProfile updated = new AudioDeviceProfile(profile);
            updated.earScanEnabled = false;
            updated.earScanFilterValues = new float[0];
            submitProfile(updated);
            return true;
        });

        setControlsEnabled(false);
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_HEARING_CHANNELS_LINKED, hearingChannelsLinked);
        state.putBoolean(STATE_HEARING_LINK_INITIALIZED, hearingLinkInitialized);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onStart() {
        super.onStart();
        Context context = requireContext();
        context.registerReceiver(stateReceiver,
                new IntentFilter(HaotianAudioService.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        serviceBound = context.bindService(new Intent(context, HaotianAudioService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        Context context = requireContext();
        if (receiverRegistered) {
            context.unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        if (serviceBound) {
            context.unbindService(serviceConnection);
            serviceBound = false;
        }
        service = null;
        super.onStop();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
        if (updatingUi) return true;
        AudioDeviceProfile confirmed = profile;
        if (confirmed == null || service == null) return false;
        AudioDeviceProfile current = new AudioDeviceProfile(confirmed);
        String key = preference.getKey();
        if (KEY_SOUND_ID_ENABLED.equals(key)) {
            ensureSoundIdCurve(current);
            current.soundIdEnabled = (Boolean) value;
        } else if (KEY_HEARING_ENABLED.equals(key)) {
            ensureHearingCurves(current);
            current.hearingProfileEnabled = (Boolean) value;
        } else if (KEY_HEARING_LINK.equals(key)) {
            hearingChannelsLinked = (Boolean) value;
            hearingLinkInitialized = true;
            if (hearingChannelsLinked) {
                ensureHearingCurves(current);
                current.hearingRightGains = Arrays.copyOf(current.hearingLeftGains,
                        current.hearingLeftGains.length);
            }
        } else if (KEY_EAR_SCAN_ENABLED.equals(key)) {
            if (current.earScanFilterValues.length
                    != AudioDeviceProfile.EAR_SCAN_COEFFICIENT_COUNT) return false;
            current.earScanEnabled = (Boolean) value;
        } else if (KEY_EAR_SCAN_IMPORT.equals(key)) {
            float[] coefficients = parseCoefficients((String) value);
            if (coefficients == null) {
                Toast.makeText(requireContext(), R.string.import_calibration_invalid,
                        Toast.LENGTH_LONG).show();
                return false;
            }
            current.earScanFilterValues = coefficients;
            current.earScanEnabled = true;
        } else if (KEY_HEADSET_MODEL.equals(key)) {
            current.headsetModelId = parseInt((String) value, 0);
            current.headsetModelName = headsetNameForValue((String) value);
        } else if (key.startsWith(KEY_SOUND_ID_BAND_PREFIX)) {
            ensureSoundIdCurve(current);
            int band = parseBand(key, KEY_SOUND_ID_BAND_PREFIX);
            if (band < 0) return false;
            current.soundIdGains[band] = ((Integer) value) / 2f;
            current.soundIdProfileId = "manual";
        } else if (key.startsWith(KEY_HEARING_LEFT_PREFIX)) {
            ensureHearingCurves(current);
            int band = parseBand(key, KEY_HEARING_LEFT_PREFIX);
            if (band < 0) return false;
            current.hearingLeftGains[band] = ((Integer) value) / 2f;
            if (hearingChannelsLinked) current.hearingRightGains[band] =
                    current.hearingLeftGains[band];
        } else if (key.startsWith(KEY_HEARING_RIGHT_PREFIX)) {
            ensureHearingCurves(current);
            int band = parseBand(key, KEY_HEARING_RIGHT_PREFIX);
            if (band < 0) return false;
            current.hearingRightGains[band] = ((Integer) value) / 2f;
        } else {
            return false;
        }
        boolean submitted = submitProfile(current);
        // Imported coefficient strings are deliberately never retained in a UI preference.
        return submitted && !KEY_EAR_SCAN_IMPORT.equals(key);
    }

    private void reload() {
        IHaotianAudioService currentService = service;
        if (currentService == null) return;
        try {
            AudioDeviceProfile loaded = currentService.getActiveProfile();
            if (loaded == null) return;
            profile = loaded;
            setControlsEnabled(true);
            refreshUi();
        } catch (RemoteException | RuntimeException exception) {
            setControlsEnabled(false);
        }
    }

    private boolean submitProfile(AudioDeviceProfile requested) {
        IHaotianAudioService currentService = service;
        AudioDeviceProfile confirmed = profile;
        if (currentService == null || confirmed == null || requested == null) return false;
        try {
            profile = new AudioDeviceProfile(requested);
            refreshUi();
            setControlsEnabled(false);
            currentService.saveProfile(new AudioDeviceProfile(requested), true);
            return true;
        } catch (RemoteException | RuntimeException exception) {
            profile = confirmed;
            refreshUi();
            setControlsEnabled(true);
            Toast.makeText(requireContext(), R.string.apply_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void refreshUi() {
        AudioDeviceProfile current = profile;
        if (current == null) return;
        updatingUi = true;
        soundIdEnabled.setChecked(current.soundIdEnabled);
        hearingEnabled.setChecked(current.hearingProfileEnabled);
        earScanEnabled.setChecked(current.earScanEnabled);
        headsetModel.setValue(Integer.toString(current.headsetModelId));

        float[] soundId = sizedPersonalCurve(current.soundIdGains);
        float[] left = sizedPersonalCurve(current.hearingLeftGains);
        float[] right = sizedPersonalCurve(current.hearingRightGains);
        if (!hearingLinkInitialized) {
            hearingChannelsLinked = Arrays.equals(left, right);
            hearingLinkInitialized = true;
        }
        for (int band = 0; band < AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT; band++) {
            setBandValue(soundIdBands[band], soundId[band]);
            setBandValue(hearingLeftBands[band], left[band]);
            setBandValue(hearingRightBands[band], right[band]);
        }
        hearingLink.setChecked(hearingChannelsLinked);
        for (SeekBarPreference preference : hearingRightBands) {
            preference.setEnabled(!hearingChannelsLinked);
        }

        boolean hasCalibration = current.earScanFilterValues.length
                == AudioDeviceProfile.EAR_SCAN_COEFFICIENT_COUNT;
        earScanStatus.setSummary(hasCalibration
                ? getString(R.string.calibration_loaded,
                        AudioDeviceProfile.EAR_SCAN_COEFFICIENT_COUNT)
                : getString(R.string.personal_audio_not_configured));
        earScanEnabled.setEnabled(hasCalibration);
        earScanClear.setEnabled(hasCalibration);
        updatingUi = false;
    }

    private void createBandPreferences(PreferenceCategory category,
            SeekBarPreference[] preferences, String prefix) {
        for (int band = 0; band < preferences.length; band++) {
            SeekBarPreference preference = new SeekBarPreference(requireContext());
            preference.setKey(prefix + band);
            preference.setTitle(PERSONAL_FREQUENCIES[band]);
            preference.setMin(-24);
            preference.setMax(24);
            preference.setSeekBarIncrement(1);
            preference.setShowSeekBarValue(false);
            preference.setUpdatesContinuously(false);
            preference.setPersistent(false);
            preference.setOnPreferenceChangeListener(this);
            category.addPreference(preference);
            preferences[band] = preference;
        }
    }

    private void setBandValue(SeekBarPreference preference, float value) {
        preference.setValue(Math.round(value * 2f));
        preference.setSummary(String.format(Locale.getDefault(), "%+.1f dB", value));
    }

    private void setControlsEnabled(boolean enabled) {
        if (getPreferenceScreen() != null) getPreferenceScreen().setEnabled(enabled);
    }

    private void bindPreference(Preference preference) {
        preference.setPersistent(false);
        preference.setOnPreferenceChangeListener(this);
    }

    private void configureScreen(int screen) {
        Preference intro = requirePreference(KEY_INTRO);
        Preference soundId = requirePreference(KEY_SOUND_ID_CATEGORY);
        Preference soundIdBands = requirePreference(KEY_SOUND_ID_BANDS);
        Preference hearing = requirePreference(KEY_HEARING_CATEGORY);
        Preference hearingLeft = requirePreference(KEY_HEARING_LEFT_BANDS);
        Preference hearingRight = requirePreference(KEY_HEARING_RIGHT_BANDS);
        Preference earCanal = requirePreference(KEY_EAR_SCAN_CATEGORY);
        Preference headset = requirePreference(KEY_HEADSET_MODEL_CATEGORY);

        soundId.setVisible(screen == SCREEN_SOUND_ID);
        soundIdBands.setVisible(screen == SCREEN_SOUND_ID);
        hearing.setVisible(screen == SCREEN_HEARING);
        hearingLeft.setVisible(screen == SCREEN_HEARING);
        hearingRight.setVisible(screen == SCREEN_HEARING);
        earCanal.setVisible(screen == SCREEN_EAR_CANAL);
        headset.setVisible(screen == SCREEN_HEADSET_MODEL);

        switch (screen) {
            case SCREEN_HEARING:
                intro.setTitle(R.string.hearing_compensation_intro);
                break;
            case SCREEN_EAR_CANAL:
                intro.setTitle(R.string.ear_canal_intro);
                break;
            case SCREEN_HEADSET_MODEL:
                intro.setTitle(R.string.headset_model_intro);
                break;
            case SCREEN_SOUND_ID:
            default:
                intro.setTitle(R.string.sound_id_intro);
                break;
        }
    }

    private String headsetNameForValue(String value) {
        int index = headsetModel.findIndexOfValue(value);
        CharSequence[] entries = headsetModel.getEntries();
        return index >= 0 && index < entries.length ? entries[index].toString() : "";
    }

    private static void ensureSoundIdCurve(AudioDeviceProfile profile) {
        if (profile.soundIdGains.length != AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT) {
            profile.soundIdGains = new float[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
        }
    }

    private static void ensureHearingCurves(AudioDeviceProfile profile) {
        if (profile.hearingLeftGains.length
                != AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT) {
            profile.hearingLeftGains =
                    new float[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
        }
        if (profile.hearingRightGains.length
                != AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT) {
            profile.hearingRightGains =
                    new float[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
        }
    }

    private static float[] sizedPersonalCurve(float[] values) {
        return values != null && values.length == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT
                ? values : new float[AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT];
    }

    private static float[] parseCoefficients(String encoded) {
        if (encoded == null) return null;
        String[] parts = encoded.trim().split("[\\s,;]+", -1);
        if (parts.length != AudioDeviceProfile.EAR_SCAN_COEFFICIENT_COUNT) return null;
        float[] values = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                values[i] = Float.parseFloat(parts[i]);
                if (!Float.isFinite(values[i]) || values[i] < -128f || values[i] > 128f) {
                    return null;
                }
            }
            return values;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int parseBand(String key, String prefix) {
        int band = parseInt(key.substring(prefix.length()), -1);
        return band >= 0 && band < AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT ? band : -1;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Preference> T requirePreference(String key) {
        T preference = findPreference(key);
        if (preference == null) throw new IllegalStateException("Missing preference " + key);
        return preference;
    }
}
