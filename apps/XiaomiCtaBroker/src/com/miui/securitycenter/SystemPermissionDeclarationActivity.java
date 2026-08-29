/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.miui.securitycenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

/** Minimal, user-visible replacement for HyperOS' CTA declaration screen. */
public final class SystemPermissionDeclarationActivity extends Activity {
    // Result values consumed by Xiaomi Account and Mi Wallet.
    private static final int RESULT_AGREED = 1;
    private static final int RESULT_DENIED = 666;

    private boolean mFinished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String callerName = intent.getStringExtra("app_name");
        String purpose = intent.getStringExtra("all_purpose");
        if (TextUtils.isEmpty(callerName)) {
            callerName = getString(R.string.unknown_app);
        }
        if (TextUtils.isEmpty(purpose)) {
            purpose = getString(R.string.default_purpose);
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.declaration_title, callerName))
                .setMessage(purpose)
                .setCancelable(false)
                .setNegativeButton(R.string.disagree, (dialog, which) -> finishWith(RESULT_DENIED))
                .setPositiveButton(R.string.agree, (dialog, which) -> finishWith(RESULT_AGREED))
                .setOnDismissListener(dialog -> {
                    if (!mFinished) finishWith(RESULT_DENIED);
                })
                .show();
    }

    private void finishWith(int resultCode) {
        if (mFinished) return;
        mFinished = true;
        setResult(resultCode);
        finish();
    }
}
