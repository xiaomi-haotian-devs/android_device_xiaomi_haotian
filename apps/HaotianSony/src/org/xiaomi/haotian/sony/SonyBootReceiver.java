// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

public final class SonyBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // Secondary-user processes have no transport controller; the system user owns it.
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;
        // Application startup restores the A2DP profile observer, without forcing a connection.
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action))
            ((HaotianSonyApplication) context.getApplicationContext()).controller().reconcile();
    }
}
