/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */
package com.qualcomm.qti.lpa;

import com.qualcomm.qti.lpa.UimLpaDownloadProgress;
import com.qualcomm.qti.lpa.UimLpaProfile;

interface IUimLpaServiceCallback {
    void uimLpaAddProfileResponse(int slot, int token, int responseCode);
    void uimLpaEnableProfileResponse(int slot, int token, int responseCode);
    void uimLpaDisableProfileResponse(int slot, int token, int responseCode);
    void uimLpaDeleteProfileResponse(int slot, int token, int responseCode);
    void uimLpaUpdateNicknameResponse(int slot, int token, int responseCode);
    void uimLpaeUICCMemoryResetResponse(int slot, int token, int responseCode);
    void uimLpaGetProfilesResponse(
            int slot, int token, int responseCode, in UimLpaProfile[] profiles);
    void uimLpaGetEidResponse(int slot, int token, int responseCode, in byte[] eid);
    void uimLpaGetSrvAddrResponse(
            int slot, int token, int responseCode, String smdp, String smds);
    void uimLpaSetSrvAddrResponse(int slot, int token, int responseCode);
    void uimLpaDownloadProgressIndication(
            int slot, int responseCode, in UimLpaDownloadProgress progress);
    void uimLpaRadioStateIndication(int slot, int state);
    void uimLpaConfirmationCodeResponse(int slot, int token, int responseCode);
    void uimLpaGetEuiccInfo2Response(
            int slot, int token, int responseCode, in byte[] euiccInfo2);
    void uimLpaUserConsentIndication(int slot, int userConsentType);
}
