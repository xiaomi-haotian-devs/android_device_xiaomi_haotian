/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.xiaomi.haotian.airpods;

/** Latest authenticated Apple proximity advertisement for the selected AirPods. */
final class AirPodsNearbyState {
    static final int CONNECTION_UNKNOWN = -1;
    static final int CONNECTION_DISCONNECTED = 0x00;
    static final int CONNECTION_IDLE = 0x04;
    static final int CONNECTION_MUSIC = 0x05;
    static final int CONNECTION_CALL = 0x06;
    static final int CONNECTION_RINGING = 0x07;
    static final int CONNECTION_HANGING_UP = 0x09;

    final String classicAddress;
    final String broadcastAddress;
    final long lastSeenElapsedRealtime;
    final boolean paired;
    final int modelId;
    final String modelName;
    final int leftBattery;
    final int rightBattery;
    final int caseBattery;
    final boolean leftInEar;
    final boolean rightInEar;
    final boolean leftCharging;
    final boolean rightCharging;
    final boolean caseCharging;
    final boolean lidOpen;
    final int color;
    final int connectionState;

    AirPodsNearbyState(String classicAddress, String broadcastAddress,
            long lastSeenElapsedRealtime, boolean paired, int modelId, String modelName,
            int leftBattery, int rightBattery, int caseBattery, boolean leftInEar,
            boolean rightInEar, boolean leftCharging, boolean rightCharging,
            boolean caseCharging, boolean lidOpen, int color, int connectionState) {
        this.classicAddress = classicAddress;
        this.broadcastAddress = broadcastAddress;
        this.lastSeenElapsedRealtime = lastSeenElapsedRealtime;
        this.paired = paired;
        this.modelId = modelId;
        this.modelName = modelName;
        this.leftBattery = leftBattery;
        this.rightBattery = rightBattery;
        this.caseBattery = caseBattery;
        this.leftInEar = leftInEar;
        this.rightInEar = rightInEar;
        this.leftCharging = leftCharging;
        this.rightCharging = rightCharging;
        this.caseCharging = caseCharging;
        this.lidOpen = lidOpen;
        this.color = color;
        this.connectionState = connectionState;
    }

    boolean isWorn() {
        return leftInEar || rightInEar;
    }

    boolean samePayload(AirPodsNearbyState other) {
        return other != null && paired == other.paired && modelId == other.modelId
                && leftBattery == other.leftBattery && rightBattery == other.rightBattery
                && caseBattery == other.caseBattery && leftInEar == other.leftInEar
                && rightInEar == other.rightInEar && leftCharging == other.leftCharging
                && rightCharging == other.rightCharging && caseCharging == other.caseCharging
                && lidOpen == other.lidOpen && color == other.color
                && connectionState == other.connectionState;
    }
}
