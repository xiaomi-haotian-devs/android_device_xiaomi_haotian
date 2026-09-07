/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio.provider;

/** Stable wear-state values shared by all headphone companion implementations. */
public final class HeadphoneWearState {
    public static final int UNKNOWN = 0;
    public static final int IN_EAR = 1;
    public static final int OUT_OF_EAR = 2;
    public static final int IN_CASE = 3;
    public static final int DISCONNECTED = 4;

    private HeadphoneWearState() {
    }
}
