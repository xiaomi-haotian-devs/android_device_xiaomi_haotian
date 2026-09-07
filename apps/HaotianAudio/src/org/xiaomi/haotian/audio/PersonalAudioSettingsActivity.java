/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Shared MD3 host. Each nested activity exposes exactly one personalization feature. */
public abstract class PersonalAudioSettingsActivity extends CollapsingToolbarBaseActivity {
    protected abstract int titleResource();

    protected abstract int screen();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(titleResource());
        if (state == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            PersonalAudioSettingsFragment.newInstance(screen()))
                    .commit();
        }
    }

    public static final class SoundId extends PersonalAudioSettingsActivity {
        @Override protected int titleResource() { return R.string.sound_id; }
        @Override protected int screen() { return PersonalAudioSettingsFragment.SCREEN_SOUND_ID; }
    }

    public static final class HearingCompensation extends PersonalAudioSettingsActivity {
        @Override protected int titleResource() { return R.string.hearing_compensation; }
        @Override protected int screen() { return PersonalAudioSettingsFragment.SCREEN_HEARING; }
    }

    public static final class EarCanal extends PersonalAudioSettingsActivity {
        @Override protected int titleResource() { return R.string.ear_canal_scan; }
        @Override protected int screen() { return PersonalAudioSettingsFragment.SCREEN_EAR_CANAL; }
    }

    public static final class HeadsetModel extends PersonalAudioSettingsActivity {
        @Override protected int titleResource() { return R.string.headset_model; }
        @Override protected int screen() {
            return PersonalAudioSettingsFragment.SCREEN_HEADSET_MODEL;
        }
    }
}
