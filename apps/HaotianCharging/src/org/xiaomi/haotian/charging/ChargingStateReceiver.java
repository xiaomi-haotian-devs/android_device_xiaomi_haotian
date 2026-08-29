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
                    client.setWirelessQuietMode(preferences.getBoolean(
                            ChargingSettingsActivity.PREF_WIRELESS_QUIET, false));
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to restore local charging controls", e);
            } finally {
                result.finish();
            }
        }, "HaotianChargingReceiver").start();
    }
}
