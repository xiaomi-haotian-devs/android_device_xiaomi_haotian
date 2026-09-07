/*
 * SPDX-FileCopyrightText: 2025 LibrePods contributors
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.xiaomi.haotian.airpods;

import java.security.GeneralSecurityException;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Bluetooth ah/RPA helpers used by LibrePods proximity scanning. */
final class BluetoothCryptography {
    private BluetoothCryptography() {
    }

    static boolean verifyResolvablePrivateAddress(String address, byte[] irk) {
        if (address == null || irk == null || irk.length != 16) return false;
        String[] parts = address.toUpperCase(Locale.ROOT).split(":");
        if (parts.length != 6) return false;
        try {
            byte[] rpa = new byte[6];
            for (int i = 0; i < parts.length; i++) {
                rpa[5 - i] = (byte) Integer.parseInt(parts[i], 16);
            }
            byte[] random = new byte[] {rpa[3], rpa[4], rpa[5]};
            byte[] expected = new byte[] {rpa[0], rpa[1], rpa[2]};
            byte[] actual = ah(irk, random);
            return java.util.Arrays.equals(expected, actual);
        } catch (GeneralSecurityException | NumberFormatException exception) {
            return false;
        }
    }

    static byte[] decryptAdvertisementTail(byte[] data, byte[] key) {
        if (data == null || data.length < 16 || key == null || key.length != 16) return null;
        try {
            byte[] block = java.util.Arrays.copyOfRange(data, data.length - 16, data.length);
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(block);
        } catch (GeneralSecurityException exception) {
            return null;
        }
    }

    private static byte[] ah(byte[] key, byte[] random) throws GeneralSecurityException {
        byte[] padded = new byte[16];
        System.arraycopy(random, 0, padded, 0, 3);
        byte[] encrypted = bluetoothE(key, padded);
        return java.util.Arrays.copyOfRange(encrypted, 0, 3);
    }

    private static byte[] bluetoothE(byte[] key, byte[] data) throws GeneralSecurityException {
        byte[] swappedKey = reverse(key);
        byte[] swappedData = reverse(data);
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(swappedKey, "AES"));
        return reverse(cipher.doFinal(swappedData));
    }

    private static byte[] reverse(byte[] value) {
        byte[] result = new byte[value.length];
        for (int i = 0; i < value.length; i++) result[i] = value[value.length - 1 - i];
        return result;
    }
}
