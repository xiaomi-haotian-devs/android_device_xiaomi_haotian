/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** SettingsLib host for the two intentionally separate spatial rendering paths. */
public final class MiBudsSettingsActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        if (state == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new MiBudsSettingsFragment())
                    .commit();
        }
    }
}
