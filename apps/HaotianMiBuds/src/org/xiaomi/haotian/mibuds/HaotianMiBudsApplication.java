/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.app.Application;
import android.os.UserHandle;

/** Owns the one system-user Xiaomi Buds controller. */
public final class HaotianMiBudsApplication extends Application {
    private MiBudsController controller;

    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            controller = new MiBudsController(this);
        }
    }

    MiBudsController controller() {
        if (controller == null) {
            throw new IllegalStateException("Xiaomi Buds transport belongs to system user");
        }
        return controller;
    }
}
