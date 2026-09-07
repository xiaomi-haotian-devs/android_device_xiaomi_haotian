/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.ButtonPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.Arrays;
import java.util.Locale;

/** Settings client. All global effect ownership remains in {@link HaotianAudioService}. */
public final class AudioSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String KEY_DOLBY_CATEGORY = "dolby_category";
    private static final String KEY_DOLBY_EQ_CATEGORY = "dolby_eq_category";
    private static final String KEY_MISOUND_CATEGORY = "misound_category";
    private static final String KEY_MISOUND_EQ_CATEGORY = "misound_eq_category";
    private static final String KEY_DOLBY_EQ_EXPAND = "dolby_eq_expand";
    private static final String KEY_DOLBY_EQ_RESET = "dolby_eq_reset";
    private static final String KEY_MISOUND_EQ_EXPAND = "misound_eq_expand";
    private static final String KEY_MISOUND_EQ_RESET = "misound_eq_reset";
    private static final String KEY_ACTIVE_OUTPUT = "active_output";
    private static final String KEY_HEAD_TRACKING_DEBUG = "head_tracking_debug";
    private static final String KEY_SOUND_ID_STATUS = "sound_id_status";
    private static final String KEY_HEARING_STATUS = "hearing_profile_status";
    private static final String KEY_EAR_SCAN_STATUS = "ear_scan_status";
    private static final String KEY_HEADSET_MODEL_STATUS = "headset_model_status";
    private static final String KEY_EFFECT_STATUS = "effect_status";
    private static final String KEY_INPUT_AUDIO_STATUS = "input_audio_status";
    private static final String KEY_OUTPUT_AUDIO_STATUS = "output_audio_status";
    private static final String KEY_SPATIAL_AUDIO_STATUS = "spatial_audio_status";
    private static final String KEY_PERSONAL_AUDIO_STATUS = "personal_audio_status";
    private static final long STATUS_REFRESH_INTERVAL_MS = 1000;

    private static final String[] DOLBY_FREQUENCIES = {
        "32 Hz", "64 Hz", "125 Hz", "250 Hz", "500 Hz",
        "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz"
    };
    private static final String[] MISOUND_FREQUENCIES = {
        "65 Hz", "150 Hz", "400 Hz", "1 kHz", "2.5 kHz", "6 kHz", "14 kHz"
    };

    private final SeekBarPreference[] dolbyBands =
            new SeekBarPreference[DOLBY_FREQUENCIES.length];
    private final SeekBarPreference[] miSoundBands =
            new SeekBarPreference[MISOUND_FREQUENCIES.length];

    private IHaotianAudioService service;
    private AudioDeviceProfile activeProfile;
    private boolean serviceBound;
    private boolean receiverRegistered;
    private boolean dolbyEqualizerExpanded;
    private boolean miSoundEqualizerExpanded;

    private ListPreference implementationPreference;
    private SwitchPreferenceCompat spatialPreference;
    private PreferenceCategory dolbyCategory;
    private PreferenceCategory dolbyEqCategory;
    private PreferenceCategory miSoundCategory;
    private PreferenceCategory miSoundEqCategory;
    private Preference dolbyEqExpandPreference;
    private ButtonPreference dolbyEqResetPreference;
    private Preference miSoundEqExpandPreference;
    private ButtonPreference miSoundEqResetPreference;
    private ListPreference dolbyProfilePreference;
    private ListPreference dolbyEqPreference;
    private ListPreference miSoundProfilePreference;
    private SwitchPreferenceCompat miSoundSurroundPreference;
    private ListPreference miSoundEqPreference;
    private Preference activeOutputPreference;
    private SwitchPreferenceCompat headTrackingPreference;
    private Preference headTrackingDebugPreference;
    private Preference soundIdStatusPreference;
    private Preference hearingStatusPreference;
    private Preference earScanStatusPreference;
    private Preference headsetModelStatusPreference;
    private Preference effectStatusPreference;
    private Preference inputAudioStatusPreference;
    private Preference outputAudioStatusPreference;
    private Preference spatialAudioStatusPreference;
    private Preference personalAudioStatusPreference;
    private AudioPipelineReporter pipelineReporter;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            refreshLiveStatus();
            statusHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IHaotianAudioService.Stub.asInterface(binder);
            reloadFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            setControlsEnabled(false);
            effectStatusPreference.setSummary(R.string.effect_status_service_unavailable);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (HaotianAudioService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                if (intent.hasExtra(HaotianAudioService.EXTRA_APPLY_SUCCEEDED)
                        && !intent.getBooleanExtra(
                                HaotianAudioService.EXTRA_APPLY_SUCCEEDED, false)) {
                    Toast.makeText(context, R.string.apply_failed,
                            Toast.LENGTH_SHORT).show();
                }
                reloadFromService();
            }
        }
    };

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        setPreferencesFromResource(R.xml.audio_settings, rootKey);

        implementationPreference = requirePreference(AudioEffectController.KEY_IMPLEMENTATION);
        spatialPreference = requirePreference(AudioEffectController.KEY_SPATIAL);
        dolbyCategory = requirePreference(KEY_DOLBY_CATEGORY);
        dolbyEqCategory = requirePreference(KEY_DOLBY_EQ_CATEGORY);
        miSoundCategory = requirePreference(KEY_MISOUND_CATEGORY);
        miSoundEqCategory = requirePreference(KEY_MISOUND_EQ_CATEGORY);
        dolbyEqExpandPreference = requirePreference(KEY_DOLBY_EQ_EXPAND);
        dolbyEqResetPreference = requirePreference(KEY_DOLBY_EQ_RESET);
        miSoundEqExpandPreference = requirePreference(KEY_MISOUND_EQ_EXPAND);
        miSoundEqResetPreference = requirePreference(KEY_MISOUND_EQ_RESET);
        dolbyProfilePreference = requirePreference(AudioEffectController.KEY_DOLBY_PROFILE);
        dolbyEqPreference = requirePreference(AudioEffectController.KEY_DOLBY_EQ);
        miSoundProfilePreference = requirePreference(AudioEffectController.KEY_MISOUND_PROFILE);
        miSoundSurroundPreference =
                requirePreference(AudioEffectController.KEY_MISOUND_SURROUND);
        miSoundEqPreference = requirePreference(AudioEffectController.KEY_MISOUND_EQ);
        activeOutputPreference = requirePreference(KEY_ACTIVE_OUTPUT);
        headTrackingPreference = requirePreference(AudioEffectController.KEY_HEAD_TRACKING);
        headTrackingDebugPreference = requirePreference(KEY_HEAD_TRACKING_DEBUG);
        soundIdStatusPreference = requirePreference(KEY_SOUND_ID_STATUS);
        hearingStatusPreference = requirePreference(KEY_HEARING_STATUS);
        earScanStatusPreference = requirePreference(KEY_EAR_SCAN_STATUS);
        headsetModelStatusPreference = requirePreference(KEY_HEADSET_MODEL_STATUS);
        effectStatusPreference = requirePreference(KEY_EFFECT_STATUS);
        inputAudioStatusPreference = requirePreference(KEY_INPUT_AUDIO_STATUS);
        outputAudioStatusPreference = requirePreference(KEY_OUTPUT_AUDIO_STATUS);
        spatialAudioStatusPreference = requirePreference(KEY_SPATIAL_AUDIO_STATUS);
        personalAudioStatusPreference = requirePreference(KEY_PERSONAL_AUDIO_STATUS);
        pipelineReporter = new AudioPipelineReporter(requireContext());

        bindPreference(implementationPreference);
        bindPreference(spatialPreference);
        bindPreference(headTrackingPreference);
        bindPreference(dolbyProfilePreference);
        bindPreference(dolbyEqPreference);
        bindPreference(miSoundProfilePreference);
        bindPreference(miSoundSurroundPreference);
        bindPreference(miSoundEqPreference);

        createEqualizerPreferences();
        dolbyEqExpandPreference.setOnPreferenceClickListener(preference -> {
            dolbyEqualizerExpanded = !dolbyEqualizerExpanded;
            refreshEffectControls();
            return true;
        });
        miSoundEqExpandPreference.setOnPreferenceClickListener(preference -> {
            miSoundEqualizerExpanded = !miSoundEqualizerExpanded;
            refreshEffectControls();
            return true;
        });
        dolbyEqResetPreference.setOnClickListener(view -> resetEqualizer(true));
        miSoundEqResetPreference.setOnClickListener(view -> resetEqualizer(false));
        soundIdStatusPreference.setOnPreferenceClickListener(preference ->
                openPersonalAudioScreen(PersonalAudioSettingsActivity.SoundId.class));
        hearingStatusPreference.setOnPreferenceClickListener(preference ->
                openPersonalAudioScreen(
                        PersonalAudioSettingsActivity.HearingCompensation.class));
        earScanStatusPreference.setOnPreferenceClickListener(preference ->
                openPersonalAudioScreen(PersonalAudioSettingsActivity.EarCanal.class));
        headsetModelStatusPreference.setOnPreferenceClickListener(preference ->
                openPersonalAudioScreen(PersonalAudioSettingsActivity.HeadsetModel.class));
        headTrackingDebugPreference.setOnPreferenceClickListener(preference -> {
            AudioDeviceProfile profile = activeProfile;
            if (profile == null || !profile.spatialEnabled
                    || profile.headTrackingMode
                            == AudioDeviceProfile.HEAD_TRACKING_DISABLED) {
                return false;
            }
            startActivity(new Intent(requireContext(), HeadTrackingDebugActivity.class));
            return true;
        });
        setControlsEnabled(false);
        refreshUi(false, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        Context context = requireContext();
        Intent serviceIntent = new Intent(context, HaotianAudioService.class);
        IntentFilter filter = new IntentFilter(HaotianAudioService.ACTION_STATE_CHANGED);
        context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        serviceBound = context.bindService(
                serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (!serviceBound) {
            effectStatusPreference.setSummary(R.string.effect_status_service_unavailable);
        }
        statusHandler.removeCallbacks(statusRefresh);
        statusHandler.post(statusRefresh);
    }

    @Override
    public void onStop() {
        statusHandler.removeCallbacks(statusRefresh);
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
        IHaotianAudioService currentService = service;
        AudioDeviceProfile currentProfile = activeProfile;
        if (currentService == null || currentProfile == null) {
            Toast.makeText(requireContext(), R.string.effect_status_service_unavailable,
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        AudioDeviceProfile updated = new AudioDeviceProfile(currentProfile);
        String key = preference.getKey();
        if (AudioEffectController.KEY_IMPLEMENTATION.equals(key)) {
            updated.implementation = (String) value;
            if (AudioDeviceProfile.IMPLEMENTATION_NONE.equals(updated.implementation)) {
                updated.spatialEnabled = false;
                updated.headTrackingMode = AudioDeviceProfile.HEAD_TRACKING_DISABLED;
            }
        } else if (AudioEffectController.KEY_SPATIAL.equals(key)) {
            updated.spatialEnabled = (Boolean) value;
            if (!updated.spatialEnabled) {
                updated.headTrackingMode = AudioDeviceProfile.HEAD_TRACKING_DISABLED;
            }
        } else if (AudioEffectController.KEY_HEAD_TRACKING.equals(key)) {
            if ((Boolean) value && (!updated.spatialEnabled
                    || AudioDeviceProfile.IMPLEMENTATION_NONE.equals(updated.implementation))) {
                return false;
            }
            updated.headTrackingMode = (Boolean) value
                    ? AudioDeviceProfile.HEAD_TRACKING_RELATIVE_WORLD
                    : AudioDeviceProfile.HEAD_TRACKING_DISABLED;
        } else if (AudioEffectController.KEY_DOLBY_PROFILE.equals(key)) {
            updated.dolbyProfile = parseInt((String) value, updated.dolbyProfile);
        } else if (AudioEffectController.KEY_DOLBY_EQ.equals(key)) {
            updated.dolbyEqPreset = (String) value;
            dolbyEqualizerExpanded = false;
        } else if (AudioEffectController.KEY_MISOUND_PROFILE.equals(key)) {
            updated.miSoundProfile = parseInt((String) value, updated.miSoundProfile);
        } else if (AudioEffectController.KEY_MISOUND_SURROUND.equals(key)) {
            updated.miSoundSurround = (Boolean) value;
        } else if (AudioEffectController.KEY_MISOUND_EQ.equals(key)) {
            updated.miSoundEqPreset = (String) value;
            miSoundEqualizerExpanded = false;
        } else if (key.startsWith(AudioEffectController.KEY_DOLBY_BAND_PREFIX)) {
            int band = parseBand(key, AudioEffectController.KEY_DOLBY_BAND_PREFIX,
                    updated.dolbyBands.length);
            if (band < 0) return false;
            updated.dolbyBands[band] = ((Integer) value) / 4f;
            updated.dolbyEqPreset = "custom";
        } else if (key.startsWith(AudioEffectController.KEY_MISOUND_BAND_PREFIX)) {
            int band = parseBand(key, AudioEffectController.KEY_MISOUND_BAND_PREFIX,
                    updated.miSoundBands.length);
            if (band < 0) return false;
            updated.miSoundBands[band] = (Integer) value;
            updated.miSoundEqPreset = "custom";
        } else {
            return false;
        }

        try {
            activeProfile = updated;
            renderProfile(updated);
            setControlsEnabled(false);
            currentService.saveProfile(updated, true);
        } catch (RemoteException | RuntimeException exception) {
            activeProfile = currentProfile;
            renderProfile(currentProfile);
            setControlsEnabled(true);
            Toast.makeText(requireContext(), R.string.apply_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
        // Persistence is disabled on the Preference itself; only the service profile is durable.
        return true;
    }

    private void createEqualizerPreferences() {
        for (int band = 0; band < DOLBY_FREQUENCIES.length; band++) {
            dolbyBands[band] = createBandPreference(
                    AudioEffectController.KEY_DOLBY_BAND_PREFIX + band,
                    DOLBY_FREQUENCIES[band], -24, 24, 1);
            dolbyEqCategory.addPreference(dolbyBands[band]);
        }
        for (int band = 0; band < MISOUND_FREQUENCIES.length; band++) {
            miSoundBands[band] = createBandPreference(
                    AudioEffectController.KEY_MISOUND_BAND_PREFIX + band,
                    MISOUND_FREQUENCIES[band], -6, 6, 1);
            miSoundEqCategory.addPreference(miSoundBands[band]);
        }
    }

    private void resetEqualizer(boolean dolby) {
        IHaotianAudioService currentService = service;
        AudioDeviceProfile currentProfile = activeProfile;
        if (currentService == null || currentProfile == null) {
            Toast.makeText(requireContext(), R.string.effect_status_service_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        AudioDeviceProfile updated = new AudioDeviceProfile(currentProfile);
        if (dolby) {
            Arrays.fill(updated.dolbyBands, 0f);
            updated.dolbyEqPreset = "custom";
        } else {
            Arrays.fill(updated.miSoundBands, 0f);
            updated.miSoundEqPreset = "custom";
        }
        try {
            activeProfile = updated;
            renderProfile(updated);
            setControlsEnabled(false);
            currentService.saveProfile(updated, true);
        } catch (RemoteException | RuntimeException exception) {
            activeProfile = currentProfile;
            renderProfile(currentProfile);
            setControlsEnabled(true);
            Toast.makeText(requireContext(), R.string.apply_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private SeekBarPreference createBandPreference(String key, String title,
            int min, int max, int increment) {
        SeekBarPreference preference = new SeekBarPreference(requireContext());
        preference.setKey(key);
        preference.setTitle(title);
        preference.setMin(min);
        preference.setMax(max);
        preference.setSeekBarIncrement(increment);
        preference.setShowSeekBarValue(false);
        preference.setUpdatesContinuously(false);
        preference.setPersistent(false);
        preference.setOnPreferenceChangeListener(this);
        return preference;
    }

    private void reloadFromService() {
        IHaotianAudioService currentService = service;
        if (currentService == null || !isAdded()) return;
        try {
            AudioDeviceProfile profile = currentService.getActiveProfile();
            if (profile == null) {
                setControlsEnabled(false);
                return;
            }
            activeProfile = profile;
            renderProfile(profile);
            refreshUi(currentService.areEffectsAvailable(),
                    currentService.isHeadTrackerAvailable());
            setControlsEnabled(true);
        } catch (RemoteException | RuntimeException exception) {
            setControlsEnabled(false);
            effectStatusPreference.setSummary(R.string.effect_status_service_unavailable);
        }
    }

    private void renderProfile(AudioDeviceProfile profile) {
        implementationPreference.setValue(profile.implementation);
        spatialPreference.setChecked(profile.spatialEnabled);
        headTrackingPreference.setChecked(profile.headTrackingMode
                != AudioDeviceProfile.HEAD_TRACKING_DISABLED);
        dolbyProfilePreference.setValue(Integer.toString(profile.dolbyProfile));
        dolbyEqPreference.setValue(profile.dolbyEqPreset);
        miSoundProfilePreference.setValue(Integer.toString(profile.miSoundProfile));
        miSoundSurroundPreference.setChecked(profile.miSoundSurround);
        miSoundEqPreference.setValue(profile.miSoundEqPreset);

        for (int band = 0; band < dolbyBands.length; band++) {
            dolbyBands[band].setValue(Math.round(profile.dolbyBands[band] * 4f));
            updateBandSummary(dolbyBands[band], true);
        }
        for (int band = 0; band < miSoundBands.length; band++) {
            miSoundBands[band].setValue(Math.round(profile.miSoundBands[band]));
            updateBandSummary(miSoundBands[band], false);
        }
        refreshEffectControls();
    }

    private void refreshUi(boolean effectsAvailable, boolean headTrackerAvailable) {
        refreshEffectControls();

        AudioDeviceProfile profile = activeProfile;
        if (profile == null) {
            activeOutputPreference.setSummary(R.string.status_loading);
            soundIdStatusPreference.setSummary(R.string.personal_audio_not_configured);
            hearingStatusPreference.setSummary(R.string.personal_audio_not_configured);
            earScanStatusPreference.setSummary(R.string.personal_audio_not_configured);
            headsetModelStatusPreference.setSummary(R.string.personal_audio_not_configured);
        } else {
            activeOutputPreference.setSummary(profile.displayName);
            soundIdStatusPreference.setSummary(personalStatus(profile.soundIdEnabled,
                    profile.soundIdGains.length
                            == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT));
            hearingStatusPreference.setSummary(personalStatus(profile.hearingProfileEnabled,
                    profile.hearingLeftGains.length
                            == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT
                            && profile.hearingRightGains.length
                                    == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT));
            earScanStatusPreference.setSummary(personalStatus(profile.earScanEnabled,
                    profile.earScanFilterValues.length
                            == AudioDeviceProfile.EAR_SCAN_COEFFICIENT_COUNT));
            if (!profile.headsetModelName.isEmpty()) {
                headsetModelStatusPreference.setSummary(profile.headsetModelName);
            } else if (profile.headsetModelId > 0) {
                headsetModelStatusPreference.setSummary(
                        getString(R.string.headset_model_id, profile.headsetModelId));
            } else {
                headsetModelStatusPreference.setSummary(R.string.personal_audio_not_configured);
            }
        }

        if (profile == null || !profile.spatialEnabled
                || AudioDeviceProfile.IMPLEMENTATION_NONE.equals(profile.implementation)) {
            headTrackingPreference.setSummary(R.string.head_tracking_requires_immersive);
        } else if (profile.headTrackingMode
                == AudioDeviceProfile.HEAD_TRACKING_DISABLED) {
            headTrackingPreference.setSummary(R.string.head_tracking_disabled_summary);
        } else if (!headTrackerAvailable) {
            headTrackingPreference.setSummary(R.string.head_tracking_unavailable);
        } else {
            headTrackingPreference.setSummary(R.string.head_tracking_enabled_summary);
        }
        effectStatusPreference.setSummary(effectsAvailable
                ? R.string.effect_status_ready : R.string.effect_status_unavailable);
        refreshLiveStatus();
    }

    private void refreshLiveStatus() {
        if (!isAdded() || pipelineReporter == null) return;
        boolean available = false;
        IHaotianAudioService currentService = service;
        if (currentService != null) {
            try {
                available = currentService.areEffectsAvailable();
            } catch (RemoteException ignored) {
            }
        }
        try {
            AudioPipelineReporter.Snapshot snapshot =
                    pipelineReporter.capture(activeProfile, available);
            effectStatusPreference.setSummary(snapshot.engine);
            inputAudioStatusPreference.setSummary(snapshot.input);
            outputAudioStatusPreference.setSummary(snapshot.output);
            spatialAudioStatusPreference.setSummary(snapshot.spatial);
            personalAudioStatusPreference.setSummary(snapshot.personal);
            updateSpatialPreferenceSummary(snapshot);
        } catch (RuntimeException exception) {
            inputAudioStatusPreference.setSummary(R.string.audio_status_unavailable);
            outputAudioStatusPreference.setSummary(R.string.audio_status_unavailable);
            spatialAudioStatusPreference.setSummary(R.string.audio_status_unavailable);
            personalAudioStatusPreference.setSummary(R.string.audio_status_unavailable);
        }
    }

    private void updateSpatialPreferenceSummary(AudioPipelineReporter.Snapshot snapshot) {
        AudioDeviceProfile profile = activeProfile;
        if (profile == null || !profile.spatialEnabled
                || AudioDeviceProfile.IMPLEMENTATION_NONE.equals(profile.implementation)) {
            spatialPreference.setSummary(R.string.immersive_sound_summary);
        } else if (!snapshot.spatializerEnabled) {
            spatialPreference.setSummary(R.string.immersive_sound_not_applied);
        } else if (!snapshot.spatializerAvailable) {
            spatialPreference.setSummary(R.string.immersive_sound_waiting_route);
        } else if (snapshot.hasSpatializedPlayback) {
            spatialPreference.setSummary(R.string.immersive_sound_active);
        } else if (snapshot.hasActivePlayback) {
            spatialPreference.setSummary(R.string.immersive_sound_content_bypassed);
        } else {
            spatialPreference.setSummary(R.string.immersive_sound_ready);
        }
    }

    private void refreshEffectControls() {
        AudioDeviceProfile profile = activeProfile;
        String implementation = profile == null
                ? AudioEffectController.IMPLEMENTATION_DOLBY : profile.implementation;
        boolean dolby = AudioEffectController.IMPLEMENTATION_DOLBY.equals(implementation);
        boolean miSound = AudioEffectController.IMPLEMENTATION_MISOUND.equals(implementation);
        boolean off = AudioEffectController.IMPLEMENTATION_NONE.equals(implementation);
        boolean dolbyCustom = profile == null || "custom".equals(profile.dolbyEqPreset);
        boolean miSoundCustom = profile == null || "custom".equals(profile.miSoundEqPreset);

        dolbyCategory.setVisible(dolby);
        dolbyEqExpandPreference.setVisible(dolby && dolbyCustom);
        dolbyEqExpandPreference.setSummary(dolbyEqualizerExpanded
                ? R.string.equalizer_collapse : R.string.equalizer_expand);
        dolbyEqCategory.setVisible(dolby && dolbyCustom && dolbyEqualizerExpanded);
        miSoundCategory.setVisible(miSound);
        miSoundEqExpandPreference.setVisible(miSound && miSoundCustom);
        miSoundEqExpandPreference.setSummary(miSoundEqualizerExpanded
                ? R.string.equalizer_collapse : R.string.equalizer_expand);
        miSoundEqCategory.setVisible(miSound && miSoundCustom && miSoundEqualizerExpanded);
        spatialPreference.setSummary(off
                ? R.string.effect_original_summary : R.string.immersive_sound_summary);
    }

    private int personalStatus(boolean enabled, boolean configured) {
        if (!configured) return R.string.personal_audio_not_configured;
        if (enabled && activeProfile != null
                && !AudioDeviceProfile.IMPLEMENTATION_MISOUND.equals(
                        activeProfile.implementation)) {
            return R.string.personal_audio_waiting_for_misound;
        }
        return enabled ? R.string.personal_audio_active : R.string.personal_audio_saved;
    }

    private void setControlsEnabled(boolean enabled) {
        AudioDeviceProfile profile = activeProfile;
        boolean off = profile != null && AudioEffectController.IMPLEMENTATION_NONE.equals(
                profile.implementation);
        boolean immersiveEnabled = profile != null && profile.spatialEnabled && !off;
        boolean headTrackingEnabled = immersiveEnabled && profile.headTrackingMode
                != AudioDeviceProfile.HEAD_TRACKING_DISABLED;
        implementationPreference.setEnabled(enabled);
        spatialPreference.setEnabled(enabled && !off);
        headTrackingPreference.setEnabled(enabled && immersiveEnabled);
        headTrackingDebugPreference.setEnabled(enabled && headTrackingEnabled);
        dolbyCategory.setEnabled(enabled);
        dolbyEqCategory.setEnabled(enabled);
        miSoundCategory.setEnabled(enabled);
        miSoundEqCategory.setEnabled(enabled);
        dolbyEqExpandPreference.setEnabled(enabled);
        dolbyEqResetPreference.setEnabled(enabled);
        miSoundEqExpandPreference.setEnabled(enabled);
        miSoundEqResetPreference.setEnabled(enabled);
        soundIdStatusPreference.setEnabled(enabled);
        hearingStatusPreference.setEnabled(enabled);
        earScanStatusPreference.setEnabled(enabled);
        headsetModelStatusPreference.setEnabled(enabled);
    }

    private void bindPreference(Preference preference) {
        preference.setPersistent(false);
        preference.setOnPreferenceChangeListener(this);
        if (preference instanceof ListPreference) {
            ((ListPreference) preference).setSummaryProvider(
                    ListPreference.SimpleSummaryProvider.getInstance());
        }
    }

    private boolean openPersonalAudioScreen(Class<?> activityClass) {
        startActivity(new Intent(requireContext(), activityClass));
        return true;
    }

    private void updateBandSummary(SeekBarPreference preference, boolean dolby) {
        float value = dolby ? preference.getValue() / 4f : preference.getValue();
        preference.setSummary(String.format(Locale.getDefault(), "%+.2f dB", value));
    }

    private static int parseBand(String key, String prefix, int count) {
        int band = parseInt(key.substring(prefix.length()), -1);
        return band >= 0 && band < count ? band : -1;
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
