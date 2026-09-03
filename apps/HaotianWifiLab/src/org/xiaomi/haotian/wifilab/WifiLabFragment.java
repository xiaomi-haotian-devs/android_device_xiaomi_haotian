/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.wifilab;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WifiLabFragment extends SettingsBasePreferenceFragment {
    private static final String KEY_CONNECTION_STATUS = "connection_status";
    private static final String KEY_CAPABILITY_STATUS = "capability_status";
    private static final String KEY_CHANNEL_STATUS = "channel_status";
    private static final String KEY_REFRESH_STATUS = "refresh_status";
    private static final String KEY_COUNTRY_STATUS = "country_status";
    private static final String KEY_COUNTRY_CODE = "country_code";
    private static final String KEY_APPLY_COUNTRY = "apply_country";
    private static final String KEY_CLEAR_COUNTRY = "clear_country";
    private static final String KEY_PROFILE_STATUS = "profile_status";
    private static final String KEY_APPLY_STOCK_PROFILE = "apply_stock_profile";
    private static final String KEY_RESTORE_OVERLAYS = "restore_overlays";
    private static final String KEY_VERBOSE_LOGGING = "verbose_logging";
    private static final String KEY_SCAN_THROTTLE = "scan_throttle";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WifiManager wifiManager;
    private WifiFrameworkConfigClient frameworkConfig;
    private Preference connectionStatus;
    private Preference capabilityStatus;
    private Preference channelStatus;
    private Preference countryStatus;
    private Preference profileStatus;
    private EditTextPreference countryCode;
    private SwitchPreferenceCompat verboseLogging;
    private SwitchPreferenceCompat scanThrottle;
    private ConfigEntry[] configs;
    private String activeCountry;
    private String requestedCountry;
    private boolean countryCallbackRegistered;

    private final WifiManager.ActiveCountryCodeChangedCallback countryCallback =
            new WifiManager.ActiveCountryCodeChangedCallback() {
                @Override
                public void onActiveCountryCodeChanged(String country) {
                    activeCountry = country;
                    refreshCountryStatus();
                }

                @Override
                public void onCountryCodeInactive() {
                    activeCountry = null;
                    refreshCountryStatus();
                }
            };

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        setPreferencesFromResource(R.xml.wifi_lab_settings, rootKey);
        wifiManager = requireContext().getSystemService(WifiManager.class);
        if (wifiManager == null) {
            throw new IllegalStateException("Wi-Fi service is unavailable");
        }
        frameworkConfig = new WifiFrameworkConfigClient(wifiManager);

        connectionStatus = requirePreference(KEY_CONNECTION_STATUS);
        capabilityStatus = requirePreference(KEY_CAPABILITY_STATUS);
        channelStatus = requirePreference(KEY_CHANNEL_STATUS);
        countryStatus = requirePreference(KEY_COUNTRY_STATUS);
        profileStatus = requirePreference(KEY_PROFILE_STATUS);
        countryCode = requirePreference(KEY_COUNTRY_CODE);
        verboseLogging = requirePreference(KEY_VERBOSE_LOGGING);
        scanThrottle = requirePreference(KEY_SCAN_THROTTLE);

        configs = new ConfigEntry[] {
                config("config_6ghz", WifiFrameworkConfigClient.CONFIG_6GHZ_SUPPORT, true),
                config("config_softap_6ghz",
                        WifiFrameworkConfigClient.CONFIG_SOFTAP_6GHZ_SUPPORT, true),
                config("config_softap_dfs",
                        WifiFrameworkConfigClient.CONFIG_SOFTAP_ACS_INCLUDE_DFS, true),
                config("config_softap_beamformee",
                        WifiFrameworkConfigClient.CONFIG_SOFTAP_HE_SU_BEAMFORMEE, true),
                config("config_softap_beamformer",
                        WifiFrameworkConfigClient.CONFIG_SOFTAP_HE_SU_BEAMFORMER, true),
                config("config_softap_11ax",
                        WifiFrameworkConfigClient.CONFIG_SOFTAP_IEEE80211AX, true),
                config("config_softap_owe",
                        WifiFrameworkConfigClient.CONFIG_SOFTAP_OWE, true),
                config("config_softap_owe_transition",
                        WifiFrameworkConfigClient.CONFIG_SOFTAP_OWE_TRANSITION, true),
                config("config_driver_reg_event",
                        WifiFrameworkConfigClient.CONFIG_DRIVER_REG_CHANGED_EVENT, true),
                config("config_softap_dynamic_country",
                        WifiFrameworkConfigClient.CONFIG_SOFTAP_DYNAMIC_COUNTRY_CODE, true),
                config("config_sta_dynamic_country",
                        WifiFrameworkConfigClient.CONFIG_STA_DYNAMIC_COUNTRY_CODE, true),
                config("config_force_softap_restart",
                        WifiFrameworkConfigClient.CONFIG_FORCE_SOFTAP_RESTART_ON_COUNTRY_CODE,
                        false),
                config("config_auto_bridged_softap",
                        WifiFrameworkConfigClient.CONFIG_AUTO_UPGRADE_TO_BRIDGED_SOFTAP, false),
        };

        countryCode.setOnBindEditTextListener(editText -> {
            editText.setSingleLine(true);
            editText.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2)});
        });
        countryCode.setOnPreferenceChangeListener((preference, value) -> {
            String country = normalizeCountry(String.valueOf(value));
            if (!isValidCountry(country)) {
                toast(R.string.country_invalid);
                return false;
            }
            countryCode.setText(country);
            return false;
        });
        countryCode.setSummaryProvider(preference -> {
            String value = ((EditTextPreference) preference).getText();
            return TextUtils.isEmpty(value)
                    ? getString(R.string.country_code_summary)
                    : normalizeCountry(value);
        });

        requirePreference(KEY_REFRESH_STATUS).setOnPreferenceClickListener(preference -> {
            refreshAll();
            return true;
        });
        requirePreference(KEY_APPLY_COUNTRY).setOnPreferenceClickListener(preference -> {
            applyCountry();
            return true;
        });
        requirePreference(KEY_CLEAR_COUNTRY).setOnPreferenceClickListener(preference -> {
            clearCountry(R.string.country_cleared);
            return true;
        });
        requirePreference(KEY_APPLY_STOCK_PROFILE).setOnPreferenceClickListener(preference -> {
            applyStockProfile();
            return true;
        });
        requirePreference(KEY_RESTORE_OVERLAYS).setOnPreferenceClickListener(preference -> {
            restoreSourceOverlays();
            return true;
        });

        for (ConfigEntry entry : configs) {
            entry.preference.setOnPreferenceChangeListener((preference, value) -> {
                setConfig(entry, (Boolean) value);
                return false;
            });
        }
        verboseLogging.setOnPreferenceChangeListener((preference, value) -> {
            setVerboseLogging((Boolean) value);
            return false;
        });
        scanThrottle.setOnPreferenceChangeListener((preference, value) -> {
            setScanThrottle((Boolean) value);
            return false;
        });

        setFrameworkControlsEnabled(false);
        refreshAll();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerCountryCallback();
        refreshAll();
    }

    @Override
    public void onPause() {
        unregisterCountryCallback();
        super.onPause();
    }

    @Override
    public void onDestroy() {
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

    private ConfigEntry config(String key, int id, boolean stockValue) {
        return new ConfigEntry(id, stockValue, requirePreference(key));
    }

    private void registerCountryCallback() {
        if (countryCallbackRegistered) return;
        try {
            wifiManager.registerActiveCountryCodeChangedCallback(
                    requireContext().getMainExecutor(), countryCallback);
            countryCallbackRegistered = true;
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private void unregisterCountryCallback() {
        if (!countryCallbackRegistered) return;
        try {
            wifiManager.unregisterActiveCountryCodeChangedCallback(countryCallback);
        } catch (RuntimeException | LinkageError ignored) {
        }
        countryCallbackRegistered = false;
    }

    private void refreshAll() {
        executor.execute(() -> {
            final String connection = readConnectionStatus();
            final String capabilities = readCapabilityStatus();
            final String channels = readChannelStatus();
            final String frameworkCountry = readFrameworkCountry();
            final boolean verbose = readVerboseLogging();
            final boolean throttle = readScanThrottle();
            final boolean[] values = new boolean[configs.length];
            boolean hookAvailable = frameworkConfig.isAvailable();
            try {
                if (hookAvailable) {
                    for (int i = 0; i < configs.length; i++) {
                        values[i] = frameworkConfig.get(configs[i].id);
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                hookAvailable = false;
            }

            final boolean frameworkHookAvailable = hookAvailable;
            mainHandler.post(() -> {
                if (!isAdded()) return;
                connectionStatus.setSummary(connection);
                capabilityStatus.setSummary(capabilities);
                channelStatus.setSummary(channels);
                verboseLogging.setChecked(verbose);
                scanThrottle.setChecked(throttle);
                setFrameworkControlsEnabled(frameworkHookAvailable);
                if (frameworkHookAvailable) {
                    int matching = 0;
                    for (int i = 0; i < configs.length; i++) {
                        configs[i].preference.setChecked(values[i]);
                        if (values[i] == configs[i].stockValue) matching++;
                    }
                    profileStatus.setSummary(getString(R.string.profile_status_format,
                            matching, configs.length));
                } else {
                    profileStatus.setSummary(R.string.framework_hook_unavailable);
                }
                updateCountryStatus(frameworkCountry);
            });
        });
    }

    private String readConnectionStatus() {
        try {
            if (!wifiManager.isWifiEnabled()) return getString(R.string.status_wifi_disabled);
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getNetworkId() < 0) {
                return getString(R.string.status_not_connected);
            }
            String ssid = info.getSSID();
            if (TextUtils.isEmpty(ssid) || WifiManager.UNKNOWN_SSID.equals(ssid)) {
                ssid = getString(R.string.status_not_connected);
            }
            String standard = wifiStandardToString(info.getWifiStandard());
            return getString(R.string.connection_status_format, ssid, info.getFrequency(),
                    info.getRssi(), info.getLinkSpeed(), standard);
        } catch (RuntimeException e) {
            return getString(R.string.permission_required);
        }
    }

    private String readCapabilityStatus() {
        try {
            return getString(R.string.capability_status_format,
                    supported(wifiManager.is24GHzBandSupported()),
                    supported(wifiManager.is5GHzBandSupported()),
                    supported(wifiManager.is6GHzBandSupported()),
                    supported(wifiManager.isWifiStandardSupported(
                            ScanResult.WIFI_STANDARD_11AX)),
                    supported(wifiManager.isWifiStandardSupported(
                            ScanResult.WIFI_STANDARD_11BE)),
                    supported(wifiManager.isStaApConcurrencySupported()),
                    supported(wifiManager.isBridgedApConcurrencySupported()));
        } catch (RuntimeException e) {
            return getString(R.string.value_unknown);
        }
    }

    private String readChannelStatus() {
        if (requireContext().checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            return getString(R.string.permission_required);
        }
        try {
            List<WifiAvailableChannel> sta = wifiManager.getAllowedChannels(
                    WifiScanner.WIFI_BAND_6_GHZ, WifiAvailableChannel.OP_MODE_STA);
            List<WifiAvailableChannel> sap = wifiManager.getAllowedChannels(
                    WifiScanner.WIFI_BAND_6_GHZ, WifiAvailableChannel.OP_MODE_SAP);
            return getString(R.string.channel_status_format,
                    formatChannels(sta), formatChannels(sap));
        } catch (RuntimeException e) {
            return getString(R.string.operation_failed, errorMessage(e));
        }
    }

    private String formatChannels(List<WifiAvailableChannel> channels) {
        if (channels == null || channels.isEmpty()) return getString(R.string.channel_none);
        StringBuilder frequencies = new StringBuilder();
        for (WifiAvailableChannel channel : channels) {
            if (frequencies.length() > 0) frequencies.append(", ");
            frequencies.append(channel.getFrequencyMhz());
        }
        return getString(R.string.channel_list_format, channels.size(), frequencies.toString());
    }

    private String readFrameworkCountry() {
        try {
            return displayCountry(wifiManager.getCountryCode());
        } catch (RuntimeException e) {
            return getString(R.string.value_unknown);
        }
    }

    private boolean readVerboseLogging() {
        try {
            return wifiManager.isVerboseLoggingEnabled();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private boolean readScanThrottle() {
        try {
            return wifiManager.isScanThrottleEnabled();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private void refreshCountryStatus() {
        executor.execute(() -> {
            final String frameworkCountry = readFrameworkCountry();
            mainHandler.post(() -> {
                if (isAdded()) updateCountryStatus(frameworkCountry);
            });
        });
    }

    private void updateCountryStatus(String frameworkCountry) {
        countryStatus.setSummary(getString(R.string.country_status_format,
                frameworkCountry, displayCountry(activeCountry), displayCountry(requestedCountry)));
    }

    private String displayCountry(String country) {
        return TextUtils.isEmpty(country) ? getString(R.string.value_unknown) : country;
    }

    private String supported(boolean value) {
        return getString(value ? R.string.value_yes : R.string.value_no);
    }

    private String wifiStandardToString(int standard) {
        return switch (standard) {
            case ScanResult.WIFI_STANDARD_LEGACY -> "Legacy";
            case ScanResult.WIFI_STANDARD_11N -> "802.11n";
            case ScanResult.WIFI_STANDARD_11AC -> "802.11ac";
            case ScanResult.WIFI_STANDARD_11AX -> "802.11ax";
            case ScanResult.WIFI_STANDARD_11AD -> "802.11ad";
            case ScanResult.WIFI_STANDARD_11BE -> "802.11be";
            default -> getString(R.string.value_unknown);
        };
    }

    private void applyCountry() {
        final String country = normalizeCountry(countryCode.getText());
        if (!isValidCountry(country)) {
            toast(R.string.country_invalid);
            return;
        }
        executeOperation(R.string.country_applied, () -> {
            wifiManager.setOverrideCountryCode(country);
            requestedCountry = country;
        });
    }

    private void clearCountry(int successMessage) {
        executeOperation(successMessage, () -> {
            wifiManager.clearOverrideCountryCode();
            requestedCountry = null;
        });
    }

    private void applyStockProfile() {
        executeOperation(R.string.profile_applied, () -> {
            for (ConfigEntry entry : configs) {
                frameworkConfig.set(entry.id, entry.stockValue);
            }
        });
    }

    private void restoreSourceOverlays() {
        executeOperation(R.string.overrides_restored, () -> {
            for (ConfigEntry entry : configs) {
                frameworkConfig.clear(entry.id);
            }
            wifiManager.clearOverrideCountryCode();
            requestedCountry = null;
        });
    }

    private void setConfig(ConfigEntry entry, boolean value) {
        entry.preference.setEnabled(false);
        executor.execute(() -> {
            try {
                frameworkConfig.set(entry.id, value);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    entry.preference.setChecked(value);
                    entry.preference.setEnabled(true);
                    refreshAll();
                });
            } catch (RuntimeException | LinkageError e) {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    entry.preference.setEnabled(true);
                    showFailure(e);
                    refreshAll();
                });
            }
        });
    }

    private void setVerboseLogging(boolean value) {
        verboseLogging.setEnabled(false);
        executor.execute(() -> {
            try {
                wifiManager.setVerboseLoggingEnabled(value);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    verboseLogging.setChecked(value);
                    verboseLogging.setEnabled(true);
                });
            } catch (RuntimeException | LinkageError e) {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    verboseLogging.setEnabled(true);
                    showFailure(e);
                });
            }
        });
    }

    private void setScanThrottle(boolean value) {
        scanThrottle.setEnabled(false);
        executor.execute(() -> {
            try {
                wifiManager.setScanThrottleEnabled(value);
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    scanThrottle.setChecked(value);
                    scanThrottle.setEnabled(true);
                });
            } catch (RuntimeException | LinkageError e) {
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    scanThrottle.setEnabled(true);
                    showFailure(e);
                });
            }
        });
    }

    private void executeOperation(int successMessage, WifiOperation operation) {
        executor.execute(() -> {
            try {
                operation.run();
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    toast(successMessage);
                    refreshAll();
                });
            } catch (RuntimeException | LinkageError e) {
                mainHandler.post(() -> {
                    if (isAdded()) showFailure(e);
                });
            }
        });
    }

    private void setFrameworkControlsEnabled(boolean enabled) {
        for (ConfigEntry entry : configs) entry.preference.setEnabled(enabled);
        requirePreference(KEY_APPLY_STOCK_PROFILE).setEnabled(enabled);
        requirePreference(KEY_RESTORE_OVERLAYS).setEnabled(enabled);
    }

    private static String normalizeCountry(String country) {
        return country == null ? "" : country.trim().toUpperCase(Locale.US);
    }

    private static boolean isValidCountry(String country) {
        return "00".equals(country) || country.matches("[A-Z]{2}");
    }

    private void showFailure(Throwable error) {
        if (error instanceof LinkageError
                || error instanceof WifiFrameworkConfigClient.UnavailableException) {
            toast(R.string.framework_hook_unavailable);
        } else {
            Toast.makeText(requireContext(),
                    getString(R.string.operation_failed, errorMessage(error)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private void toast(int message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    private interface WifiOperation {
        void run();
    }

    private static final class ConfigEntry {
        final int id;
        final boolean stockValue;
        final SwitchPreferenceCompat preference;

        ConfigEntry(int id, boolean stockValue, SwitchPreferenceCompat preference) {
            this.id = id;
            this.stockValue = stockValue;
            this.preference = preference;
        }
    }
}
