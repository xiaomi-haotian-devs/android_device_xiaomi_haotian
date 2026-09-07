/* SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later */
package org.xiaomi.haotian.airpods;

import android.os.Bundle;

import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public final class AirPodsSettingsActivity extends CollapsingToolbarBaseActivity implements
        PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    private static final String EXTRA_DEVICE_ADDRESS =
            "org.xiaomi.haotian.airpods.extra.DEVICE_ADDRESS";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        if (state == null) {
            String address = getIntent().getStringExtra(EXTRA_DEVICE_ADDRESS);
            Bundle arguments = new Bundle();
            arguments.putString(AirPodsSettingsFragment.ARG_DEVICE_ADDRESS, address);
            AirPodsSettingsFragment fragment = new AirPodsSettingsFragment();
            fragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, fragment)
                    .commit();
        }
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller,
            PreferenceScreen screen) {
        Bundle arguments = new Bundle();
        arguments.putString(AirPodsSettingsFragment.ARG_DEVICE_ADDRESS,
                caller.getArguments() == null ? null
                        : caller.getArguments().getString(
                                AirPodsSettingsFragment.ARG_DEVICE_ADDRESS));
        arguments.putString(AirPodsSettingsFragment.ARG_ROOT_KEY, screen.getKey());
        AirPodsSettingsFragment fragment = new AirPodsSettingsFragment();
        fragment.setArguments(arguments);
        getSupportFragmentManager().beginTransaction()
                .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, fragment)
                .addToBackStack(screen.getKey())
                .commit();
        setTitle(screen.getTitle());
        return true;
    }
}
