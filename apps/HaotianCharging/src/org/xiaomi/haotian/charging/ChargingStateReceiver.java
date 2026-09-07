/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.charging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.util.Log;

public final class ChargingStateReceiver extends BroadcastReceiver {
    private static final String TAG = "HaotianCharging";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                MiChargeClient client = new MiChargeClient();
                SharedPreferences preferences = context.getSharedPreferences(
                        ChargingSettingsActivity.PREFERENCES, Context.MODE_PRIVATE);
                if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                    client.setBypassCharging(false);
                    preferences.edit().putBoolean(
                            ChargingSettingsActivity.PREF_BYPASS, false).apply();
                } else {
                    // Bypass is deliberately never restored across a reboot.
                    client.setBypassCharging(false);
                    preferences.edit().putBoolean(
                            ChargingSettingsActivity.PREF_BYPASS, false).apply();
                    if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                        // HyperOS waits for transmitter authentication before
                        // sending its standard (4) or top-speed (7) request.
                        Thread.sleep(3000);
                    }
                    int mode = preferences.contains(
                            ChargingSettingsActivity.PREF_WIRELESS_MODE)
                            ? preferences.getInt(
                                    ChargingSettingsActivity.PREF_WIRELESS_MODE,
                                    ChargingSettingsActivity.WIRELESS_MODE_STANDARD)
                            : preferences.getBoolean(
                                    ChargingSettingsActivity.PREF_WIRELESS_QUIET, false)
                                    ? ChargingSettingsActivity.WIRELESS_MODE_QUIET
                                    : ChargingSettingsActivity.WIRELESS_MODE_STANDARD;
                    ChargingSettingsActivity.applyWirelessMode(client, mode);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to restore local charging controls", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                result.finish();
            }
        }, "HaotianChargingReceiver").start();
    }
}
