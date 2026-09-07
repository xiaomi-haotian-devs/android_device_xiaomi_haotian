// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;

import android.app.Application;
import android.os.UserHandle;

public final class HaotianSonyApplication extends Application {
    private SonyController controller;

    @Override public void onCreate() {
        super.onCreate();
        // UI processes in secondary users bind to the singleUser service in the system user.
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) controller = new SonyController(this);
    }

    SonyController controller() {
        if (controller == null) throw new IllegalStateException("Sony transport belongs to system user");
        return controller;
    }
}
