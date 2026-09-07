/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miui.securitycenter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** Restores the wallet route policy supplied by HyperOS. */
public final class XiaomiAccountVisibilityReceiver extends BroadcastReceiver {
    private static final String TAG = "XiaomiAccountVisibility";
    private static final String NFC_PAYMENT_DEFAULT_COMPONENT =
            "nfc_payment_default_component";
    private static final String SIM_WALLET_COMPONENT =
            "com.android.nfc/com.android.nfc.cardemulation.SIMWalletDummyService";
    private static final String ESE_WALLET_COMPONENT =
            "com.android.nfc/com.android.nfc.cardemulation.ESEWalletDummyService";

    @Override
    public void onReceive(Context context, Intent intent) {
        // HyperOS exposes separate SIM and eSE routes, but AOSP Settings gives
        // both stock dummy services the same "NFC service" label.  Preserve a
        // user's real third-party wallet choice, while migrating the ambiguous
        // stock default to the eSE route required by Mi Smart Cards.
        String paymentComponent = Settings.Secure.getString(
                context.getContentResolver(), NFC_PAYMENT_DEFAULT_COMPONENT);
        if (paymentComponent == null || paymentComponent.isEmpty()
                || SIM_WALLET_COMPONENT.equals(paymentComponent)) {
            if (!Settings.Secure.putString(context.getContentResolver(),
                    NFC_PAYMENT_DEFAULT_COMPONENT, ESE_WALLET_COMPONENT)) {
                Log.w(TAG, "Unable to select the Xiaomi Wallet eSE route");
            }
        }
    }
}
