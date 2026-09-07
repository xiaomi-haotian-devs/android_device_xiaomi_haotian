/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

/** Restores A2DP observation after direct boot and package replacement. */
public final class MiBudsBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;
        ((HaotianMiBudsApplication) context.getApplicationContext())
                .controller().reconcile();
    }
}
