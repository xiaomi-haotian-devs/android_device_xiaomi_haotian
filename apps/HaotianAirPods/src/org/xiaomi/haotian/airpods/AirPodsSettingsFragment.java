/* SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later */
package org.xiaomi.haotian.airpods;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class AirPodsSettingsFragment extends PreferenceFragmentCompat {
    static final String ARG_DEVICE_ADDRESS = "device_address";
    static final String ARG_ROOT_KEY = "root_key";
    private static final long REFRESH_MS = 750;
    private static final long PENDING_WRITE_TIMEOUT_MS = 3_000;
    private static final int ATT_TRANSPARENCY = 0x18;
    private static final int ATT_LOUD_SOUND_REDUCTION = 0x1b;
    private static final int ATT_HEARING_AID = 0x2a;
    private static final int AACP_CUSTOM_EQ = 0x63;
    private static final ComponentName AUDIO_SERVICE = new ComponentName(
            "org.xiaomi.haotian.airpods",
            "org.xiaomi.haotian.airpods.AirPodsControlService");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private IAirPodsService service;
    private String address = "";
    private boolean bound;
    private AirPodsState lastState;
    private CustomEqSettings pendingCustomEq;
    private long pendingCustomEqDeadline;
    private TransparencySettings pendingTransparency;
    private long pendingTransparencyDeadline;
    private HearingAidSettings pendingHearingAid;
    private long pendingHearingAidDeadline;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshState();
            handler.postDelayed(this, REFRESH_MS);
        }
    };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IAirPodsService.Stub.asInterface(binder);
            refreshState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            showDisconnected();
        }
    };

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        String requestedRoot = getArguments() == null ? null
                : getArguments().getString(ARG_ROOT_KEY);
        setPreferencesFromResource(R.xml.airpods_settings, requestedRoot);
        address = getArguments() == null ? "" : nonNull(
                getArguments().getString(ARG_DEVICE_ADDRESS));
        bindPreferences();
        showDisconnected();
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent().setComponent(AUDIO_SERVICE);
        bound = requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        handler.post(refresh);
    }

    @Override
    public void onResume() {
        super.onResume();
        CharSequence title = getPreferenceScreen() == null
                ? null : getPreferenceScreen().getTitle();
        requireActivity().setTitle(title == null || title.length() == 0
                ? getText(R.string.app_name) : title);
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(refresh);
        if (bound) requireContext().unbindService(serviceConnection);
        bound = false;
        service = null;
        super.onStop();
    }

    private void refreshState() {
        IAirPodsService current = service;
        if (current == null) return;
        try {
            AirPodsState state = current.getAirPodsState();
            if (state == null || !state.connected || (!address.isEmpty()
                    && !address.equalsIgnoreCase(state.deviceAddress))) {
                showDisconnected();
                return;
            }
            lastState = state;
            renderState(state);
        } catch (RemoteException exception) {
            showDisconnected();
        }
    }

    private void showDisconnected() {
        lastState = null;
        pendingCustomEq = null;
        pendingTransparency = null;
        pendingHearingAid = null;
        setSummary("connection", service == null
                ? R.string.waiting_for_status : R.string.disconnected);
        setSummary("left", R.string.wear_unknown);
        setSummary("right", R.string.wear_unknown);
        setSummary("case", R.string.wear_disconnected);
        for (String key : CONTROL_KEYS) setEnabled(key, false);
        for (String key : HEARING_AID_DATA_KEYS) setEnabled(key, false);
        setEnabled("device_name", false);
        setEnabled("disconnect", false);
        for (String key : ABOUT_KEYS) setSummary(key, R.string.unavailable_value);
        renderNearbyState();
    }

    private void renderNearbyState() {
        AirPodsNearbyState nearby = HaotianAirPodsApplication.getNearbyState();
        if (nearby == null || (!address.isEmpty()
                && !address.equalsIgnoreCase(nearby.classicAddress))) return;
        setSummary("connection", nearbyConnection(nearby.connectionState));
        setSummary("left", nearbyWearBattery(
                nearby.leftInEar, nearby.leftBattery, nearby.leftCharging));
        setSummary("right", nearbyWearBattery(
                nearby.rightInEar, nearby.rightBattery, nearby.rightCharging));
        setSummary("case", nearbyBattery(nearby.caseBattery, nearby.caseCharging));
    }

    private void bindPreferences() {
        bindByteList("noise_mode", 0x0d);
        bindStemAction("left_action", true);
        bindStemAction("right_action", false);
        bindListeningModes("press_hold_modes_left");
        bindListeningModes("press_hold_modes_right");
        bindCallAction("mute_action");
        bindCallAction("hang_up_action");
        bindBoolean("adaptive_volume", 0x26);
        bindBoolean("conversation_awareness", 0x28);
        bindReversedSeekBar("adaptive_strength", 0x2e);
        bindBoolean("automatic_ear_detection", 0x0a);
        bindBoolean("automatic_connection", 0x20);
        bindByteList("microphone_mode", 0x01);
        bindBoolean("sleep_detection", 0x35);
        bindHeadGestures();
        bindBoolean("optimized_charging", 0x3b);
        bindByteList("press_speed", 0x17);
        bindByteList("press_hold_duration", 0x18);
        bindBoolean("one_bud_anc", 0x1b);
        bindAttBoolean("loud_sound_reduction");
        bindAttBoolean("hearing_loud_sound_reduction");
        bindAttBoolean("accessibility_loud_sound_reduction");
        bindBoolean("ppe", 0x37);
        bindHearingAid();
        bindCustomEqualizer();
        bindTransparency();
        bindChimeVolume();
        bindBoolean("volume_swipe", 0x25);
        bindByteList("volume_swipe_speed", 0x23);
        bindBoolean("allow_off", 0x34);

        EditTextPreference name = findPreference("device_name");
        if (name != null) {
            name.setOnBindEditTextListener(editText -> {
                editText.setSingleLine(true);
                editText.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            });
            name.setOnPreferenceChangeListener((preference, value) -> rename((String) value));
        }
        Preference disconnect = findPreference("disconnect");
        if (disconnect != null) disconnect.setOnPreferenceClickListener(preference -> disconnect());
    }

    private void renderState(AirPodsState state) {
        setSummary("connection", state.modelNumber.isEmpty()
                ? getString(R.string.connected) : state.modelNumber);
        setSummary("left", wearBattery(state.leftWear, state.leftBattery,
                state.leftBatteryStatus));
        setSummary("right", wearBattery(state.rightWear, state.rightBattery,
                state.rightBatteryStatus));
        setSummary("case", battery(state.caseBattery, state.caseBatteryStatus));
        EditTextPreference name = findPreference("device_name");
        if (name != null) {
            String displayName = localDisplayName(state);
            name.setEnabled(true);
            name.setText(displayName);
            name.setSummary(displayName);
        }
        setEnabled("disconnect", true);

        byte[] allowOffValue = state.getControlValue(0x34);
        boolean allowOff = allowOffValue == null || isEnabled(allowOffValue);
        Integer noiseMode = valueOrDefault(state, 0x0d, 0, AirPodsState.NOISE_TRANSPARENCY);
        Integer leftAction = state.leftLongPressAction;
        Integer rightAction = state.rightLongPressAction;
        setNoiseMode(noiseMode);
        setList("left_action", leftAction);
        setList("right_action", rightAction);
        setSummary("left_press_screen", stemActionSummary(leftAction));
        setSummary("right_press_screen", stemActionSummary(rightAction));
        setListeningModes("press_hold_modes_left", state.getControlValue(0x1a),
                leftAction, allowOff);
        setListeningModes("press_hold_modes_right", state.getControlValue(0x1a),
                rightAction, allowOff);
        setCallActions(state.getControlValue(0x24));
        setSwitch("adaptive_volume", state.getControlValue(0x26));
        setSwitch("conversation_awareness", state.getControlValue(0x28));
        setReversedProgress("adaptive_strength", state.getControlValue(0x2e));
        setSwitch("automatic_ear_detection", state.getControlValue(0x0a));
        setSwitch("automatic_connection", state.getControlValue(0x20));
        Integer microphoneMode = valueOrDefault(state, 0x01, 0, 0);
        setList("microphone_mode", microphoneMode);
        setSummary("microphone_screen", microphoneSummary(microphoneMode));
        setSwitch("sleep_detection", state.getControlValue(0x35));
        renderHeadGestures();
        setSwitch("optimized_charging", state.getControlValue(0x3b));
        setList("press_speed", valueOrDefault(state, 0x17, 0, 0));
        setList("press_hold_duration", valueOrDefault(state, 0x18, 0, 0));
        setSwitch("one_bud_anc", state.getControlValue(0x1b));
        renderHearingProtection(state);
        renderHearingAid(state);
        renderCustomEqualizer(state);
        renderTransparency(state);
        setProgress("chime_volume", state.getControlValue(0x1f));
        setSwitch("volume_swipe", state.getControlValue(0x25));
        setList("volume_swipe_speed", valueOrDefault(state, 0x23, 0, 1));
        setSwitch("allow_off", state.getControlValue(0x34));
        if (allowOffValue == null) {
            SwitchPreferenceCompat allowOffPreference = findPreference("allow_off");
            if (allowOffPreference != null) allowOffPreference.setChecked(true);
        }

        setSummary("about_model", state.modelNumber);
        setSummary("about_serial", state.serialNumber);
        setSummary("about_left_serial", state.leftSerialNumber);
        setSummary("about_right_serial", state.rightSerialNumber);
        setSummary("about_version1", state.version1);
        setSummary("about_version2", state.version2);
        setSummary("about_version3", state.version3);
        setSummary("about_hardware", state.hardwareRevision);
        setSummary("about_screen", state.modelNumber);
    }

    private void bindBoolean(String key, int identifier) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference != null) preference.setOnPreferenceChangeListener((ignored, value) ->
                sendControl(identifier, new byte[] {(byte) ((Boolean) value ? 1 : 2)}));
    }

    private void bindAttBoolean(String key) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference != null) {
            preference.setOnPreferenceChangeListener((ignored, value) ->
                    sendAttValue(ATT_LOUD_SOUND_REDUCTION,
                            new byte[] {(byte) ((Boolean) value ? 1 : 0)}));
        }
    }

    private void bindHeadGestures() {
        SwitchPreferenceCompat preference = findPreference(HeadGestureReceiver.PREF_ENABLED);
        if (preference == null) return;
        preference.setOnPreferenceChangeListener((ignored, value) -> {
            boolean enabled = (Boolean) value;
            Context context = requireContext();
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean(HeadGestureReceiver.PREF_ENABLED, enabled).apply();
            Intent serviceIntent = new Intent(context, HeadGestureService.class);
            if (!enabled) {
                context.stopService(serviceIntent);
            } else {
                try {
                    TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
                    if (telephony != null && telephony.getCallState()
                            == TelephonyManager.CALL_STATE_RINGING) {
                        context.startForegroundService(
                                serviceIntent.setAction(HeadGestureService.ACTION_START));
                    }
                } catch (RuntimeException ignoredException) {
                }
            }
            setSummary("head_gestures", enabled
                    ? getText(R.string.on) : getText(R.string.off));
            return true;
        });
    }

    private void bindCustomEqualizer() {
        ListPreference mode = findPreference("custom_eq_mode");
        if (mode != null) {
            mode.setOnPreferenceChangeListener((ignored, value) -> {
                CustomEqSettings current = currentCustomEq();
                if (current == null) return false;
                return sendCustomEq(current.withEnabled("2".equals(value)));
            });
        }
        bindCustomEqBand("custom_eq_low", 0);
        bindCustomEqBand("custom_eq_mid", 1);
        bindCustomEqBand("custom_eq_high", 2);
        Preference reset = findPreference("custom_eq_reset");
        if (reset != null) {
            reset.setOnPreferenceClickListener(ignored -> {
                CustomEqSettings current = currentCustomEq();
                return current != null && sendCustomEq(current.withBands(50, 50, 50));
            });
        }
    }

    private void bindHearingAid() {
        SwitchPreferenceCompat enabled = findPreference("hearing_aid_enabled");
        if (enabled != null) {
            enabled.setOnPreferenceChangeListener((ignored, value) -> {
                if (!((Boolean) value)) return setHearingAidEnabled(false);
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.hearing_aid_enable_title)
                        .setMessage(R.string.hearing_aid_enable_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.enable,
                                (dialog, which) -> setHearingAidEnabled(true))
                        .show();
                return false;
            });
        }
        bindBoolean("hearing_aid_gain_swipe", 0x2f);
        bindHearingAidSlider("hearing_aid_amplification",
                (current, value) -> current.withAmplification(bipolar(value)));
        bindHearingAidSlider("hearing_aid_balance",
                (current, value) -> current.withBalance(bipolar(value)));
        bindHearingAidSlider("hearing_aid_tone",
                (current, value) -> current.withTone(bipolar(value)));
        bindHearingAidSlider("hearing_aid_ambient",
                (current, value) -> current.withAmbientNoiseReduction(value / 100f));
        SwitchPreferenceCompat conversation =
                findPreference("hearing_aid_conversation_boost");
        if (conversation != null) {
            conversation.setOnPreferenceChangeListener((ignored, value) -> {
                HearingAidSettings current = currentHearingAid();
                return current != null && sendHearingAid(
                        current.withConversationBoost((Boolean) value));
            });
        }
        for (int channel : new int[] {HearingAidSettings.LEFT, HearingAidSettings.RIGHT}) {
            String side = channel == HearingAidSettings.LEFT ? "left" : "right";
            for (int band = 0; band < HearingAidSettings.EQ_BANDS; band++) {
                final int requestedChannel = channel;
                final int requestedBand = band;
                bindHearingAidSlider("hearing_test_" + side + "_" + (band + 1),
                        (current, value) -> current.withEqualizerBand(
                                requestedChannel, requestedBand, value));
            }
        }
    }

    private void bindHearingAidSlider(String key, HearingAidEditor editor) {
        SeekBarPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setOnPreferenceChangeListener((ignored, value) -> {
            HearingAidSettings current = currentHearingAid();
            return current != null && sendHearingAid(editor.edit(current, (Integer) value));
        });
    }

    private boolean setHearingAidEnabled(boolean enabled) {
        boolean hearingAid = sendControl(0x2c,
                new byte[] {0x01, (byte) (enabled ? 0x01 : 0x02)});
        boolean assist = sendControl(0x33,
                new byte[] {(byte) (enabled ? 0x01 : 0x02)});
        if (enabled && hearingAid && assist) {
            TransparencySettings transparency = currentTransparency();
            if (transparency != null && transparency.isEnabled()) {
                sendTransparency(transparency.withEnabled(false));
            }
        }
        return hearingAid && assist;
    }

    private void bindCustomEqBand(String key, int band) {
        SeekBarPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setOnPreferenceChangeListener((ignored, value) -> {
            CustomEqSettings current = currentCustomEq();
            if (current == null) return false;
            int low = band == 0 ? (Integer) value : current.low;
            int mid = band == 1 ? (Integer) value : current.mid;
            int high = band == 2 ? (Integer) value : current.high;
            return sendCustomEq(current.withBands(low, mid, high));
        });
    }

    private void bindTransparency() {
        SwitchPreferenceCompat enabled = findPreference("transparency_enabled");
        if (enabled != null) {
            enabled.setOnPreferenceChangeListener((ignored, value) -> {
                TransparencySettings current = currentTransparency();
                return current != null
                        && sendTransparency(current.withEnabled((Boolean) value));
            });
        }
        bindTransparencySlider("transparency_amplification",
                (current, value) -> current.withAmplification(bipolar(value)));
        bindTransparencySlider("transparency_balance",
                (current, value) -> current.withBalance(bipolar(value)));
        bindTransparencySlider("transparency_tone",
                (current, value) -> current.withTone(bipolar(value)));
        bindTransparencySlider("transparency_ambient",
                (current, value) -> current.withAmbientNoiseReduction(value / 100f));
        SwitchPreferenceCompat conversation =
                findPreference("transparency_conversation_boost");
        if (conversation != null) {
            conversation.setOnPreferenceChangeListener((ignored, value) -> {
                TransparencySettings current = currentTransparency();
                return current != null && sendTransparency(
                        current.withConversationBoost((Boolean) value));
            });
        }
        for (int band = 0; band < TransparencySettings.EQ_BANDS; band++) {
            final int requestedBand = band;
            bindTransparencySlider("transparency_eq_" + (band + 1),
                    (current, value) -> current.withEqualizerBand(requestedBand, value));
        }
    }

    private void bindTransparencySlider(String key, TransparencyEditor editor) {
        SeekBarPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setOnPreferenceChangeListener((ignored, value) -> {
            TransparencySettings current = currentTransparency();
            return current != null && sendTransparency(editor.edit(current, (Integer) value));
        });
    }

    private void bindByteList(String key, int identifier) {
        ListPreference preference = findPreference(key);
        if (preference != null) preference.setOnPreferenceChangeListener((ignored, value) -> {
            try {
                int requested = Integer.parseInt((String) value);
                if (identifier == 0x0d && requested == AirPodsState.NOISE_OFF
                        && !isEnabled(lastState == null
                                ? null : lastState.getControlValue(0x34))) {
                    if (!sendControl(0x34, new byte[] {1})) return false;
                    SwitchPreferenceCompat allowOff = findPreference("allow_off");
                    if (allowOff != null) allowOff.setChecked(true);
                    handler.postDelayed(() -> sendControl(identifier,
                            new byte[] {(byte) requested}), 150);
                    return true;
                }
                return sendControl(identifier, new byte[] {(byte) requested});
            } catch (NumberFormatException exception) {
                return false;
            }
        });
    }

    private void bindStemAction(String key, boolean left) {
        ListPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setOnPreferenceChangeListener((ignored, value) -> {
            try {
                int action = Integer.parseInt((String) value);
                IAirPodsService current = service;
                if (current == null) return false;
                boolean accepted = current.setAirPodsLongPressAction(address, left, action);
                if (!accepted) Toast.makeText(requireContext(), R.string.control_failed,
                        Toast.LENGTH_SHORT).show();
                return accepted;
            } catch (RemoteException | NumberFormatException exception) {
                return false;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void bindListeningModes(String key) {
        MultiSelectListPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setOnPreferenceChangeListener((ignored, value) -> {
            int bits = 0;
            for (String selected : (Set<String>) value) {
                try {
                    bits |= Integer.parseInt(selected);
                } catch (NumberFormatException exception) {
                    return false;
                }
            }
            if (Integer.bitCount(bits & 0x0f) < 2) return false;
            return sendControl(0x1a, new byte[] {(byte) bits});
        });
    }

    private void bindCallAction(String key) {
        ListPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setOnPreferenceChangeListener((ignored, value) -> {
            try {
                return sendControl(0x24,
                        new byte[] {0, (byte) Integer.parseInt((String) value)});
            } catch (NumberFormatException exception) {
                return false;
            }
        });
    }

    private void bindReversedSeekBar(String key, int identifier) {
        SeekBarPreference preference = findPreference(key);
        if (preference != null) preference.setOnPreferenceChangeListener((ignored, value) ->
                sendControl(identifier, new byte[] {(byte) (100 - (Integer) value)}));
    }

    private void bindChimeVolume() {
        SeekBarPreference preference = findPreference("chime_volume");
        if (preference != null) preference.setOnPreferenceChangeListener((ignored, value) ->
                sendControl(0x1f, new byte[] {(byte) ((Integer) value).intValue(), 0x50}));
    }

    private boolean rename(String name) {
        IAirPodsService current = service;
        if (current == null || name == null || name.trim().isEmpty()) return false;
        try {
            boolean accepted = current.renameAirPods(address, name.trim());
            if (accepted) {
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null && !address.isEmpty()) {
                        adapter.getRemoteDevice(address).setAlias(name.trim());
                    }
                } catch (RuntimeException ignored) {
                }
            }
            if (!accepted) Toast.makeText(requireContext(), R.string.rename_failed,
                    Toast.LENGTH_SHORT).show();
            return accepted;
        } catch (RemoteException exception) {
            return false;
        }
    }

    private boolean sendControl(int identifier, byte[] value) {
        IAirPodsService current = service;
        if (current == null) return false;
        try {
            boolean accepted = current.setAirPodsControlValue(address, identifier, value);
            if (!accepted) Toast.makeText(requireContext(), R.string.control_failed,
                    Toast.LENGTH_SHORT).show();
            return accepted;
        } catch (RemoteException exception) {
            return false;
        }
    }

    private boolean sendAttValue(int handle, byte[] value) {
        IAirPodsService current = service;
        if (current == null) return false;
        try {
            boolean accepted = current.setAirPodsAttValue(address, handle, value);
            if (!accepted) Toast.makeText(requireContext(), R.string.control_failed,
                    Toast.LENGTH_SHORT).show();
            return accepted;
        } catch (RemoteException exception) {
            return false;
        }
    }

    private boolean sendCustomEq(CustomEqSettings settings) {
        IAirPodsService current = service;
        if (current == null) return false;
        try {
            boolean accepted = current.sendAirPodsAacpMessage(
                    address, AACP_CUSTOM_EQ, settings.toBody());
            if (accepted) {
                pendingCustomEq = settings;
                pendingCustomEqDeadline = SystemClock.elapsedRealtime()
                        + PENDING_WRITE_TIMEOUT_MS;
            } else {
                Toast.makeText(requireContext(), R.string.control_failed,
                        Toast.LENGTH_SHORT).show();
            }
            return accepted;
        } catch (RemoteException exception) {
            return false;
        }
    }

    private boolean sendTransparency(TransparencySettings settings) {
        byte[] requested = settings.toByteArray();
        if (!sendAttValue(ATT_TRANSPARENCY, requested)) return false;
        pendingTransparency = settings;
        pendingTransparencyDeadline = SystemClock.elapsedRealtime()
                + PENDING_WRITE_TIMEOUT_MS;
        return true;
    }

    private boolean sendHearingAid(HearingAidSettings settings) {
        byte[] requested = settings.toByteArray();
        if (!sendAttValue(ATT_HEARING_AID, requested)) return false;
        pendingHearingAid = settings;
        pendingHearingAidDeadline = SystemClock.elapsedRealtime()
                + PENDING_WRITE_TIMEOUT_MS;
        return true;
    }

    private CustomEqSettings currentCustomEq() {
        if (pendingCustomEq != null
                && SystemClock.elapsedRealtime() <= pendingCustomEqDeadline) {
            return pendingCustomEq;
        }
        pendingCustomEq = null;
        AirPodsState state = lastState;
        if (state == null || !state.connected) return null;
        CustomEqSettings parsed =
                CustomEqSettings.parse(state.getAacpMessageValue(AACP_CUSTOM_EQ));
        return parsed == null ? CustomEqSettings.defaults() : parsed;
    }

    private TransparencySettings currentTransparency() {
        if (pendingTransparency != null
                && SystemClock.elapsedRealtime() <= pendingTransparencyDeadline) {
            return pendingTransparency;
        }
        pendingTransparency = null;
        AirPodsState state = lastState;
        return state == null ? null
                : TransparencySettings.parse(state.getAttValue(ATT_TRANSPARENCY));
    }

    private HearingAidSettings currentHearingAid() {
        if (pendingHearingAid != null
                && SystemClock.elapsedRealtime() <= pendingHearingAidDeadline) {
            return pendingHearingAid;
        }
        pendingHearingAid = null;
        AirPodsState state = lastState;
        return state == null ? null
                : HearingAidSettings.parse(state.getAttValue(ATT_HEARING_AID));
    }

    private void renderHearingProtection(AirPodsState state) {
        byte[] loudSoundReduction = state.getAttValue(ATT_LOUD_SOUND_REDUCTION);
        boolean loudSoundAvailable = state.attConnected && loudSoundReduction != null
                && loudSoundReduction.length > 0;
        setAttSwitch("loud_sound_reduction", loudSoundReduction, loudSoundAvailable);
        setAttSwitch("hearing_loud_sound_reduction", loudSoundReduction,
                loudSoundAvailable);
        setAttSwitch("accessibility_loud_sound_reduction", loudSoundReduction,
                loudSoundAvailable);

        byte[] ppe = state.getControlValue(0x37);
        setSwitch("ppe", ppe);
        setEnabled("hearing_health", true);
        setEnabled("hearing_protection_screen", true);
    }

    private void renderHeadGestures() {
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean(HeadGestureReceiver.PREF_ENABLED, false);
        setEnabled("head_gestures", true);
        setEnabled(HeadGestureReceiver.PREF_ENABLED, true);
        SwitchPreferenceCompat preference =
                findPreference(HeadGestureReceiver.PREF_ENABLED);
        if (preference != null) preference.setChecked(enabled);
        setSummary("head_gestures", enabled ? getText(R.string.on) : getText(R.string.off));
    }

    private void renderHearingAid(AirPodsState state) {
        byte[] hearingControl = state.getControlValue(0x2c);
        byte[] assistControl = state.getControlValue(0x33);
        byte[] reported = state.getAttValue(ATT_HEARING_AID);
        HearingAidSettings parsed = HearingAidSettings.parse(reported);
        long now = SystemClock.elapsedRealtime();
        if (pendingHearingAid != null) {
            if (pendingHearingAid.hasValue(reported) || now > pendingHearingAidDeadline) {
                pendingHearingAid = null;
            } else {
                parsed = pendingHearingAid;
            }
        }
        boolean controlAvailable = hearingControl != null && hearingControl.length > 1
                && assistControl != null && assistControl.length > 0;
        boolean dataAvailable = state.attConnected && parsed != null;
        boolean hearingEnabled = controlAvailable && hearingControl[1] == 0x01
                && assistControl[0] == 0x01;
        setEnabled("hearing_aid", controlAvailable && dataAvailable);
        setEnabled("hearing_aid_enabled", controlAvailable && dataAvailable);
        SwitchPreferenceCompat enabled = findPreference("hearing_aid_enabled");
        if (enabled != null && controlAvailable) enabled.setChecked(hearingEnabled);
        setSummary("hearing_aid", hearingEnabled ? getText(R.string.on)
                : getText(R.string.off));

        setEnabled("hearing_aid_adjustments", dataAvailable);
        setEnabled("hearing_test", dataAvailable);
        setEnabled("hearing_test_left", dataAvailable);
        setEnabled("hearing_test_right", dataAvailable);
        setSwitch("hearing_aid_gain_swipe", state.getControlValue(0x2f));
        setEnabled("hearing_aid_gain_swipe", dataAvailable
                && state.getControlValue(0x2f) != null);
        for (String key : HEARING_AID_DATA_KEYS) setEnabled(key, dataAvailable);
        if (!dataAvailable) return;

        setSeekBar("hearing_aid_amplification",
                progressFromBipolar(parsed.amplification()), true);
        setSeekBar("hearing_aid_balance", progressFromBipolar(parsed.balance()), true);
        setSeekBar("hearing_aid_tone", progressFromBipolar(parsed.tone()), true);
        setSeekBar("hearing_aid_ambient",
                Math.round(parsed.ambientNoiseReduction() * 100f), true);
        SwitchPreferenceCompat conversation =
                findPreference("hearing_aid_conversation_boost");
        if (conversation != null) conversation.setChecked(parsed.conversationBoost());
        for (int channel : new int[] {HearingAidSettings.LEFT, HearingAidSettings.RIGHT}) {
            String side = channel == HearingAidSettings.LEFT ? "left" : "right";
            for (int band = 0; band < HearingAidSettings.EQ_BANDS; band++) {
                setSeekBar("hearing_test_" + side + "_" + (band + 1),
                        Math.round(parsed.equalizerBand(channel, band)), true);
            }
        }
    }

    private void renderCustomEqualizer(AirPodsState state) {
        byte[] reported = state.getAacpMessageValue(AACP_CUSTOM_EQ);
        CustomEqSettings parsed = CustomEqSettings.parse(reported);
        if (parsed == null) parsed = CustomEqSettings.defaults();
        long now = SystemClock.elapsedRealtime();
        if (pendingCustomEq != null) {
            if (Arrays.equals(pendingCustomEq.toBody(), reported)
                    || now > pendingCustomEqDeadline) {
                pendingCustomEq = null;
            } else {
                parsed = pendingCustomEq;
            }
        }
        boolean available = parsed != null;
        setEnabled("equalizer", available);
        setEnabled("custom_eq_mode", available);
        setVisible("custom_eq_controls", available && parsed.isEnabled());
        if (!available) return;

        ListPreference mode = findPreference("custom_eq_mode");
        if (mode != null) mode.setValue(Integer.toString(parsed.state));
        setSummary("equalizer", parsed.isEnabled()
                ? getText(R.string.custom) : getText(R.string.recommended));
        setSeekBar("custom_eq_low", parsed.low, parsed.isEnabled());
        setSeekBar("custom_eq_mid", parsed.mid, parsed.isEnabled());
        setSeekBar("custom_eq_high", parsed.high, parsed.isEnabled());
        setEnabled("custom_eq_reset", parsed.isEnabled()
                && (parsed.low != 50 || parsed.mid != 50 || parsed.high != 50));
    }

    private void renderTransparency(AirPodsState state) {
        byte[] reported = state.getAttValue(ATT_TRANSPARENCY);
        TransparencySettings parsed = TransparencySettings.parse(reported);
        long now = SystemClock.elapsedRealtime();
        if (pendingTransparency != null) {
            if (pendingTransparency.hasValue(reported) || now > pendingTransparencyDeadline) {
                pendingTransparency = null;
            } else {
                parsed = pendingTransparency;
            }
        }
        boolean available = state.attConnected && parsed != null;
        setEnabled("transparency_screen", available);
        for (String key : TRANSPARENCY_KEYS) setEnabled(key, available);
        if (!available) return;

        SwitchPreferenceCompat enabled = findPreference("transparency_enabled");
        if (enabled != null) enabled.setChecked(parsed.isEnabled());
        setSeekBar("transparency_amplification",
                progressFromBipolar(parsed.amplification()), true);
        setSeekBar("transparency_balance", progressFromBipolar(parsed.balance()), true);
        setSeekBar("transparency_tone", progressFromBipolar(parsed.tone()), true);
        setSeekBar("transparency_ambient",
                Math.round(parsed.ambientNoiseReduction() * 100f), true);
        SwitchPreferenceCompat conversation =
                findPreference("transparency_conversation_boost");
        if (conversation != null) conversation.setChecked(parsed.conversationBoost());
        for (int band = 0; band < TransparencySettings.EQ_BANDS; band++) {
            setSeekBar("transparency_eq_" + (band + 1),
                    Math.round(parsed.equalizerBand(band)), true);
        }
    }

    private void setAttSwitch(String key, byte[] value, boolean available) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference == null) return;
        preference.setEnabled(available);
        if (available) preference.setChecked(value[0] == 1);
    }

    private void setSeekBar(String key, int value, boolean enabled) {
        SeekBarPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setEnabled(enabled);
        preference.setValue(Math.max(preference.getMin(),
                Math.min(preference.getMax(), value)));
    }

    private void setVisible(String key, boolean visible) {
        Preference preference = findPreference(key);
        if (preference != null) preference.setVisible(visible);
    }

    private static int progressFromBipolar(float value) {
        return Math.round((Math.max(-1f, Math.min(1f, value)) + 1f) * 50f);
    }

    private static float bipolar(int progress) {
        return Math.max(-1f, Math.min(1f, (progress - 50) / 50f));
    }

    private boolean disconnect() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || address.isEmpty()) return false;
            BluetoothDevice device = adapter.getRemoteDevice(address);
            device.disconnect();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String localDisplayName(AirPodsState state) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && !address.isEmpty()) {
                String alias = adapter.getRemoteDevice(address).getAlias();
                if (alias != null && !alias.isEmpty()) return alias;
            }
        } catch (RuntimeException ignored) {
        }
        return state.displayName;
    }

    private void setSwitch(String key, byte[] value) {
        SwitchPreferenceCompat preference = findPreference(key);
        if (preference == null) return;
        // A missing initial notification does not mean an AirPods Pro 3 control is unsupported.
        preference.setEnabled(lastState != null);
        if (value != null && value.length > 0) preference.setChecked(value[0] == 1);
    }

    private void setList(String key, Integer value) {
        ListPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setEnabled(lastState != null);
        if (value != null) preference.setValue(Integer.toString(value));
    }

    private void setListeningModes(String key, byte[] value, Integer action,
            boolean allowOff) {
        MultiSelectListPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setEntries(allowOff ? R.array.noise_mode_entries
                : R.array.noise_mode_entries_no_off);
        preference.setEntryValues(allowOff ? R.array.noise_mode_bits
                : R.array.noise_mode_bits_no_off);
        preference.setEnabled(lastState != null && action != null && action == 1);
        Set<String> selected = new HashSet<>();
        // LibrePods defaults to Off, Noise Cancellation and Transparency until reported.
        int bits = value == null || value.length == 0 ? 0x07 : value[0] & 0xff;
        for (int bit : new int[] {1, 2, 4, 8}) {
            if ((bits & bit) != 0) selected.add(Integer.toString(bit));
        }
        preference.setValues(selected);
    }

    private void setCallActions(byte[] value) {
        boolean reported = value != null && value.length > 1;
        boolean available = lastState != null;
        int configuration = reported ? value[1] & 0xff : 3;
        ListPreference mute = findPreference("mute_action");
        ListPreference hangUp = findPreference("hang_up_action");
        if (mute != null) {
            mute.setEnabled(available);
            mute.setValue(Integer.toString(configuration));
        }
        if (hangUp != null) {
            hangUp.setEnabled(available);
            hangUp.setValue(Integer.toString(configuration));
        }
    }

    private void setNoiseMode(Integer value) {
        ListPreference preference = findPreference("noise_mode");
        if (preference == null) return;
        // Keep Off directly selectable in the main listening-mode control. The separate
        // allow-off switch only governs whether stem press-and-hold cycles through that mode.
        preference.setEntries(R.array.noise_mode_entries);
        preference.setEntryValues(R.array.noise_mode_values);
        preference.setEnabled(lastState != null);
        if (value != null) preference.setValue(Integer.toString(value));
    }

    private void setProgress(String key, byte[] value) {
        SeekBarPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setEnabled(lastState != null);
        if (value != null && value.length > 0) preference.setValue(value[0] & 0xff);
    }

    private void setReversedProgress(String key, byte[] value) {
        SeekBarPreference preference = findPreference(key);
        if (preference == null) return;
        preference.setEnabled(lastState != null);
        if (value != null && value.length > 0) {
            preference.setValue(Math.max(0, Math.min(100, 100 - (value[0] & 0xff))));
        }
    }

    private static Integer value(AirPodsState state, int identifier, int index) {
        byte[] value = state.getControlValue(identifier);
        return value == null || value.length <= index ? null : value[index] & 0xff;
    }

    private static Integer valueOrDefault(AirPodsState state, int identifier, int index,
            int fallback) {
        Integer reported = value(state, identifier, index);
        return reported == null ? fallback : reported;
    }

    private static boolean isEnabled(byte[] value) {
        return value != null && value.length > 0 && value[0] == 1;
    }

    private CharSequence stemActionSummary(Integer action) {
        if (action == null) return getText(R.string.unavailable_value);
        if (action == 1) return getText(R.string.noise_control);
        if (action == 5) return getText(R.string.voice_assistant);
        return getText(R.string.unavailable_value);
    }

    private CharSequence microphoneSummary(Integer mode) {
        if (mode == null) return getText(R.string.unavailable_value);
        if (mode == 0) return getText(R.string.microphone_automatic);
        if (mode == 1) return getText(R.string.microphone_always_right);
        if (mode == 2) return getText(R.string.microphone_always_left);
        return getText(R.string.unavailable_value);
    }

    private void setEnabled(String key, boolean enabled) {
        Preference preference = findPreference(key);
        if (preference != null) preference.setEnabled(enabled);
    }

    private void setSummary(String key, int summary) {
        Preference preference = findPreference(key);
        if (preference != null) preference.setSummary(summary);
    }

    private void setSummary(String key, CharSequence summary) {
        Preference preference = findPreference(key);
        if (preference != null) preference.setSummary(
                summary == null || summary.length() == 0
                        ? getText(R.string.unavailable_value) : summary);
    }

    private String wearBattery(int wear, int level, int status) {
        String wearText = getString(wearString(wear));
        String batteryText = battery(level, status);
        return level < 0 ? wearText
                : getString(R.string.wear_battery_format, wearText, batteryText);
    }

    private String battery(int level, int status) {
        if (level < 0) return getString(R.string.wear_disconnected);
        return status == AirPodsState.BATTERY_STATUS_CHARGING
                || status == AirPodsState.BATTERY_STATUS_OPTIMIZED_CHARGING
                ? getString(R.string.battery_charging_format, level)
                : getString(R.string.battery_format, level);
    }

    private String nearbyWearBattery(boolean worn, int level, boolean charging) {
        String wearText = getString(worn ? R.string.wear_in_ear : R.string.wear_out_of_ear);
        String batteryText = nearbyBattery(level, charging);
        return level < 0 ? wearText
                : getString(R.string.wear_battery_format, wearText, batteryText);
    }

    private String nearbyBattery(int level, boolean charging) {
        if (level < 0) return getString(R.string.wear_disconnected);
        return charging ? getString(R.string.battery_charging_format, level)
                : getString(R.string.battery_format, level);
    }

    private CharSequence nearbyConnection(int state) {
        switch (state) {
            case AirPodsNearbyState.CONNECTION_DISCONNECTED:
                return getText(R.string.nearby_disconnected);
            case AirPodsNearbyState.CONNECTION_IDLE:
                return getText(R.string.nearby_idle);
            case AirPodsNearbyState.CONNECTION_MUSIC:
                return getText(R.string.nearby_music);
            case AirPodsNearbyState.CONNECTION_CALL:
                return getText(R.string.nearby_call);
            case AirPodsNearbyState.CONNECTION_RINGING:
                return getText(R.string.nearby_ringing);
            case AirPodsNearbyState.CONNECTION_HANGING_UP:
                return getText(R.string.nearby_hanging_up);
            default:
                return getText(R.string.nearby);
        }
    }

    private static int wearString(int wear) {
        switch (wear) {
            case AirPodsState.WEAR_IN_EAR: return R.string.wear_in_ear;
            case AirPodsState.WEAR_OUT_OF_EAR: return R.string.wear_out_of_ear;
            case AirPodsState.WEAR_IN_CASE: return R.string.wear_in_case;
            case AirPodsState.WEAR_DISCONNECTED: return R.string.wear_disconnected;
            default: return R.string.wear_unknown;
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private interface TransparencyEditor {
        TransparencySettings edit(TransparencySettings current, int value);
    }

    private interface HearingAidEditor {
        HearingAidSettings edit(HearingAidSettings current, int value);
    }

    private static final String[] CONTROL_KEYS = {
            "noise_mode", "left_action", "right_action", "press_hold_modes_left",
            "press_hold_modes_right",
            "mute_action", "hang_up_action", "adaptive_volume", "conversation_awareness",
            "adaptive_strength", "automatic_ear_detection", "automatic_connection",
            "microphone_mode", "sleep_detection", "optimized_charging", "press_speed",
            "head_gestures", "head_gestures_enabled",
            "press_hold_duration", "one_bud_anc", "chime_volume", "volume_swipe",
            "volume_swipe_speed", "allow_off", "hearing_health", "hearing_protection_screen",
            "hearing_aid", "hearing_aid_enabled", "hearing_aid_adjustments",
            "hearing_aid_gain_swipe", "hearing_test", "hearing_test_left",
            "hearing_test_right",
            "loud_sound_reduction", "hearing_loud_sound_reduction",
            "accessibility_loud_sound_reduction", "ppe", "equalizer", "custom_eq_mode",
            "custom_eq_low", "custom_eq_mid", "custom_eq_high", "custom_eq_reset",
            "transparency_screen", "transparency_enabled", "transparency_amplification",
            "transparency_balance", "transparency_tone", "transparency_ambient",
            "transparency_conversation_boost", "transparency_eq_1", "transparency_eq_2",
            "transparency_eq_3", "transparency_eq_4", "transparency_eq_5",
            "transparency_eq_6", "transparency_eq_7", "transparency_eq_8"
    };
    private static final String[] HEARING_AID_DATA_KEYS = {
            "hearing_aid_amplification", "hearing_aid_balance", "hearing_aid_tone",
            "hearing_aid_ambient", "hearing_aid_conversation_boost",
            "hearing_test_left_1", "hearing_test_left_2", "hearing_test_left_3",
            "hearing_test_left_4", "hearing_test_left_5", "hearing_test_left_6",
            "hearing_test_left_7", "hearing_test_left_8", "hearing_test_right_1",
            "hearing_test_right_2", "hearing_test_right_3", "hearing_test_right_4",
            "hearing_test_right_5", "hearing_test_right_6", "hearing_test_right_7",
            "hearing_test_right_8"
    };
    private static final String[] TRANSPARENCY_KEYS = {
            "transparency_enabled", "transparency_amplification", "transparency_balance",
            "transparency_tone", "transparency_ambient", "transparency_conversation_boost",
            "transparency_eq_1", "transparency_eq_2", "transparency_eq_3",
            "transparency_eq_4", "transparency_eq_5", "transparency_eq_6",
            "transparency_eq_7", "transparency_eq_8"
    };
    private static final String[] ABOUT_KEYS = {
            "about_model", "about_serial", "about_left_serial", "about_right_serial",
            "about_version1", "about_version2", "about_version3", "about_hardware"
    };
}
