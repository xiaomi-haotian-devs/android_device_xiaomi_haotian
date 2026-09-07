/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.charging;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vendor.lineage.powershare.IPowerShare;

public final class ChargingSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {
    private static final String KEY_REVERSE = "reverse_enable";
    private static final String KEY_THRESHOLD = "reverse_threshold";
    private static final String KEY_ANTI_AGING = "anti_aging";
    private static final String KEY_FAST_CHARGE = "fast_charge";
    private static final String KEY_WIRELESS_MODE = "wireless_mode";
    private static final String KEY_WIRELESS_NFC = "wireless_disable_nfc";
    private static final String KEY_BYPASS = "bypass";
    private static final String KEY_WIRELESS_STATUS = "wireless_status";
    private static final String KEY_BATTERY_STATUS = "battery_status";
    private static final String KEY_FIRMWARE = "firmware_update";
    private static final String KEY_RESET = "reset_limits";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MiChargeClient miCharge = new MiChargeClient();
    private final ChargingControlClient controller = new ChargingControlClient();
    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            mainHandler.postDelayed(this, 1000);
        }
    };

    private SharedPreferences preferences;
    private IPowerShare powerShare;
    private MainSwitchPreference reversePreference;
    private ListPreference thresholdPreference;
    private SwitchPreferenceCompat antiAgingPreference;
    private SwitchPreferenceCompat fastChargePreference;
    private ListPreference wirelessModePreference;
    private SwitchPreferenceCompat wirelessNfcPreference;
    private SwitchPreferenceCompat bypassPreference;
    private Preference wirelessStatusPreference;
    private Preference batteryStatusPreference;
    private Preference firmwarePreference;
    private Preference resetPreference;

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        setPreferencesFromResource(R.xml.charging_settings, rootKey);
        preferences = requireContext().getSharedPreferences(
                ChargingSettingsActivity.PREFERENCES, Context.MODE_PRIVATE);

        reversePreference = requirePreference(KEY_REVERSE);
        thresholdPreference = requirePreference(KEY_THRESHOLD);
        antiAgingPreference = requirePreference(KEY_ANTI_AGING);
        fastChargePreference = requirePreference(KEY_FAST_CHARGE);
        wirelessModePreference = requirePreference(KEY_WIRELESS_MODE);
        wirelessNfcPreference = requirePreference(KEY_WIRELESS_NFC);
        bypassPreference = requirePreference(KEY_BYPASS);
        wirelessStatusPreference = requirePreference(KEY_WIRELESS_STATUS);
        batteryStatusPreference = requirePreference(KEY_BATTERY_STATUS);
        firmwarePreference = requirePreference(KEY_FIRMWARE);
        resetPreference = requirePreference(KEY_RESET);

        reversePreference.setOnPreferenceChangeListener(this);
        thresholdPreference.setOnPreferenceChangeListener(this);
        antiAgingPreference.setOnPreferenceChangeListener(this);
        fastChargePreference.setOnPreferenceChangeListener(this);
        wirelessModePreference.setOnPreferenceChangeListener(this);
        wirelessNfcPreference.setOnPreferenceChangeListener(this);
        bypassPreference.setOnPreferenceChangeListener(this);
        firmwarePreference.setOnPreferenceClickListener(this);
        resetPreference.setOnPreferenceClickListener(this);

        thresholdPreference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        wirelessModePreference.setSummaryProvider(
                ListPreference.SimpleSummaryProvider.getInstance());
        connectServicesAndRefresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(periodicRefresh);
        mainHandler.post(periodicRefresh);
    }

    @Override
    public void onPause() {
        mainHandler.removeCallbacks(periodicRefresh);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(periodicRefresh);
        executor.shutdownNow();
        super.onDestroy();
    }

    @SuppressWarnings("unchecked")
    private <T extends Preference> T requirePreference(String key) {
        T preference = findPreference(key);
        if (preference == null) {
            throw new IllegalStateException("Missing preference " + key);
        }
        return preference;
    }

    private void connectServicesAndRefresh() {
        executor.execute(() -> {
            IBinder binder = ServiceManager.checkService(IPowerShare.DESCRIPTOR + "/default");
            powerShare = IPowerShare.Stub.asInterface(binder);

            boolean reverse = false;
            int threshold = 30;
            boolean antiAging = false;
            boolean fastCharge = false;
            try {
                if (powerShare != null) {
                    threshold = powerShare.getMinBattery();
                }
                if (miCharge.isAvailable()) {
                    reverse = miCharge.isWirelessChargingEnabled();
                }
                if (controller.isAvailable()) {
                    antiAging = controller.isAntiAgingEnabled();
                    fastCharge = controller.isFastChargeEnabled();
                }
            } catch (RemoteException ignored) {
            }

            final boolean reverseValue = reverse;
            final String thresholdValue = Integer.toString(threshold);
            final boolean antiAgingValue = antiAging;
            final boolean fastChargeValue = fastCharge;
            final boolean controlsAvailable = controller.isAvailable();
            final boolean miChargeAvailable = miCharge.isAvailable();
            final int wirelessMode = preferences.contains(
                    ChargingSettingsActivity.PREF_WIRELESS_MODE)
                    ? preferences.getInt(ChargingSettingsActivity.PREF_WIRELESS_MODE,
                            ChargingSettingsActivity.WIRELESS_MODE_STANDARD)
                    : preferences.getBoolean(ChargingSettingsActivity.PREF_WIRELESS_QUIET, false)
                            ? ChargingSettingsActivity.WIRELESS_MODE_QUIET
                            : ChargingSettingsActivity.WIRELESS_MODE_STANDARD;
            final boolean bypass = preferences.getBoolean(
                    ChargingSettingsActivity.PREF_BYPASS, false);
            final boolean disableNfcDuringWireless = Settings.Global.getInt(
                    requireContext().getContentResolver(),
                    ChargingSettingsActivity.SETTING_DISABLE_NFC_DURING_WIRELESS, 0) != 0;

            postToUi(() -> {
                reversePreference.setChecked(reverseValue);
                reversePreference.setEnabled(miChargeAvailable);
                thresholdPreference.setValue(thresholdValue);
                thresholdPreference.setEnabled(powerShare != null);
                antiAgingPreference.setChecked(antiAgingValue);
                antiAgingPreference.setEnabled(controlsAvailable);
                fastChargePreference.setChecked(fastChargeValue);
                fastChargePreference.setEnabled(controlsAvailable);
                wirelessModePreference.setValue(Integer.toString(wirelessMode));
                wirelessModePreference.setEnabled(miChargeAvailable);
                wirelessNfcPreference.setChecked(disableNfcDuringWireless);
                bypassPreference.setChecked(bypass);
                bypassPreference.setEnabled(miChargeAvailable);
                firmwarePreference.setEnabled(miChargeAvailable);
            });
            refreshStatus();
        });
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
        if (preference == reversePreference) {
            final boolean enabled = (Boolean) value;
            executePreferenceChange(preference, () -> {
                if (!miCharge.isAvailable()) return false;
                return miCharge.setWirelessChargingEnabled(enabled);
            }, () -> reversePreference.setChecked(enabled));
            return false;
        }
        if (preference == thresholdPreference) {
            final String requested = (String) value;
            final int threshold = Integer.parseInt(requested);
            executePreferenceChange(preference, () -> {
                if (powerShare == null) return false;
                powerShare.setMinBattery(threshold);
                return powerShare.getMinBattery() == threshold;
            }, () -> thresholdPreference.setValue(requested));
            return false;
        }
        if (preference == antiAgingPreference) {
            final boolean enabled = (Boolean) value;
            executePreferenceChange(preference,
                    () -> controller.setAntiAgingEnabled(enabled)
                            && controller.isAntiAgingEnabled() == enabled,
                    () -> antiAgingPreference.setChecked(enabled));
            return false;
        }
        if (preference == fastChargePreference) {
            final boolean enabled = (Boolean) value;
            executePreferenceChange(preference,
                    () -> controller.setFastChargeEnabled(enabled)
                            && controller.isFastChargeEnabled() == enabled,
                    () -> fastChargePreference.setChecked(enabled));
            return false;
        }
        if (preference == wirelessModePreference) {
            final String requested = (String) value;
            final int mode = Integer.parseInt(requested);
            executePreferenceChange(preference,
                    () -> ChargingSettingsActivity.applyWirelessMode(miCharge, mode),
                    () -> {
                        wirelessModePreference.setValue(requested);
                        preferences.edit()
                                .putInt(ChargingSettingsActivity.PREF_WIRELESS_MODE, mode)
                                .putBoolean(ChargingSettingsActivity.PREF_WIRELESS_QUIET,
                                        mode == ChargingSettingsActivity.WIRELESS_MODE_QUIET)
                                .apply();
                    });
            return false;
        }
        if (preference == wirelessNfcPreference) {
            final boolean enabled = (Boolean) value;
            final boolean success = Settings.Global.putInt(
                    requireContext().getContentResolver(),
                    ChargingSettingsActivity.SETTING_DISABLE_NFC_DURING_WIRELESS,
                    enabled ? 1 : 0);
            if (!success) {
                toast(R.string.operation_failed);
            }
            return success;
        }
        if (preference == bypassPreference) {
            final boolean enabled = (Boolean) value;
            if (enabled && !canEnableBypass()) {
                toast(R.string.bypass_requirement);
                return false;
            }
            executePreferenceChange(preference, () -> miCharge.setBypassCharging(enabled), () -> {
                bypassPreference.setChecked(enabled);
                preferences.edit().putBoolean(
                        ChargingSettingsActivity.PREF_BYPASS, enabled).apply();
            });
            return false;
        }
        return false;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference == firmwarePreference) {
            checkAndConfirmFirmwareUpdate();
            return true;
        }
        if (preference == resetPreference) {
            resetOptionalControls();
            return true;
        }
        return false;
    }

    private interface RemoteBooleanOperation {
        boolean run() throws RemoteException;
    }

    private void executePreferenceChange(Preference preference,
            RemoteBooleanOperation operation, Runnable onSuccess) {
        preference.setEnabled(false);
        executor.execute(() -> {
            boolean success;
            try {
                success = operation.run();
            } catch (RemoteException | RuntimeException e) {
                success = false;
            }
            final boolean result = success;
            postToUi(() -> {
                preference.setEnabled(true);
                if (result) {
                    onSuccess.run();
                } else {
                    toast(R.string.operation_failed);
                }
            });
        });
    }

    private boolean canEnableBypass() {
        Intent battery = requireContext().registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int percentage = scale > 0 ? level * 100 / scale : 0;
        return percentage >= 40 && (plugged & (BatteryManager.BATTERY_PLUGGED_AC
                | BatteryManager.BATTERY_PLUGGED_USB)) != 0;
    }

    private void checkAndConfirmFirmwareUpdate() {
        firmwarePreference.setEnabled(false);
        executor.execute(() -> {
            int error = 0;
            try {
                if (!miCharge.isWirelessFirmwareUpdateSupported()) {
                    error = R.string.firmware_not_supported;
                } else if (batteryPercentage() < 30) {
                    error = R.string.firmware_guard_battery;
                } else if (isWirelesslyCharging()) {
                    error = R.string.firmware_guard_wireless;
                } else if (miCharge.isWirelessChargingEnabled()) {
                    error = R.string.firmware_guard_reverse;
                } else if (isFirmwareBusy(miCharge.getWirelessFirmwareStatus())) {
                    error = R.string.firmware_guard_busy;
                }
            } catch (RemoteException e) {
                error = R.string.service_unavailable;
            }
            final int guardError = error;
            postToUi(() -> {
                firmwarePreference.setEnabled(true);
                if (guardError != 0) {
                    toast(guardError);
                    return;
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.firmware_confirm_title)
                        .setMessage(R.string.firmware_confirm_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.update,
                                (dialog, which) -> requestFirmwareUpdate())
                        .show();
            });
        });
    }

    private void requestFirmwareUpdate() {
        firmwarePreference.setEnabled(false);
        executor.execute(() -> {
            boolean success;
            try {
                success = miCharge.requestWirelessFirmwareUpdate();
            } catch (RemoteException e) {
                success = false;
            }
            final boolean result = success;
            postToUi(() -> {
                firmwarePreference.setEnabled(true);
                toast(result ? R.string.firmware_started : R.string.operation_failed);
            });
            refreshStatus();
        });
    }

    private void resetOptionalControls() {
        resetPreference.setEnabled(false);
        executor.execute(() -> {
            boolean success = true;
            try {
                success &= controller.isAvailable()
                        && controller.setAntiAgingEnabled(false);
                success &= controller.isAvailable()
                        && controller.setFastChargeEnabled(false);
                success &= ChargingSettingsActivity.applyWirelessMode(miCharge,
                        ChargingSettingsActivity.WIRELESS_MODE_STANDARD);
                success &= miCharge.setBypassCharging(false);
                success &= Settings.Global.putInt(getContext().getContentResolver(),
                        ChargingSettingsActivity.SETTING_DISABLE_NFC_DURING_WIRELESS, 0);
            } catch (RemoteException e) {
                success = false;
            }
            if (success) {
                preferences.edit()
                        .putBoolean(ChargingSettingsActivity.PREF_WIRELESS_QUIET, false)
                        .putInt(ChargingSettingsActivity.PREF_WIRELESS_MODE,
                                ChargingSettingsActivity.WIRELESS_MODE_STANDARD)
                        .putBoolean(ChargingSettingsActivity.PREF_BYPASS, false)
                        .apply();
            }
            final boolean result = success;
            postToUi(() -> {
                resetPreference.setEnabled(true);
                if (result) {
                    antiAgingPreference.setChecked(false);
                    fastChargePreference.setChecked(false);
                    wirelessModePreference.setValue(Integer.toString(
                            ChargingSettingsActivity.WIRELESS_MODE_STANDARD));
                    wirelessNfcPreference.setChecked(false);
                    bypassPreference.setChecked(false);
                }
                toast(result ? R.string.reset_done : R.string.operation_failed);
            });
        });
    }

    private void refreshStatus() {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            Boolean reverse = null;
            try {
                if (miCharge.isAvailable()) {
                    reverse = miCharge.isWirelessChargingEnabled();
                }
            } catch (RemoteException ignored) {
            }

            String batteryStatus;
            try {
                int firmwareState = miCharge.getWirelessFirmwareStatus();
                batteryStatus = getString(R.string.status_format,
                        miCharge.getBatterySoh(), miCharge.getCycleCount(),
                        miCharge.getBatteryChargeFullMah(), miCharge.getChargingPowerMax(),
                        firmwareStateLabel(firmwareState));
            } catch (RemoteException e) {
                batteryStatus = getString(R.string.service_unavailable);
            }

            String wirelessStatus;
            try {
                wirelessStatus = controller.isAvailable()
                        ? formatWirelessStatus(controller.getWirelessDetails())
                        : getString(R.string.service_unavailable);
            } catch (RemoteException e) {
                wirelessStatus = getString(R.string.service_unavailable);
            }
            final String batteryValue = batteryStatus;
            final String wirelessValue = wirelessStatus;
            final Boolean reverseValue = reverse;
            postToUi(() -> {
                if (reverseValue != null) {
                    reversePreference.setChecked(reverseValue);
                }
                batteryStatusPreference.setSummary(batteryValue);
                wirelessStatusPreference.setSummary(wirelessValue);
            });
        });
    }

    private String formatWirelessStatus(ChargingControlClient.WirelessDetails details) {
        boolean connected = isWirelesslyCharging() || details.adapterType != 0
                || details.receiverVoltageMv > 0;
        String firmware = details.firmwareVersion.isEmpty()
                ? getString(R.string.value_unknown) : details.firmwareVersion;
        if (!connected) {
            return getString(R.string.wireless_status_disconnected, firmware);
        }

        float powerWatts = details.receiverVoltageMv * details.receiverCurrentMa
                / 1_000_000.0f;
        String uuid = details.transmitterUuid.isEmpty()
                || "00.00.00.00".equals(details.transmitterUuid)
                ? getString(R.string.value_unknown) : details.transmitterUuid;
        String alignment = details.alignment == 0
                ? getString(R.string.wireless_alignment_good)
                : getString(R.string.wireless_alignment_adjust);
        return getString(R.string.wireless_status_format,
                adapterTypeLabel(details.adapterType), uuid, powerWatts,
                details.receiverVoltageMv, details.receiverCurrentMa,
                details.rectifierVoltageMv, details.dieTemperature, alignment,
                details.signalStrength, details.controlError,
                details.transmitterSpeed,
                details.fastCharge ? getString(R.string.value_yes)
                        : getString(R.string.value_no),
                details.carAdapter ? getString(R.string.value_yes)
                        : getString(R.string.value_no),
                firmware);
    }

    private String adapterTypeLabel(int type) {
        final int resource;
        switch (type) {
            case 1: resource = R.string.adapter_sdp; break;
            case 2: resource = R.string.adapter_cdp; break;
            case 3: resource = R.string.adapter_dcp; break;
            case 4: resource = R.string.adapter_qc2; break;
            case 5: resource = R.string.adapter_qc3; break;
            case 6: resource = R.string.adapter_pd; break;
            case 7: resource = R.string.adapter_auth_failed; break;
            case 8: resource = R.string.adapter_xiaomi_qc3; break;
            case 9: resource = R.string.adapter_xiaomi_pd; break;
            case 10: resource = R.string.adapter_zimi_car; break;
            case 11: resource = R.string.adapter_xiaomi_40w; break;
            case 12: resource = R.string.adapter_voice_box; break;
            case 13: resource = R.string.adapter_xiaomi_50w; break;
            case 14: resource = R.string.adapter_xiaomi_60w; break;
            case 15: resource = R.string.adapter_xiaomi_100w; break;
            default: return getString(R.string.adapter_unknown, type);
        }
        return getString(resource);
    }

    private String firmwareStateLabel(int state) {
        switch (state) {
            case 0: return getString(R.string.firmware_state_idle);
            case 1: return getString(R.string.firmware_state_available);
            case 2: return getString(R.string.firmware_state_updating);
            case 3: return getString(R.string.firmware_state_latest);
            case 4: return getString(R.string.firmware_state_error);
            case 5: return getString(R.string.firmware_state_checking);
            default: return getString(R.string.firmware_state_unknown, state);
        }
    }

    private static boolean isFirmwareBusy(int state) {
        return state == 2 || state == 5;
    }

    private int batteryPercentage() {
        Intent battery = requireContext().registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return 0;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return scale > 0 ? level * 100 / scale : 0;
    }

    private boolean isWirelesslyCharging() {
        Intent battery = requireContext().registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0;
    }

    private void postToUi(Runnable action) {
        mainHandler.post(() -> {
            if (isAdded()) action.run();
        });
    }

    private void toast(int message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
        }
    }
}
