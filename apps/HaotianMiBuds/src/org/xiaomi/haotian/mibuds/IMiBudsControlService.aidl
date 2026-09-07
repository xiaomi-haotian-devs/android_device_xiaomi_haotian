/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import org.xiaomi.haotian.mibuds.IMiBudsStateCallback;

interface IMiBudsControlService {
    String getState();
    void registerCallback(IMiBudsStateCallback callback);
    void unregisterCallback(IMiBudsStateCallback callback);
    void refresh();
    void setLocalSpatialEnabled(boolean enabled);
    void setLocalHeadTrackingEnabled(boolean enabled);
}
