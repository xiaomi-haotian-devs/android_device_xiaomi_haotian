/*
 * SPDX-FileCopyrightText: 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <unistd.h>

// Haotian uses Xiaomi's userspace touch-host processing (THP) path. The stock touchfeature service
// starts this same entry point from libtouchreport after the kernel driver registers touch ID 0.
extern "C" int thp_daemon_main_for_touch_id(int touch_id);

int main() {
    // libtouchreport searches /odm/lib64, /vendor/lib64 and its current directory for the HAL and
    // algorithm plugins. Recovery libraries live under /system/lib64, so use it as the equivalent
    // of the stock service's /odm/lib64 plugin directory.
    if (chdir("/system/lib64") != 0) {
        return 1;
    }

    return thp_daemon_main_for_touch_id(0);
}
