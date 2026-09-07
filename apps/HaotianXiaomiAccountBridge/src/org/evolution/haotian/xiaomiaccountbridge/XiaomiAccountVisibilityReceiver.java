/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.haotian.xiaomiaccountbridge;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restores the package-scoped Xiaomi account visibility inherited from stock signing. */
public final class XiaomiAccountVisibilityReceiver extends BroadcastReceiver {
    private static final String TAG = "HaotianXiaomiAccount";
    private static final String XIAOMI_ACCOUNT_TYPE = "com.xiaomi";
    private static final String[] ACCOUNT_CLIENT_PACKAGES = {
            "com.miui.tsmclient",
            "com.miui.nextpay",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        AccountManager accountManager = AccountManager.get(context);
        for (Account account : accountManager.getAccountsByType(XIAOMI_ACCOUNT_TYPE)) {
            for (String packageName : ACCOUNT_CLIENT_PACKAGES) {
                exposeAccount(accountManager, account, packageName);
            }
        }
    }

    private static void exposeAccount(AccountManager accountManager, Account account,
            String packageName) {
        try {
            if (!accountManager.setAccountVisibility(account, packageName,
                    AccountManager.VISIBILITY_VISIBLE)) {
                Log.w(TAG, "Unable to expose Xiaomi account to " + packageName);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to expose Xiaomi account to " + packageName, e);
        }
    }
}
