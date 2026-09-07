/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.charging;

import android.os.Bundle;
import android.os.RemoteException;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public final class ChargingSettingsActivity extends CollapsingToolbarBaseActivity {
    static final String PREFERENCES = "haotian_charging";
    static final String PREF_WIRELESS_QUIET = "wireless_quiet";
    static final String PREF_WIRELESS_MODE = "wireless_mode";
    static final String PREF_BYPASS = "bypass";
    static final String SETTING_DISABLE_NFC_DURING_WIRELESS =
            "haotian_disable_nfc_during_wireless_charging";
    static final int WIRELESS_MODE_QUIET = 0;
    static final int WIRELESS_MODE_STANDARD = 1;
    static final int WIRELESS_MODE_BOOST = 2;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.app_name);
        if (state == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new ChargingSettingsFragment())
                    .commit();
        }
    }

    static boolean applyWirelessMode(MiChargeClient client, int mode)
            throws RemoteException {
        if (mode == WIRELESS_MODE_QUIET) {
            return client.setWirelessQuietMode(true);
        }
        if (!client.setWirelessQuietMode(false)) {
            return false;
        }
        // HyperOS uses 4 for its normal fan request and 7 for top-speed mode.
        // The dock and phone firmware retain final thermal and safety control.
        return client.setWirelessTransmitterSpeed(
                mode == WIRELESS_MODE_BOOST ? 7 : 4);
    }
}
