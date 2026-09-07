/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.xiaomi.haotian.airpods;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.util.Locale;

/** Device-protected private storage for keys returned by AACP opcode 0x31. */
final class AirPodsProximityKeyStore {
    private static final String PREFS = "airpods_proximity_keys";
    private static final String ACTIVE_DEVICE = "active_device";
    private static final int KEY_IRK = 0x01;
    private static final int KEY_ENCRYPTION = 0x04;

    private final SharedPreferences preferences;

    AirPodsProximityKeyStore(Context context) {
        preferences = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void setActiveDevice(String address) {
        String normalized = normalizeAddress(address);
        if (!normalized.isEmpty()) preferences.edit().putString(ACTIVE_DEVICE, normalized).apply();
    }

    String getActiveDevice() {
        return normalizeAddress(preferences.getString(ACTIVE_DEVICE, ""));
    }

    boolean updateFromAacp(String address, byte[] body) {
        String normalized = normalizeAddress(address);
        if (normalized.isEmpty() || body == null || body.length < 1) return false;
        int count = body[0] & 0xff;
        if (count < 1 || count > 8) return false;
        int offset = 1;
        boolean changed = false;
        SharedPreferences.Editor editor = preferences.edit().putString(ACTIVE_DEVICE, normalized);
        for (int i = 0; i < count; i++) {
            if (offset + 4 > body.length) return false;
            int type = body[offset] & 0xff;
            int length = body[offset + 2] & 0xff;
            offset += 4;
            if (length < 1 || length > 64 || offset + length > body.length) return false;
            if ((type == KEY_IRK || type == KEY_ENCRYPTION) && length == 16) {
                byte[] key = java.util.Arrays.copyOfRange(body, offset, offset + length);
                String encoded = Base64.encodeToString(key, Base64.NO_WRAP);
                String name = keyName(normalized, type);
                if (!encoded.equals(preferences.getString(name, ""))) {
                    editor.putString(name, encoded);
                    changed = true;
                }
            }
            offset += length;
        }
        editor.apply();
        return changed;
    }

    byte[] getIrk() {
        return getKey(getActiveDevice(), KEY_IRK);
    }

    byte[] getEncryptionKey() {
        return getKey(getActiveDevice(), KEY_ENCRYPTION);
    }

    private byte[] getKey(String address, int type) {
        if (address.isEmpty()) return null;
        String encoded = preferences.getString(keyName(address, type), "");
        if (encoded.isEmpty()) return null;
        try {
            byte[] value = Base64.decode(encoded, Base64.DEFAULT);
            return value.length == 16 ? value : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String keyName(String address, int type) {
        return (type == KEY_IRK ? "irk:" : "enc:") + address;
    }

    private static String normalizeAddress(String address) {
        if (address == null) return "";
        String normalized = address.toUpperCase(Locale.ROOT);
        return normalized.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}") ? normalized : "";
    }
}
