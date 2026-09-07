// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** SettingsLib Expressive UI, driven by the features and values actually reported by MDR. */
public final class SonySettingsFragment extends PreferenceFragmentCompat {
    private SonyControlClient client;
    private SonyState state = new SonyState("{}");
    private String requestedAddress;
    private String structure = "";
    private String selectionAttempt = "";
    private String lastError = "";
    private Preference information;
    private Preference refresh;
    private Preference spatial;
    private final List<Binding> bindings = new ArrayList<>();

    private static final class Binding {
        final Preference preference;
        final String key;
        final String target;
        Binding(Preference preference, String key, String target) {
            this.preference = preference; this.key = key; this.target = target;
        }
    }

    @Override public void onCreatePreferences(Bundle saved, String rootKey) {
        requestedAddress = SonyController.canonicalAddress(getArguments() == null ? null
                : getArguments().getString(SonySettingsActivity.EXTRA_ADDRESS));
        client = new SonyControlClient(requireContext(), this::onState);
        build();
    }
    @Override public void onStart() { super.onStart(); selectionAttempt = ""; client.start(); }
    @Override public void onStop() { client.stop(); super.onStop(); }

    private void onState(SonyState updated) {
        if (!isAdded()) return;
        state = updated;
        if (!requestedAddress.isEmpty() && !requestedAddress.equalsIgnoreCase(state.address)
                && !selectionAttempt.equals(requestedAddress)) {
            selectionAttempt = requestedAddress;
            client.select(requestedAddress);
        }
        boolean matches = requestedAddress.isEmpty() || requestedAddress.equalsIgnoreCase(state.address);
        String nextStructure = matches ? state.address + state.array("features") + ":"
                + state.array("bands").length() + ":" + state.array("eqPresets")
                + shape(state.array("general"), "index")
                + shape(state.array("assignments"), "location") + shape(state.array("paired"), "id") : "";
        if (!nextStructure.equals(structure)) { structure = nextStructure; build(); }
        information.setTitle(matches && !state.name.isEmpty() ? state.name : getString(R.string.app_name));
        information.setSummary(matches ? summary() : getString(R.string.connect_requested));
        refresh.setEnabled(matches && !state.address.isEmpty() && (!state.busy || !state.ready));
        spatial.setEnabled(matches && !state.address.isEmpty());
        for (Binding binding : bindings) update(binding, matches && state.canEdit(requestedAddress));
        Preference level = findPreference("soundPressure");
        if (level != null) level.setSummary(state.json.has("soundPressure")
                ? getString(R.string.sound_pressure_value, state.json.optInt("soundPressure")) : "—");
        Preference track = findPreference("track");
        if (track != null) track.setSummary(state.json.optString("track") + "\n" + state.json.optString("artist"));
        if (matches && !state.error.isEmpty() && !state.error.equals(lastError))
            Toast.makeText(requireContext(), getString(R.string.operation_error, state.error), Toast.LENGTH_LONG).show();
        lastError = state.error;
    }
    private static String shape(JSONArray items, String key) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) result.append(item.optString(key)).append(':')
                    .append(item.optString("options")).append(':').append(item.optString("title")).append(';');
        }
        return result.toString();
    }
    private String summary() {
        int label;
        switch (state.status) {
            case "connecting": label = R.string.connecting; break;
            case "initializing": case "syncing": label = R.string.syncing; break;
            case "applying": label = R.string.applying; break;
            case "retrying": label = R.string.retrying; break;
            case "ready": label = R.string.connected; break;
            default: label = R.string.disconnected;
        }
        StringBuilder result = new StringBuilder(getString(label));
        for (int i = 0; i < state.array("batteries").length(); i++) {
            JSONObject b = state.array("batteries").optJSONObject(i);
            if (b == null) continue;
            int part = b.optInt("part");
            String[] names = getResources().getStringArray(R.array.battery_parts);
            if (part >= 0 && part < names.length)
                result.append('\n').append(getString(R.string.battery_format, names[part], b.optInt("level")))
                        .append(b.optInt("charging") == 2 ? " · " + getString(R.string.charging) : "");
        }
        String firmware = state.json.optString("firmware");
        if (!firmware.isEmpty()) result.append('\n').append(getString(R.string.firmware_format, firmware));
        if (!state.error.isEmpty()) result.append('\n').append(state.error);
        String alert = state.json.optString("alert");
        if (!alert.isEmpty()) result.append('\n').append(alert);
        return result.toString();
    }
    private void build() {
        Context c = getPreferenceManager().getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen); bindings.clear();
        information = new Preference(c);
        information.setTitle(R.string.app_name); information.setSummary(R.string.disconnected);
        information.setIcon(R.drawable.ic_sony_headphones); information.setSelectable(false);
        screen.addPreference(information);
        refresh = new Preference(c); refresh.setTitle(R.string.refresh);
        refresh.setOnPreferenceClickListener(p -> {
            String address = requestedAddress.isEmpty() ? state.address : requestedAddress;
            client.select(address); client.refresh(address); return true;
        });
        screen.addPreference(refresh);
        spatial = new Preference(c); spatial.setTitle(R.string.spatial_audio);
        spatial.setSummary(R.string.spatial_audio_summary);
        spatial.setOnPreferenceClickListener(p -> { openAudio(false); return true; });
        screen.addPreference(spatial);
        Preference visualization = new Preference(c); visualization.setTitle(R.string.tracking_visualization);
        visualization.setOnPreferenceClickListener(p -> { openAudio(true); return true; });
        screen.addPreference(visualization);
        if (!requestedAddress.isEmpty() && !requestedAddress.equalsIgnoreCase(state.address)) return;

        if (state.has(5) || state.has(6) || state.has(7)) {
            PreferenceCategory playback = category(R.string.playback);
            if (state.has(5)) {
                Preference track = new Preference(c); track.setKey("track");
                track.setTitle(R.string.now_playing); track.setSelectable(false);
                playback.addPreference(track);
            }
            if (state.has(7)) range(playback, "playback.volume", R.string.headphone_volume, 0, 30);
            if (state.has(6)) {
                action(playback, "playback.command", R.string.play, 1);
                action(playback, "playback.command", R.string.pause, 2);
                action(playback, "playback.command", R.string.next_track, 3);
                action(playback, "playback.command", R.string.previous_track, 4);
            }
        }

        if (state.has(8) || state.has(9)) {
            PreferenceCategory noise = category(R.string.noise_control);
            if (state.has(8)) select(noise, "noise.mode", R.string.noise_mode, R.array.noise_modes,
                    state.has(9) ? new int[]{0, 1, 2} : new int[]{0, 1});
            if (state.has(9)) {
                range(noise, "noise.ambient", R.string.ambient_level, 0, 20);
                toggle(noise, "noise.voice", R.string.focus_voice);
            }
            if (state.has(10)) {
                toggle(noise, "noise.adaptive", R.string.adaptive_ambient);
                select(noise, "noise.sensitivity", R.string.adaptive_sensitivity,
                        R.array.adaptive_levels, new int[]{1, 2, 3});
            }
            if (state.has(19)) select(noise, "noise.button", R.string.noise_button,
                    R.array.noise_button_modes, new int[]{1, 2, 3, 4});
        }
        if (state.has(11)) {
            PreferenceCategory speak = category(R.string.speak_to_chat);
            toggle(speak, "speak.enabled", R.string.speak_to_chat);
            select(speak, "speak.sensitivity", R.string.speech_sensitivity,
                    R.array.speech_levels, new int[]{1, 2, 3});
            select(speak, "speak.timeout", R.string.speech_timeout,
                    R.array.speech_timeouts, new int[]{1, 2, 3, 4});
        }
        if (state.has(13) || state.has(14) || state.has(12)) {
            PreferenceCategory sound = category(R.string.sound);
            if (state.has(13)) {
                JSONArray presets = state.array("eqPresets");
                if (presets.length() > 0) {
                    ListPreference preset = new ListPreference(c);
                    preset.setTitle(R.string.eq_preset);
                    String[] labels = getResources().getStringArray(R.array.eq_presets);
                    CharSequence[] entries = new CharSequence[presets.length()];
                    CharSequence[] values = new CharSequence[presets.length()];
                    for (int i = 0; i < presets.length(); i++) {
                        int id = presets.optInt(i, -1);
                        entries[i] = id >= 0 && id < labels.length ? labels[id]
                                : getString(R.string.unknown_value, id);
                        values[i] = Integer.toString(id);
                    }
                    preset.setEntries(entries); preset.setEntryValues(values);
                    bind(sound, preset, "eq.preset", "");
                }
                if (state.array("bands").length() == 5)
                    range(sound, "eq.bass", R.string.clear_bass, -10, 10);
                int count = state.array("bands").length();
                if (count == 5 || count == 10) for (int i = 0; i < count; i++) {
                    SeekBarPreference band = new SeekBarPreference(c);
                    band.setTitle(getString(R.string.eq_band, i + 1));
                    band.setMin(count == 5 ? -10 : -6); band.setMax(count == 5 ? 10 : 6);
                    band.setShowSeekBarValue(true); band.setUpdatesContinuously(false);
                    bind(sound, band, "eq.band", Integer.toString(i));
                }
            }
            if (state.has(14)) toggle(sound, "eq.dsee", R.string.dsee);
            if (state.has(12)) {
                select(sound, "listening.mode", R.string.listening_mode, R.array.listening_modes, new int[]{0, 1, 2});
                select(sound, "listening.room", R.string.room_size, R.array.room_sizes, new int[]{1, 2, 3});
            }
        }
        if (state.has(20) || state.has(21) || state.has(22) || state.has(23) || state.has(24)
                || state.has(25) || state.has(26) || state.has(27)) {
            PreferenceCategory system = category(R.string.system);
            if (state.has(20)) select(system, "power.timeout", R.string.auto_power_off,
                    R.array.power_timeouts, new int[]{0, 5, 15, 30, 60, 180});
            if (state.has(21)) select(system, "power.wearing", R.string.wearing_power,
                    R.array.wearing_modes, new int[]{1, 2});
            if (state.has(22)) toggle(system, "power.pause", R.string.auto_pause);
            if (state.has(23)) toggle(system, "power.gesture", R.string.head_gestures);
            if (state.has(24)) toggle(system, "voice.enabled", R.string.voice_guidance);
            if (state.has(25)) range(system, "voice.volume", R.string.voice_volume, -2, 2);
            if (state.has(27)) select(system, "connection.priority", R.string.audio_priority,
                    R.array.audio_priorities, new int[]{1, 2});
            if (state.has(26)) {
                Preference shutdown = new Preference(c); shutdown.setTitle(R.string.power_off);
                bind(system, shutdown, "power.shutdown", "");
                shutdown.setOnPreferenceClickListener(p -> {
                    confirm(R.string.power_off, R.string.power_off_confirm, () -> send("power.shutdown", 1, ""));
                    return true;
                });
            }
        }
        if (state.array("general").length() > 0 || state.array("assignments").length() > 0) {
            PreferenceCategory controls = category(R.string.controls);
            for (int i = 0; i < state.array("general").length(); i++) {
                JSONObject item = state.array("general").optJSONObject(i);
                if (item == null) continue;
                SwitchPreferenceCompat toggle = new SwitchPreferenceCompat(c);
                toggle.setTitle(item.optString("title", getString(R.string.headset_setting)));
                toggle.setSummary(item.optString("summary"));
                bind(controls, toggle, "general", Integer.toString(item.optInt("index")));
            }
            for (int i = 0; i < state.array("assignments").length(); i++) {
                JSONObject item = state.array("assignments").optJSONObject(i);
                if (item == null) continue;
                JSONArray options = item.optJSONArray("options");
                if (options == null || options.length() == 0) continue;
                int location = item.optInt("location");
                ListPreference p = new ListPreference(c);
                p.setTitle(location == 1 ? R.string.left_control : location == 2 ? R.string.right_control : R.string.custom_control);
                String[] allLabels = getResources().getStringArray(R.array.assignable_actions);
                CharSequence[] labels = new CharSequence[options.length()];
                CharSequence[] values = new CharSequence[options.length()];
                for (int j = 0; j < options.length(); j++) {
                    int option = options.optInt(j);
                    values[j] = Integer.toString(option);
                    labels[j] = option >= 0 && option < allLabels.length
                            ? allLabels[option] : getString(R.string.unknown_value, option);
                }
                p.setEntries(labels); p.setEntryValues(values);
                bind(controls, p, "assign", Integer.toString(location));
            }
        }
        if (state.has(15) || state.has(16) || state.has(29)) {
            PreferenceCategory devices = category(R.string.devices);
            Preference note = new Preference(c); note.setSummary(R.string.devices_note); note.setSelectable(false);
            devices.addPreference(note);
            if (state.has(16)) toggle(devices, "pairing.enabled", R.string.pairing_mode);
            if (state.has(29)) toggle(devices, "pairing.switch", R.string.automatic_source_switch);
            for (int i = 0; i < state.array("paired").length(); i++) {
                JSONObject item = state.array("paired").optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                Preference p = new Preference(c); p.setTitle(item.optString("name", getString(R.string.paired_device)));
                bind(devices, p, "pairing.device", id);
                p.setOnPreferenceClickListener(ignored -> { showDevice(id); return true; });
            }
        }
        if (state.has(28)) {
            PreferenceCategory safe = category(R.string.safe_listening);
            action(safe, "safe.preview", R.string.start_preview, 1);
            action(safe, "safe.preview", R.string.stop_preview, 0);
            Preference level = new Preference(c); level.setKey("soundPressure");
            level.setTitle(R.string.sound_pressure); level.setSelectable(false); safe.addPreference(level);
        }
    }

    private PreferenceCategory category(int title) {
        PreferenceCategory category = new PreferenceCategory(getPreferenceManager().getContext());
        category.setTitle(title); getPreferenceScreen().addPreference(category); return category;
    }
    private void toggle(PreferenceCategory category, String key, int title) {
        SwitchPreferenceCompat p = new SwitchPreferenceCompat(category.getContext());
        p.setTitle(title); bind(category, p, key, "");
    }
    private void action(PreferenceCategory category, String key, int title, int value) {
        Preference p = new Preference(category.getContext());
        p.setTitle(title);
        final String boundAddress = state.address;
        bind(category, p, key, Integer.toString(value));
        p.setOnPreferenceClickListener(ignored -> {
            if (boundAddress.equals(state.address)) send(key, value, "");
            return true;
        });
    }
    private void range(PreferenceCategory category, String key, int title, int min, int max) {
        SeekBarPreference p = new SeekBarPreference(category.getContext());
        p.setTitle(title); p.setMin(min); p.setMax(max);
        p.setShowSeekBarValue(true); p.setUpdatesContinuously(false); bind(category, p, key, "");
    }
    private void select(PreferenceCategory category, String key, int title, int labels, int[] ids) {
        ListPreference p = new ListPreference(category.getContext());
        p.setTitle(title);
        String[] source = getResources().getStringArray(labels);
        CharSequence[] entries = new CharSequence[ids.length], values = new CharSequence[ids.length];
        for (int i = 0; i < ids.length; i++) { entries[i] = source[i]; values[i] = Integer.toString(ids[i]); }
        p.setEntries(entries); p.setEntryValues(values); bind(category, p, key, "");
    }
    private void bind(PreferenceCategory category, Preference p, String key, String target) {
        final String boundAddress = state.address;
        p.setKey(key + ":" + target); p.setPersistent(false);
        p.setOnPreferenceChangeListener((preference, value) -> {
            if (!state.canEdit(requestedAddress) || !boundAddress.equals(state.address)) return false;
            int number;
            try { number = value instanceof Boolean ? ((Boolean) value ? 1 : 0) : Integer.parseInt(value.toString()); }
            catch (NumberFormatException e) { return false; }
            send(key, number, target);
            return false; // Render only the next confirmed snapshot, never persist an optimistic value.
        });
        bindings.add(new Binding(p, key, target)); category.addPreference(p);
    }
    private void update(Binding binding, boolean editable) {
        Preference p = binding.preference;
        int value = state.value(binding.key, Integer.MIN_VALUE);
        if (binding.key.equals("eq.band")) value = state.array("bands").optInt(Integer.parseInt(binding.target), Integer.MIN_VALUE);
        if (binding.key.equals("general") || binding.key.equals("assign")) {
            JSONObject item = find(state.array(binding.key.equals("general") ? "general" : "assignments"),
                    binding.key.equals("general") ? "index" : "location", binding.target);
            value = item == null ? Integer.MIN_VALUE : item.optInt("value", Integer.MIN_VALUE);
            if (binding.key.equals("general") && (item == null || item.optInt("writable") == 0)) editable = false;
        }
        if (binding.key.equals("pairing.device")) {
            JSONObject item = find(state.array("paired"), "id", binding.target);
            p.setSummary(item != null && item.optInt("playing") == 1 ? R.string.playing
                    : item != null && item.optInt("connected") == 1 ? R.string.connected : R.string.disconnected);
            p.setEnabled(editable && item != null); return;
        }
        if (binding.key.equals("power.shutdown") || binding.key.equals("playback.command")
                || binding.key.equals("safe.preview")) { p.setEnabled(editable); return; }
        p.setEnabled(editable && value != Integer.MIN_VALUE);
        if (value == Integer.MIN_VALUE) return;
        if (p instanceof SwitchPreferenceCompat) ((SwitchPreferenceCompat) p).setChecked(value != 0);
        else if (p instanceof SeekBarPreference) ((SeekBarPreference) p).setValue(value);
        else if (p instanceof ListPreference) {
            ListPreference list = (ListPreference) p;
            list.setValue(Integer.toString(value));
            p.setSummary(list.getEntry() == null ? getString(R.string.unknown_value, value) : list.getEntry());
        }
    }
    private static JSONObject find(JSONArray items, String field, String value) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && value.equals(item.optString(field))) return item;
        }
        return null;
    }
    private void send(String key, int value, String target) {
        if (!state.canEdit(requestedAddress)) return;
        client.set(state.address, key, value, target);
        for (Binding binding : bindings) binding.preference.setEnabled(false);
    }
    private void showDevice(String id) {
        if (!state.canEdit(requestedAddress)) return;
        final String boundAddress = state.address;
        JSONObject device = find(state.array("paired"), "id", id);
        if (device == null) return;
        String[] labels = getResources().getStringArray(R.array.device_actions);
        new AlertDialog.Builder(requireContext()).setTitle(device.optString("name"))
                .setItems(labels, (dialog, which) -> {
                    if (!boundAddress.equals(state.address)) return;
                    if (which == 3) confirm(R.string.forget_device, R.string.forget_device_confirm,
                            () -> send("pairing.device", 4, id));
                    else send("pairing.device", which + 1, id);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void confirm(int title, int message, Runnable action) {
        if (!state.canEdit(requestedAddress)) return;
        final String boundAddress = state.address;
        new AlertDialog.Builder(requireContext()).setTitle(title).setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (boundAddress.equals(state.address) && state.canEdit(requestedAddress)) action.run();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void openAudio(boolean debug) {
        try {
            Intent intent = new Intent().setClassName("org.xiaomi.haotian.audio",
                    "org.xiaomi.haotian.audio." + (debug ? "HeadTrackingDebugActivity" : "AudioSettingsActivity"));
            startActivity(intent);
        } catch (RuntimeException e) { Toast.makeText(requireContext(), R.string.audio_unavailable, Toast.LENGTH_SHORT).show(); }
    }
    private static int[] sequence(int count) {
        int[] values = new int[count]; for (int i = 0; i < count; i++) values[i] = i; return values;
    }
}
