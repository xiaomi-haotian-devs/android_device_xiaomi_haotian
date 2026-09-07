/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public final class AudioSettingsActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        if (state == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new AudioSettingsFragment())
                    .commit();
        }
    }
}
