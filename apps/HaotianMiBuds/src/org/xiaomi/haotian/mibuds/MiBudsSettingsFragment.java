/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import org.json.JSONException;
import org.json.JSONObject;

/** A readback-driven UI; switches never claim an ACK as retained earbud state. */
public final class MiBudsSettingsFragment extends PreferenceFragmentCompat {
    private MiBudsControlClient client;
    private Preference information;
    private Preference refresh;
    private SwitchPreferenceCompat localSpatial;
    private SwitchPreferenceCompat localHeadTracking;
    private boolean updating;
    private String lastError = "";

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        Context context = getPreferenceManager().getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        information = new Preference(context);
        information.setIcon(R.drawable.ic_mibuds);
        information.setTitle(R.string.app_name);
        information.setSummary(R.string.disconnected);
        information.setSelectable(false);
        screen.addPreference(information);

        refresh = new Preference(context);
        refresh.setTitle(R.string.refresh);
        refresh.setOnPreferenceClickListener(preference -> {
            client.refresh();
            return true;
        });
        screen.addPreference(refresh);

        PreferenceCategory earbud = new PreferenceCategory(context);
        earbud.setTitle(R.string.earbud_rendering);
        screen.addPreference(earbud);

        localSpatial = new SwitchPreferenceCompat(context);
        localSpatial.setTitle(R.string.immersive_sound);
        localSpatial.setSummary(R.string.immersive_sound_summary);
        localSpatial.setOnPreferenceChangeListener((preference, value) -> {
            if (!updating) client.setLocalSpatialEnabled(Boolean.TRUE.equals(value));
            return false;
        });
        earbud.addPreference(localSpatial);

        localHeadTracking = new SwitchPreferenceCompat(context);
        localHeadTracking.setTitle(R.string.local_head_tracking);
        localHeadTracking.setSummary(R.string.local_head_tracking_summary);
        localHeadTracking.setOnPreferenceChangeListener((preference, value) -> {
            if (!updating) client.setLocalHeadTrackingEnabled(Boolean.TRUE.equals(value));
            return false;
        });
        earbud.addPreference(localHeadTracking);

        PreferenceCategory phone = new PreferenceCategory(context);
        phone.setTitle(R.string.phone_rendering);
        screen.addPreference(phone);
        Preference haotianAudio = new Preference(context);
        haotianAudio.setTitle(R.string.open_haotian_audio);
        haotianAudio.setSummary(R.string.open_haotian_audio_summary);
        haotianAudio.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent("org.xiaomi.haotian.audio.action.SETTINGS")
                    .setPackage("org.xiaomi.haotian.audio");
            if (requireContext().getPackageManager().resolveActivity(intent, 0) != null) {
                startActivity(intent);
            }
            return true;
        });
        phone.addPreference(haotianAudio);

        client = new MiBudsControlClient(requireContext(), this::onState);
        setUnavailable();
    }

    @Override
    public void onStart() {
        super.onStart();
        client.start();
    }

    @Override
    public void onStop() {
        client.stop();
        super.onStop();
    }

    private void onState(String encoded) {
        if (!isAdded()) return;
        try {
            JSONObject state = new JSONObject(encoded == null ? "{}" : encoded);
            String name = state.optString("name", getString(R.string.app_name));
            information.setTitle(name.isEmpty() ? getString(R.string.app_name) : name);
            information.setSummary(statusSummary(state.optString("status")));
            boolean ready = state.optBoolean("ready");
            boolean hasSpatial = ready && state.has("spatialState");
            boolean editable = hasSpatial && state.optString("status").equals("ready");
            updating = true;
            localSpatial.setEnabled(editable);
            localHeadTracking.setEnabled(editable);
            if (hasSpatial) {
                localSpatial.setSummary(R.string.immersive_sound_summary);
                localHeadTracking.setSummary(R.string.local_head_tracking_summary);
                int bits = state.optInt("spatialState");
                localSpatial.setChecked((bits & 0x01) != 0);
                localHeadTracking.setChecked((bits & 0x08) != 0);
            } else {
                localSpatial.setSummary(R.string.state_unavailable);
                localHeadTracking.setSummary(R.string.state_unavailable);
            }
            updating = false;
            refresh.setEnabled(!state.optString("status").equals("connecting"));
            String error = state.optString("error");
            if (!error.isEmpty() && !error.equals(lastError)) {
                Toast.makeText(requireContext(), getString(R.string.operation_error, error),
                        Toast.LENGTH_LONG).show();
            }
            lastError = error;
        } catch (JSONException exception) {
            setUnavailable();
        }
    }

    private int statusSummary(String status) {
        switch (status) {
            case "connecting":
                return R.string.connecting;
            case "authenticating":
                return R.string.authenticating;
            case "ready":
                return R.string.ready;
            case "applying":
                return R.string.applying;
            case "retrying":
                return R.string.retrying;
            default:
                return R.string.disconnected;
        }
    }

    private void setUnavailable() {
        updating = true;
        localSpatial.setEnabled(false);
        localHeadTracking.setEnabled(false);
        localSpatial.setSummary(R.string.state_unavailable);
        localHeadTracking.setSummary(R.string.state_unavailable);
        updating = false;
    }
}
