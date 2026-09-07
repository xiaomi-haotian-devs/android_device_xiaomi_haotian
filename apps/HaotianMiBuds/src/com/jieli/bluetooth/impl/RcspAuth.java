/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package com.jieli.bluetooth.impl;

/**
 * Narrow JNI declaration matching the public authentication entry points in
 * Jieli's Apache-2.0 libjl_bluetooth.so.
 *
 * <p>The upstream library registers these four methods by class and method
 * name during JNI_OnLoad. Keeping this bridge deliberately small avoids
 * importing the SDK's unrelated connection, file-transfer and OTA surface.</p>
 */
public final class RcspAuth {
    static {
        System.loadLibrary("jl_bluetooth");
    }

    private final boolean initialized = nativeInit();

    public boolean isInitialized() {
        return initialized;
    }

    public byte[] getRandomData() {
        return initialized ? getRandomAuthData() : null;
    }

    public byte[] getAuthData(byte[] challenge) {
        return initialized && challenge != null ? getEncryptedAuthData(challenge) : null;
    }

    public native boolean nativeInit();
    public native byte[] getRandomAuthData();
    public native int setLinkKey(byte[] linkKey);
    public native byte[] getEncryptedAuthData(byte[] challenge);
}
