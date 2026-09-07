/* SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later */
package org.xiaomi.haotian.airpods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.TileService;
import android.util.Log;

/** Coalesces system/AACP state broadcasts into a background-safe metadata refresh job. */
public final class AirPodsStateReceiver extends BroadcastReceiver {
    private static final String TAG = "HaotianAirPods";

    @Override
    public void onReceive(Context context, Intent intent) {
        HaotianAirPodsApplication.requestPolicyRefresh();
        try {
            TileService.requestListeningState(
                    context, AirPodsNoiseControlTileService.COMPONENT);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not refresh AirPods Quick Settings tile", exception);
        }
        AirPodsMetadataService.schedule(context);
    }
}
