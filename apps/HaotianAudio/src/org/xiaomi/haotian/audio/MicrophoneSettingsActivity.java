/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Standalone microphone controls. This is intentionally separate from Xiaomi audio effects. */
public final class MicrophoneSettingsActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.microphone_app_name);
        if (state == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new MicrophoneSettingsFragment())
                    .commit();
        }
    }
}
