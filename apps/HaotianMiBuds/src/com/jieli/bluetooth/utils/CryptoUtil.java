/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package com.jieli.bluetooth.utils;

/**
 * Narrow JNI declaration required by Jieli's {@code libjl_bluetooth.so}.
 *
 * <p>The library looks up this class and registers {@code crc16([BS)S} from
 * {@code JNI_OnLoad}, even when callers only use the authentication methods
 * exposed by {@code RcspAuth}. Keep the source-level name and signature in
 * sync with the pinned upstream AAR.</p>
 */
public final class CryptoUtil {
    private CryptoUtil() {}

    public static short CRC16(byte[] data, short seed) {
        return crc16(data, seed);
    }

    private static native short crc16(byte[] data, short seed);
}
