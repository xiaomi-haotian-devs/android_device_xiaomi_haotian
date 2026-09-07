// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.haptics;

import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Toast;
import androidx.preference.ListPreference;
import androidx.preference.SwitchPreferenceCompat;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public final class HapticsSettingsFragment extends SettingsBasePreferenceFragment {
    private static final String STYLE = "persist.sys.haotian.haptics.style";
    private static final String ENHANCE = "persist.sys.haotian.haptics.enhance";
    private static final VibrationAttributes ATTRS = new VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_TOUCH).build();
    private Vibrator vibrator;
    private ListPreference style;
    private SwitchPreferenceCompat enhance;

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        setPreferencesFromResource(R.xml.haptics_settings, rootKey);
        vibrator = requireContext().getSystemService(Vibrator.class);
        style = findPreference("style");
        enhance = findPreference("enhance");
        style.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        style.setOnPreferenceChangeListener((preference, value) -> {
            String selected = String.valueOf(value);
            if (!selected.equals("soft") && !selected.equals("balanced")
                    && !selected.equals("crisp")) return false;
            return write(STYLE, selected);
        });
        enhance.setOnPreferenceChangeListener((preference, value) ->
                write(ENHANCE, Boolean.TRUE.equals(value) ? "true" : "false"));
        findPreference("reset").setOnPreferenceClickListener(preference -> {
            if (write(STYLE, "balanced")) write(ENHANCE, "true");
            refresh();
            return true;
        });
        for (String key : new String[]{"click", "tick", "ramp", "waveform"}) {
            findPreference(key).setOnPreferenceClickListener(preference -> {
                preview(preference.getKey());
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String selected = SystemProperties.get(STYLE, "balanced");
        style.setValue(style.findIndexOfValue(selected) >= 0 ? selected : "balanced");
        enhance.setChecked(SystemProperties.getBoolean(ENHANCE, true));
        boolean available = vibrator != null && vibrator.hasVibrator();
        boolean owner = UserHandle.myUserId() == UserHandle.USER_SYSTEM;
        style.setEnabled(available && owner);
        enhance.setEnabled(available && owner);
        findPreference("reset").setEnabled(available && owner);
        findPreference("scope").setSummary(owner ? R.string.scope_summary : R.string.owner_only);
        for (String key : new String[]{"click", "tick", "ramp", "waveform"}) {
            findPreference(key).setEnabled(available);
        }
    }

    private boolean write(String property, String value) {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return false;
        try {
            SystemProperties.set(property, value);
            return value.equals(SystemProperties.get(property));
        } catch (RuntimeException e) {
            Toast.makeText(requireContext(), R.string.write_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void preview(String key) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.cancel();
        try {
            VibrationEffect effect;
            switch (key) {
                case "tick":
                    effect = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
                            ? VibrationEffect.startComposition().addPrimitive(
                                    VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f).compose()
                            : VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
                    break;
                case "ramp":
                    if (!vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                        Toast.makeText(requireContext(), R.string.preview_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    VibrationEffect.Composition composition = VibrationEffect.startComposition();
                    for (int i = 1; i <= 5; ++i) {
                        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                                i / 5.0f, i == 1 ? 0 : 40);
                    }
                    effect = composition.compose();
                    break;
                case "waveform":
                    effect = VibrationEffect.createWaveform(
                            new long[]{100, 100, 100, 100, 100},
                            new int[]{32, 100, 220, 100, 32}, -1);
                    break;
                default:
                    effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            }
            vibrator.vibrate(effect, ATTRS);
        } catch (RuntimeException e) {
            Toast.makeText(requireContext(), R.string.preview_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPause() {
        if (vibrator != null) vibrator.cancel();
        super.onPause();
    }
}
