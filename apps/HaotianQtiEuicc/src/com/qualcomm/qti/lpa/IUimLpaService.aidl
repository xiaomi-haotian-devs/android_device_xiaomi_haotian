/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */
package com.qualcomm.qti.lpa;

import com.qualcomm.qti.lpa.IUimLpaServiceCallback;

interface IUimLpaService {
    int registerCallback(IUimLpaServiceCallback callback);
    int deregisterCallback(IUimLpaServiceCallback callback);
    int uimLpaAddProfile(int slot, int token, String activationCode, String confirmationCode);
    int uimLpaEnableProfile(int slot, int token, in byte[] iccid);
    int uimLpaDisableProfile(int slot, int token, in byte[] iccid);
    int uimLpaDeleteProfile(int slot, int token, in byte[] iccid);
    int uimLpaUpdateNickname(int slot, int token, in byte[] iccid, String nickname);
    int uimLpaeUICCMemoryReset(int slot, int token, int option);
    int uimLpaGetProfiles(int slot, int token);
    int uimLpaGetEid(int slot, int token);
    int uimLpaUserConsent(int slot, int token, boolean userOk);
    int uimLpaGetSrvAddr(int slot, int token);
    int uimLpaSetSrvAddr(int slot, int token, String smdp);
    int uimLpaConfirmationcodeReq(int slot, int token, String confirmationCode);
    int uimLpaUserConsent_1_1(int slot, int token, boolean userOk, int nokReason);
    int uimLpaGetEuiccInfo2(int slot, int token);
    int uimLpaUserConsent_1_3(
            int slot, int token, boolean userOk, int nokReason, int userConsentType);
}
