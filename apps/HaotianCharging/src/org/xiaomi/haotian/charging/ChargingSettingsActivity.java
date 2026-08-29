/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.charging;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vendor.lineage.powershare.IPowerShare;

public final class ChargingSettingsActivity extends Activity {
    static final String PREFERENCES = "haotian_charging";
    static final String PREF_WIRELESS_QUIET = "wireless_quiet";
    static final String PREF_BYPASS = "bypass";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MiChargeClient miCharge = new MiChargeClient();
    private final ChargingControlClient controller = new ChargingControlClient();

    private SharedPreferences preferences;
    private IPowerShare powerShare;
    private Switch reverseSwitch;
    private Switch antiAgingSwitch;
    private Switch fastChargeSwitch;
    private Switch quietSwitch;
    private Switch bypassSwitch;
    private Spinner thresholdSpinner;
    private TextView statusText;
    private int textPrimary;
    private int textSecondary;
    private int surface;
    private boolean bindingUi;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        resolveColors();
        setContentView(buildContent());
        connectServicesAndRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusText != null) {
            refreshStatus();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(10), dp(20), dp(28));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(34);
        back.setMinWidth(dp(48));
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text(getString(R.string.app_name), 24, textPrimary);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(toolbar);

        TextView summary = text(getString(R.string.page_summary), 14, textSecondary);
        summary.setPadding(dp(12), 0, dp(12), dp(12));
        root.addView(summary);

        addSection(root, R.string.section_reverse);
        reverseSwitch = addSwitch(root, R.string.reverse_title,
                R.string.reverse_summary, this::onReverseChanged);

        LinearLayout thresholdRow = card();
        TextView thresholdTitle = text(getString(R.string.reverse_threshold), 16, textPrimary);
        thresholdRow.addView(thresholdTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        thresholdSpinner = new Spinner(this);
        String[] entries = {"20%", "30%", "40%"};
        thresholdSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, entries));
        thresholdSpinner.setEnabled(false);
        thresholdSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            if (!bindingUi && powerShare != null) {
                int value = new int[] {20, 30, 40}[position];
                executor.execute(() -> {
                    try {
                        powerShare.setMinBattery(value);
                    } catch (RemoteException e) {
                        showFailure();
                    }
                });
            }
        }));
        thresholdRow.addView(thresholdSpinner);
        root.addView(thresholdRow, cardParams());

        addSection(root, R.string.section_protection);
        antiAgingSwitch = addSwitch(root, R.string.anti_aging_title,
                R.string.anti_aging_summary, this::onAntiAgingChanged);

        addSection(root, R.string.section_performance);
        fastChargeSwitch = addSwitch(root, R.string.fast_charge_title,
                R.string.fast_charge_summary, this::onFastChargeChanged);
        quietSwitch = addSwitch(root, R.string.wireless_quiet_title,
                R.string.wireless_quiet_summary, this::onQuietChanged);
        bypassSwitch = addSwitch(root, R.string.bypass_title,
                R.string.bypass_summary, this::onBypassChanged);

        addSection(root, R.string.section_status);
        LinearLayout statusCard = card();
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusText = text(getString(R.string.status_loading), 15, textSecondary);
        statusCard.addView(statusText);
        root.addView(statusCard, cardParams());

        Button firmware = actionButton(R.string.firmware_update);
        firmware.setContentDescription(getString(R.string.firmware_update_summary));
        firmware.setOnClickListener(v -> checkAndConfirmFirmwareUpdate());
        root.addView(firmware, cardParams());

        Button reset = actionButton(R.string.reset_limits);
        reset.setContentDescription(getString(R.string.reset_limits_summary));
        reset.setOnClickListener(v -> resetOptionalControls());
        root.addView(reset, cardParams());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void connectServicesAndRefresh() {
        executor.execute(() -> {
            IBinder binder = ServiceManager.checkService(IPowerShare.DESCRIPTOR + "/default");
            powerShare = IPowerShare.Stub.asInterface(binder);
            boolean reverse = false;
            int threshold = 30;
            boolean anti = false;
            boolean fast = false;
            try {
                if (powerShare != null) {
                    reverse = powerShare.isEnabled();
                    threshold = powerShare.getMinBattery();
                }
                if (controller.isAvailable()) {
                    anti = controller.isAntiAgingEnabled();
                    fast = controller.isFastChargeEnabled();
                }
            } catch (RemoteException ignored) {
            }
            final boolean reverseValue = reverse;
            final int thresholdValue = threshold;
            final boolean antiValue = anti;
            final boolean fastValue = fast;
            runOnUiThread(() -> {
                bindingUi = true;
                reverseSwitch.setChecked(reverseValue);
                reverseSwitch.setEnabled(powerShare != null);
                thresholdSpinner.setSelection(thresholdValue <= 20 ? 0
                        : thresholdValue >= 40 ? 2 : 1);
                thresholdSpinner.setEnabled(powerShare != null);
                antiAgingSwitch.setChecked(antiValue);
                antiAgingSwitch.setEnabled(controller.isAvailable());
                fastChargeSwitch.setChecked(fastValue);
                fastChargeSwitch.setEnabled(controller.isAvailable());
                quietSwitch.setChecked(preferences.getBoolean(PREF_WIRELESS_QUIET, false));
                bypassSwitch.setChecked(preferences.getBoolean(PREF_BYPASS, false));
                quietSwitch.setEnabled(miCharge.isAvailable());
                bypassSwitch.setEnabled(miCharge.isAvailable());
                bindingUi = false;
            });
            refreshStatus();
        });
    }

    private void onReverseChanged(boolean enabled) {
        if (bindingUi || powerShare == null) return;
        executeSwitchChange(reverseSwitch, enabled, () -> {
            powerShare.setEnabled(enabled);
            return true;
        });
    }

    private void onAntiAgingChanged(boolean enabled) {
        if (bindingUi) return;
        executeSwitchChange(antiAgingSwitch, enabled,
                () -> controller.setAntiAgingEnabled(enabled));
    }

    private void onFastChargeChanged(boolean enabled) {
        if (bindingUi) return;
        executeSwitchChange(fastChargeSwitch, enabled,
                () -> controller.setFastChargeEnabled(enabled));
    }

    private void onQuietChanged(boolean enabled) {
        if (bindingUi) return;
        executeSwitchChange(quietSwitch, enabled, () -> {
            boolean success = miCharge.setWirelessQuietMode(enabled);
            if (success) {
                preferences.edit().putBoolean(PREF_WIRELESS_QUIET, enabled).apply();
            }
            return success;
        });
    }

    private void onBypassChanged(boolean enabled) {
        if (bindingUi) return;
        if (enabled && !canEnableBypass()) {
            setSwitchWithoutCallback(bypassSwitch, false);
            toast(R.string.bypass_requirement);
            return;
        }
        executeSwitchChange(bypassSwitch, enabled, () -> {
            boolean success = miCharge.setBypassCharging(enabled);
            if (success) {
                preferences.edit().putBoolean(PREF_BYPASS, enabled).apply();
            }
            return success;
        });
    }

    private boolean canEnableBypass() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int percentage = scale > 0 ? level * 100 / scale : 0;
        return percentage >= 40 && (plugged & (BatteryManager.BATTERY_PLUGGED_AC
                | BatteryManager.BATTERY_PLUGGED_USB)) != 0;
    }

    private void checkAndConfirmFirmwareUpdate() {
        executor.execute(() -> {
            int error = 0;
            try {
                if (!miCharge.isWirelessFirmwareUpdateSupported()) {
                    error = R.string.firmware_not_supported;
                } else if (batteryPercentage() < 30) {
                    error = R.string.firmware_guard_battery;
                } else if (isWirelesslyCharging()) {
                    error = R.string.firmware_guard_wireless;
                } else if (miCharge.getWirelessReverseStatus() != 0) {
                    error = R.string.firmware_guard_reverse;
                } else if (miCharge.getWirelessFirmwareStatus() != 0) {
                    error = R.string.firmware_guard_busy;
                }
            } catch (RemoteException e) {
                error = R.string.service_unavailable;
            }
            final int guardError = error;
            runOnUiThread(() -> {
                if (guardError != 0) {
                    toast(guardError);
                    return;
                }
                new AlertDialog.Builder(this)
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
        executor.execute(() -> {
            boolean success;
            try {
                success = miCharge.requestWirelessFirmwareUpdate();
            } catch (RemoteException e) {
                success = false;
            }
            final boolean result = success;
            runOnUiThread(() -> toast(result ? R.string.firmware_started
                    : R.string.operation_failed));
            refreshStatus();
        });
    }

    private void resetOptionalControls() {
        executor.execute(() -> {
            boolean success = true;
            try {
                success &= controller.setAntiAgingEnabled(false);
                success &= controller.setFastChargeEnabled(false);
                success &= miCharge.setWirelessQuietMode(false);
                success &= miCharge.setBypassCharging(false);
            } catch (RemoteException e) {
                success = false;
            }
            if (success) {
                preferences.edit().putBoolean(PREF_WIRELESS_QUIET, false)
                        .putBoolean(PREF_BYPASS, false).apply();
            }
            final boolean result = success;
            runOnUiThread(() -> {
                if (result) {
                    setSwitchWithoutCallback(antiAgingSwitch, false);
                    setSwitchWithoutCallback(fastChargeSwitch, false);
                    setSwitchWithoutCallback(quietSwitch, false);
                    setSwitchWithoutCallback(bypassSwitch, false);
                }
                toast(result ? R.string.reset_done : R.string.operation_failed);
            });
        });
    }

    private void refreshStatus() {
        executor.execute(() -> {
            String value;
            try {
                value = getString(R.string.status_format,
                        miCharge.getBatterySoh(), miCharge.getCycleCount(),
                        miCharge.getBatteryChargeFullMah(), miCharge.getChargingPowerMax(),
                        miCharge.getWirelessFirmwareStatus());
            } catch (RemoteException e) {
                value = getString(R.string.service_unavailable);
            }
            final String status = value;
            runOnUiThread(() -> statusText.setText(status));
        });
    }

    private int batteryPercentage() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return 0;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return scale > 0 ? level * 100 / scale : 0;
    }

    private boolean isWirelesslyCharging() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0;
    }

    private interface RemoteBooleanOperation {
        boolean run() throws RemoteException;
    }

    private void executeSwitchChange(Switch target, boolean requested,
            RemoteBooleanOperation operation) {
        target.setEnabled(false);
        executor.execute(() -> {
            boolean success;
            try {
                success = operation.run();
            } catch (RemoteException e) {
                success = false;
            }
            final boolean result = success;
            runOnUiThread(() -> {
                target.setEnabled(true);
                if (!result) {
                    setSwitchWithoutCallback(target, !requested);
                    toast(R.string.operation_failed);
                }
            });
        });
    }

    private void setSwitchWithoutCallback(Switch target, boolean value) {
        bindingUi = true;
        target.setChecked(value);
        bindingUi = false;
    }

    private Switch addSwitch(LinearLayout parent, int titleRes, int summaryRes,
            java.util.function.Consumer<Boolean> listener) {
        LinearLayout row = card();
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(getString(titleRes), 16, textPrimary);
        TextView summary = text(getString(summaryRes), 13, textSecondary);
        summary.setPadding(0, dp(3), dp(8), 0);
        labels.addView(title);
        labels.addView(summary);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(this);
        toggle.setEnabled(false);
        toggle.setOnCheckedChangeListener((button, checked) -> listener.accept(checked));
        row.addView(toggle);
        parent.addView(row, cardParams());
        return toggle;
    }

    private void addSection(LinearLayout parent, int titleRes) {
        TextView title = text(getString(titleRes), 14, textSecondary);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), dp(18), dp(12), dp(6));
        parent.addView(title);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(18), dp(15), dp(18), dp(15));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surface);
        background.setCornerRadius(dp(20));
        layout.setBackground(background);
        return layout;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        return params;
    }

    private Button actionButton(int textRes) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setMinHeight(dp(58));
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void resolveColors() {
        textPrimary = resolveColor(android.R.attr.textColorPrimary, Color.BLACK);
        textSecondary = resolveColor(android.R.attr.textColorSecondary, Color.DKGRAY);
        surface = resolveColor(android.R.attr.colorBackgroundFloating,
                (getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        ? Color.rgb(45, 43, 48) : Color.WHITE);
    }

    private int resolveColor(int attribute, int fallback) {
        android.content.res.TypedArray values = obtainStyledAttributes(new int[] {attribute});
        try {
            return values.getColor(0, fallback);
        } finally {
            values.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showFailure() {
        runOnUiThread(() -> toast(R.string.operation_failed));
    }

    private static final class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final java.util.function.IntConsumer listener;

        SimpleItemSelectedListener(java.util.function.IntConsumer listener) {
            this.listener = listener;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                int position, long id) {
            listener.accept(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
