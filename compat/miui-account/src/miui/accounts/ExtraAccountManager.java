/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package miui.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;

/** Minimal compatibility surface used by XiaomiAccount on non-HyperOS builds. */
public final class ExtraAccountManager {
    private static final String XIAOMI_ACCOUNT_TYPE = "com.xiaomi";

    private ExtraAccountManager() {
    }

    public static Account getXiaomiAccount(Context context) {
        if (context == null) {
            return null;
        }
        Account[] accounts = AccountManager.get(context)
                .getAccountsByType(XIAOMI_ACCOUNT_TYPE);
        return accounts.length == 0 ? null : accounts[0];
    }
}
