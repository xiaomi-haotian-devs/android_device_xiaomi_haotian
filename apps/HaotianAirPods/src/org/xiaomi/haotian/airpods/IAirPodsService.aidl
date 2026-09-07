/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import org.xiaomi.haotian.airpods.AirPodsState;

/** Local control plane for HaotianAirPods UI and policy components. */
interface IAirPodsService {
    AirPodsState getAirPodsState();
    boolean setAirPodsNoiseControlMode(String deviceAddress, int mode);
    boolean setAirPodsControlValue(String deviceAddress, int identifier, in byte[] value);
    boolean setAirPodsLongPressAction(String deviceAddress, boolean left, int action);
    boolean renameAirPods(String deviceAddress, String name);
    boolean refreshAirPodsAttValue(String deviceAddress, int handle);
    boolean setAirPodsAttValue(String deviceAddress, int handle, in byte[] value);
    boolean sendAirPodsAacpMessage(String deviceAddress, int opcode, in byte[] body);
    boolean setAirPodsControlConnectionEnabled(String deviceAddress, boolean enabled);
}
