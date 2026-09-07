/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.haotian.qtilpa;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.euicc.DownloadSubscriptionResult;
import android.service.euicc.EuiccProfileInfo;
import android.service.euicc.EuiccService;
import android.service.euicc.GetDefaultDownloadableSubscriptionListResult;
import android.service.euicc.GetDownloadableSubscriptionMetadataResult;
import android.service.euicc.GetEuiccProfileInfoListResult;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccCardManager;
import android.telephony.euicc.EuiccInfo;
import android.telephony.euicc.EuiccManager;
import android.text.TextUtils;
import android.util.Log;

import com.qualcomm.qti.lpa.IUimLpaService;
import com.qualcomm.qti.lpa.IUimLpaServiceCallback;
import com.qualcomm.qti.lpa.UimLpaDownloadProgress;
import com.qualcomm.qti.lpa.UimLpaProfile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adapts Android's EuiccService API to Qualcomm's modem-side LPA.
 *
 * <p>The Qualcomm Android 16 service owns the HTTPS relay and the stable vendor LPA AIDL
 * callbacks. This service intentionally talks only to its public, signature-protected Binder API.
 */
public final class QtiEuiccService extends EuiccService {
    private static final String TAG = "HaotianQtiEuicc";

    private static final ComponentName BACKEND_COMPONENT = new ComponentName(
            "com.qualcomm.qti.lpa", "com.qualcomm.qti.lpa.UimLpaService");

    private static final long CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final long REQUEST_TIMEOUT_MILLIS = 60_000;
    private static final long RESOLUTION_CANCEL_TIMEOUT_MILLIS = 30_000;
    private static final long DOWNLOAD_TIMEOUT_MILLIS = 15 * 60_000;

    private static final int QTI_RESULT_SUCCESS = 0;
    private static final int QTI_RESULT_CONFIRMATION_CODE_MISSING = 2;

    private static final int DOWNLOAD_STATUS_ERROR = 1;
    private static final int DOWNLOAD_STATUS_INSTALLATION_COMPLETE = 4;
    private static final int DOWNLOAD_STATUS_GET_USER_CONSENT = 5;
    private static final int DOWNLOAD_STATUS_SEND_CONFIRMATION_CODE = 6;

    private static final int USER_CONSENT_SIMPLE = 1;

    private static final String ACTIVATION_CODE_URI_PREFIX = "LPA:";

    // Qualcomm's own EuiccService uses 2 for a generic implementation-specific failure.
    private static final int RESULT_QTI_GENERIC_ERROR = RESULT_FIRST_USER + 1;

    private final Object mConnectionLock = new Object();
    private final Object mOperationLock = new Object();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger mNextToken = new AtomicInteger(100_000);
    private final ConcurrentHashMap<Integer, CompletableFuture<QtiResult>> mPendingRequests =
            new ConcurrentHashMap<>();

    @Nullable private volatile IUimLpaService mBackend;
    @Nullable private volatile DownloadSession mDownloadSession;
    private boolean mBound;
    private boolean mDestroyed;

    private final ServiceConnection mBackendConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IUimLpaService backend = IUimLpaService.Stub.asInterface(binder);
            synchronized (mConnectionLock) {
                if (mDestroyed) {
                    return;
                }
                try {
                    if (backend == null || backend.registerCallback(mBackendCallback) != 0) {
                        Log.e(TAG, "Qualcomm LPA callback registration failed");
                        backend = null;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Qualcomm LPA callback registration failed", e);
                    backend = null;
                }
                mBackend = backend;
                mConnectionLock.notifyAll();
            }
            if (backend != null) {
                Log.i(TAG, "Connected to Qualcomm modem LPA backend");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            disconnectBackend("backend disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            disconnectBackend("backend binding died");
            abandonBinding(true);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            disconnectBackend("backend returned a null binding");
            abandonBinding(false);
        }
    };

    private final IUimLpaServiceCallback mBackendCallback =
            new IUimLpaServiceCallback.Stub() {
                @Override
                public void uimLpaAddProfileResponse(int slot, int token, int responseCode) {
                    DownloadSession session = mDownloadSession;
                    if (session == null || session.mSlot != slot || session.mToken != token) {
                        Log.w(TAG, "Ignoring unmatched add-profile response");
                        return;
                    }
                    if (responseCode == QTI_RESULT_SUCCESS) {
                        // This acknowledges that the modem accepted ADD_PROFILE.
                        // Completion arrives separately as an installation-complete
                        // progress indication after all HTTP and card work finishes.
                        Log.d(TAG, "Qualcomm LPA accepted the add-profile request");
                    } else if (responseCode == QTI_RESULT_CONFIRMATION_CODE_MISSING) {
                        session.complete(DownloadOutcome.confirmationRequired());
                    } else {
                        session.complete(session.failureOutcome());
                    }
                }

                @Override
                public void uimLpaEnableProfileResponse(int slot, int token, int responseCode) {
                    completeRequest(token, QtiResult.code(responseCode));
                }

                @Override
                public void uimLpaDisableProfileResponse(int slot, int token, int responseCode) {
                    completeRequest(token, QtiResult.code(responseCode));
                }

                @Override
                public void uimLpaDeleteProfileResponse(int slot, int token, int responseCode) {
                    completeRequest(token, QtiResult.code(responseCode));
                }

                @Override
                public void uimLpaUpdateNicknameResponse(int slot, int token, int responseCode) {
                    completeRequest(token, QtiResult.code(responseCode));
                }

                @Override
                public void uimLpaeUICCMemoryResetResponse(int slot, int token, int responseCode) {
                    completeRequest(token, QtiResult.code(responseCode));
                }

                @Override
                public void uimLpaGetProfilesResponse(
                        int slot, int token, int responseCode, UimLpaProfile[] profiles) {
                    completeRequest(token, QtiResult.profiles(responseCode, profiles));
                }

                @Override
                public void uimLpaGetEidResponse(
                        int slot, int token, int responseCode, byte[] eid) {
                    completeRequest(token, QtiResult.bytes(responseCode, eid));
                }

                @Override
                public void uimLpaGetSrvAddrResponse(
                        int slot, int token, int responseCode, String smdp, String smds) {
                    completeRequest(token, QtiResult.code(responseCode));
                }

                @Override
                public void uimLpaSetSrvAddrResponse(int slot, int token, int responseCode) {
                    completeRequest(token, QtiResult.code(responseCode));
                }

                @Override
                public void uimLpaDownloadProgressIndication(
                        int slot, int responseCode, UimLpaDownloadProgress progress) {
                    handleDownloadProgress(slot, progress);
                }

                @Override
                public void uimLpaRadioStateIndication(int slot, int state) {
                    Log.d(TAG, "Radio state changed for LPA slot " + slot + ": " + state);
                }

                @Override
                public void uimLpaConfirmationCodeResponse(
                        int slot, int token, int responseCode) {
                    DownloadSession session = mDownloadSession;
                    if (session == null || session.mSlot != slot
                            || session.mConfirmationRequestToken != token) {
                        Log.d(TAG, "Ignoring unmatched confirmation-code response");
                        return;
                    }
                    if (responseCode != QTI_RESULT_SUCCESS) {
                        session.complete(DownloadOutcome.confirmationRequired());
                    }
                }

                @Override
                public void uimLpaGetEuiccInfo2Response(
                        int slot, int token, int responseCode, byte[] euiccInfo2) {
                    completeRequest(token, QtiResult.bytes(responseCode, euiccInfo2));
                }

                @Override
                public void uimLpaUserConsentIndication(int slot, int userConsentType) {
                    DownloadSession session = mDownloadSession;
                    if (session == null || session.mSlot != slot) {
                        return;
                    }
                    session.mUserConsentType = userConsentType;
                    handleUserConsentRequest(session);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (mConnectionLock) {
            mDestroyed = false;
        }
        bindBackend();
    }

    @Override
    public void onDestroy() {
        IUimLpaService backend;
        boolean shouldUnbind;
        synchronized (mConnectionLock) {
            mDestroyed = true;
            backend = mBackend;
            shouldUnbind = mBound;
            mBound = false;
            mBackend = null;
            mConnectionLock.notifyAll();
        }
        if (backend != null) {
            try {
                backend.deregisterCallback(mBackendCallback);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to deregister Qualcomm LPA callback", e);
            }
        }
        if (shouldUnbind) {
            unbindService(mBackendConnection);
        }
        mMainHandler.removeCallbacksAndMessages(null);
        failPendingRequests("service destroyed");
        super.onDestroy();
    }

    @Override
    @Nullable
    public String onGetEid(int slotId) {
        synchronized (mOperationLock) {
            QtiResult result = request(slotId, (backend, token) ->
                    backend.uimLpaGetEid(slotId, token));
            if (result.isSuccess()) {
                String eid = bytesToHex(result.mBytes);
                if (!TextUtils.isEmpty(eid)) {
                    return eid;
                }
            }
            // Qualcomm's generic UIM initialization already publishes the EID in
            // card status, even on products where the optional modem-LPA command
            // path is disabled. Preserve that working framework source.
            UiccCardInfo card = getCardInfoForSlot(slotId);
            return card == null ? null : card.getEid();
        }
    }

    @Override
    public int onGetOtaStatus(int slotId) {
        return EuiccManager.EUICC_OTA_STATUS_UNAVAILABLE;
    }

    @Override
    public void onStartOtaIfNecessary(
            int slotId, @NonNull OtaStatusChangedCallback statusChangedCallback) {
        statusChangedCallback.onOtaStatusChanged(EuiccManager.EUICC_OTA_STATUS_UNAVAILABLE);
    }

    @Override
    @NonNull
    public GetDownloadableSubscriptionMetadataResult onGetDownloadableSubscriptionMetadata(
            int slotId, @NonNull DownloadableSubscription subscription,
            boolean forceDeactivateSim) {
        // Qualcomm's modem LPA fetches and authenticates metadata during ADD_PROFILE.
        return new GetDownloadableSubscriptionMetadataResult(RESULT_OK, subscription);
    }

    @Override
    @NonNull
    public GetDownloadableSubscriptionMetadataResult onGetDownloadableSubscriptionMetadata(
            int slotId, int portIndex, @NonNull DownloadableSubscription subscription,
            boolean forceDeactivateSim) {
        return onGetDownloadableSubscriptionMetadata(slotId, subscription, forceDeactivateSim);
    }

    @Override
    @NonNull
    public GetDefaultDownloadableSubscriptionListResult
            onGetDefaultDownloadableSubscriptionList(int slotId, boolean forceDeactivateSim) {
        return new GetDefaultDownloadableSubscriptionListResult(
                RESULT_QTI_GENERIC_ERROR, null);
    }

    @Override
    @NonNull
    public DownloadSubscriptionResult onDownloadSubscription(
            int slotIndex, int portIndex, @NonNull DownloadableSubscription subscription,
            boolean switchAfterDownload, boolean forceDeactivateSim,
            @NonNull Bundle resolvedBundle) {
        synchronized (mOperationLock) {
            return downloadSubscription(
                    slotIndex, subscription, switchAfterDownload, resolvedBundle);
        }
    }

    @Override
    @NonNull
    public DownloadSubscriptionResult onDownloadSubscription(
            int slotId, @NonNull DownloadableSubscription subscription,
            boolean switchAfterDownload, boolean forceDeactivateSim,
            @Nullable Bundle resolvedBundle) {
        synchronized (mOperationLock) {
            return downloadSubscription(
                    slotId, subscription, switchAfterDownload,
                    resolvedBundle == null ? Bundle.EMPTY : resolvedBundle);
        }
    }

    @Override
    @NonNull
    public GetEuiccProfileInfoListResult onGetEuiccProfileInfoList(int slotId) {
        synchronized (mOperationLock) {
            QtiResult result = getProfiles(slotId);
            if (!result.isSuccess()) {
                return new GetEuiccProfileInfoListResult(
                        RESULT_QTI_GENERIC_ERROR, null, true);
            }
            List<EuiccProfileInfo> profiles = new ArrayList<>();
            for (UimLpaProfile profile : result.getProfiles()) {
                EuiccProfileInfo converted = toEuiccProfile(profile);
                if (converted != null) {
                    profiles.add(converted);
                }
            }
            return new GetEuiccProfileInfoListResult(
                    RESULT_OK, profiles.toArray(new EuiccProfileInfo[0]), true);
        }
    }

    @Override
    @NonNull
    public EuiccInfo onGetEuiccInfo(int slotId) {
        synchronized (mOperationLock) {
            QtiResult result = request(slotId, (backend, token) ->
                    backend.uimLpaGetEuiccInfo2(slotId, token));
            return new EuiccInfo(result.isSuccess() ? bytesToHex(result.mBytes) : null);
        }
    }

    @Override
    public int onDeleteSubscription(int slotId, @NonNull String iccid) {
        synchronized (mOperationLock) {
            return toEuiccResult(request(slotId, (backend, token) ->
                    backend.uimLpaDeleteProfile(
                            slotId, token, iccid.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Override
    public int onSwitchToSubscription(
            int slotId, @Nullable String iccid, boolean forceDeactivateSim) {
        synchronized (mOperationLock) {
            return switchToSubscription(slotId, iccid);
        }
    }

    @Override
    public int onSwitchToSubscriptionWithPort(
            int slotId, int portIndex, @Nullable String iccid, boolean forceDeactivateSim) {
        synchronized (mOperationLock) {
            return switchToSubscription(slotId, iccid);
        }
    }

    @Override
    public int onUpdateSubscriptionNickname(
            int slotId, @NonNull String iccid, @NonNull String nickname) {
        synchronized (mOperationLock) {
            return toEuiccResult(request(slotId, (backend, token) ->
                    backend.uimLpaUpdateNickname(slotId, token,
                            iccid.getBytes(StandardCharsets.UTF_8), nickname)));
        }
    }

    @Override
    public int onEraseSubscriptions(int slotId) {
        // The Qualcomm reference implementation also rejects the legacy unscoped reset.
        return RESULT_QTI_GENERIC_ERROR;
    }

    @Override
    public int onEraseSubscriptions(int slotIndex, int options) {
        int qtiOptions = convertResetOptions(options);
        if (qtiOptions < 0) {
            return RESULT_QTI_GENERIC_ERROR;
        }
        synchronized (mOperationLock) {
            return toEuiccResult(request(slotIndex, (backend, token) ->
                    backend.uimLpaeUICCMemoryReset(slotIndex, token, qtiOptions)));
        }
    }

    @Override
    public int onRetainSubscriptionsForFactoryReset(int slotId) {
        return RESULT_QTI_GENERIC_ERROR;
    }

    @Override
    public long onGetAvailableMemoryInBytes(int slotId) {
        return EuiccManager.EUICC_MEMORY_FIELD_UNAVAILABLE;
    }

    private void bindBackend() {
        synchronized (mConnectionLock) {
            if (mDestroyed || mBound) {
                return;
            }
            Intent intent = new Intent().setComponent(BACKEND_COMPONENT);
            mBound = bindService(intent, mBackendConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!mBound) {
                Log.e(TAG, "Unable to bind Qualcomm LPA backend");
                mConnectionLock.notifyAll();
            }
        }
    }

    private void abandonBinding(boolean reconnect) {
        boolean shouldUnbind;
        synchronized (mConnectionLock) {
            shouldUnbind = mBound;
            mBound = false;
        }
        if (shouldUnbind) {
            unbindService(mBackendConnection);
        }
        if (reconnect) {
            mMainHandler.post(this::bindBackend);
        }
    }

    @Nullable
    private IUimLpaService awaitBackend() {
        long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MILLIS;
        synchronized (mConnectionLock) {
            if (!mBound) {
                mMainHandler.post(this::bindBackend);
            }
            while (mBackend == null) {
                if (mDestroyed) {
                    return null;
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    Log.e(TAG, "Timed out connecting to Qualcomm LPA backend");
                    return null;
                }
                try {
                    mConnectionLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return mBackend;
        }
    }

    private void disconnectBackend(String reason) {
        synchronized (mConnectionLock) {
            mBackend = null;
            mConnectionLock.notifyAll();
        }
        failPendingRequests(reason);
    }

    private void failPendingRequests(String reason) {
        Log.w(TAG, "Failing active LPA requests: " + reason);
        for (CompletableFuture<QtiResult> future : mPendingRequests.values()) {
            future.complete(QtiResult.failure());
        }
        DownloadSession session = mDownloadSession;
        if (session != null) {
            session.complete(DownloadOutcome.error(session.mLastFailureCause));
        }
    }

    private QtiResult request(int slot, QtiRequest request) {
        if (slot < 0) {
            Log.e(TAG, "Rejecting LPA request for invalid slot " + slot);
            return QtiResult.failure();
        }
        IUimLpaService backend = awaitBackend();
        if (backend == null) {
            return QtiResult.failure();
        }
        int token = nextToken();
        CompletableFuture<QtiResult> future = new CompletableFuture<>();
        mPendingRequests.put(token, future);
        try {
            if (request.send(backend, token) != QTI_RESULT_SUCCESS) {
                return QtiResult.failure();
            }
            return future.get(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RemoteException | ExecutionException | TimeoutException e) {
            Log.e(TAG, "Qualcomm LPA request failed for slot " + slot, e);
            return QtiResult.failure();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return QtiResult.failure();
        } finally {
            mPendingRequests.remove(token);
        }
    }

    private void completeRequest(int token, QtiResult result) {
        CompletableFuture<QtiResult> future = mPendingRequests.get(token);
        if (future != null) {
            future.complete(result);
        } else {
            Log.d(TAG, "Ignoring response for expired LPA token");
        }
    }

    @NonNull
    private DownloadSubscriptionResult downloadSubscription(
            int slot, DownloadableSubscription subscription, boolean switchAfterDownload,
            Bundle resolvedBundle) {
        int cardId = getCardIdForSlot(slot);
        String activationCode = subscription.getEncodedActivationCode();
        if (!TextUtils.isEmpty(activationCode)
                && activationCode.regionMatches(true, 0, ACTIVATION_CODE_URI_PREFIX, 0,
                        ACTIVATION_CODE_URI_PREFIX.length())) {
            // Android's QR parser preserves the LPA URI scheme, while Qualcomm's modem-side
            // LPA expects the GSMA activation-code payload beginning with its format version.
            activationCode = activationCode.substring(ACTIVATION_CODE_URI_PREFIX.length());
            Log.d(TAG, "Removed QR URI scheme from activation code");
        }
        if (TextUtils.isEmpty(activationCode)) {
            Log.e(TAG, "Rejecting profile download with an empty activation code");
            return new DownloadSubscriptionResult(
                    RESULT_QTI_GENERIC_ERROR, 0, cardId);
        }

        String confirmationCode = subscription.getConfirmationCode();
        if (TextUtils.isEmpty(confirmationCode)) {
            confirmationCode = resolvedBundle.getString(EXTRA_RESOLUTION_CONFIRMATION_CODE);
        }
        boolean policyAccepted = resolvedBundle.getBoolean(
                EXTRA_RESOLUTION_ALLOW_POLICY_RULES, false);
        Set<String> profilesBefore = null;
        if (switchAfterDownload) {
            QtiResult before = getProfiles(slot);
            if (before.isSuccess()) {
                profilesBefore = getProfileIccids(before);
            } else {
                Log.w(TAG, "Could not snapshot profiles before download; automatic switch "
                        + "will be skipped if the download succeeds");
            }
        }

        IUimLpaService backend = awaitBackend();
        if (backend == null) {
            return new DownloadSubscriptionResult(
                    RESULT_QTI_GENERIC_ERROR, 0, cardId);
        }

        int token = nextToken();
        DownloadSession session = new DownloadSession(
                slot, token, confirmationCode, policyAccepted);
        mDownloadSession = session;
        DownloadOutcome outcome;
        try {
            int accepted = backend.uimLpaAddProfile(
                    slot, token, activationCode, confirmationCode);
            if (accepted != QTI_RESULT_SUCCESS) {
                Log.e(TAG, "Qualcomm LPA synchronously rejected add-profile for slot " + slot
                        + " with result " + accepted);
                return new DownloadSubscriptionResult(
                        RESULT_QTI_GENERIC_ERROR, 0, cardId);
            }
            outcome = session.mOutcome.get(DOWNLOAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RemoteException | ExecutionException | TimeoutException e) {
            Log.e(TAG, "Profile download failed for slot " + slot, e);
            outcome = DownloadOutcome.error(session.mLastFailureCause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = DownloadOutcome.error(session.mLastFailureCause);
        } finally {
            if (mDownloadSession == session) {
                mDownloadSession = null;
            }
        }

        if (outcome.mType == DownloadOutcome.TYPE_CONFIRMATION_REQUIRED) {
            int resolvableErrors = RESOLVABLE_ERROR_CONFIRMATION_CODE;
            if (policyAccepted) {
                // EuiccOperation does not retain policy consent across two
                // separate resolution dialogs. Ask for both again so a
                // policy-then-confirmation sequence cannot loop forever.
                resolvableErrors |= RESOLVABLE_ERROR_POLICY_RULES;
            }
            return new DownloadSubscriptionResult(RESULT_RESOLVABLE_ERRORS,
                    resolvableErrors, cardId);
        }
        if (outcome.mType == DownloadOutcome.TYPE_POLICY_REQUIRED) {
            return new DownloadSubscriptionResult(RESULT_RESOLVABLE_ERRORS,
                    RESOLVABLE_ERROR_POLICY_RULES, cardId);
        }
        if (outcome.mType != DownloadOutcome.TYPE_SUCCESS) {
            Log.e(TAG, "Profile download ended with QTI cause " + outcome.mFailureCause);
            return new DownloadSubscriptionResult(
                    RESULT_QTI_GENERIC_ERROR, 0, cardId);
        }

        if (switchAfterDownload && !enableDownloadedProfile(slot, profilesBefore)) {
            Log.e(TAG, "Profile was installed but could not be enabled");
            return new DownloadSubscriptionResult(
                    RESULT_QTI_GENERIC_ERROR, 0, cardId);
        }
        return new DownloadSubscriptionResult(RESULT_OK, 0, cardId);
    }

    private void handleDownloadProgress(int slot, @Nullable UimLpaDownloadProgress progress) {
        DownloadSession session = mDownloadSession;
        if (session == null || session.mSlot != slot || progress == null) {
            return;
        }
        session.mLastFailureCause = progress.getCause();
        if (progress.getUserConsentType() != 0) {
            session.mUserConsentType = progress.getUserConsentType();
        }
        Log.i(TAG, "Download state=" + progress.getStatus()
                + " progress=" + progress.getProgress()
                + " cause=" + progress.getCause());

        switch (progress.getStatus()) {
            case DOWNLOAD_STATUS_ERROR:
                session.complete(session.failureOutcome());
                break;
            case DOWNLOAD_STATUS_INSTALLATION_COMPLETE:
                session.complete(DownloadOutcome.success());
                break;
            case DOWNLOAD_STATUS_GET_USER_CONSENT:
                handleUserConsentRequest(session);
                break;
            case DOWNLOAD_STATUS_SEND_CONFIRMATION_CODE:
                handleConfirmationCodeRequest(session);
                break;
            default:
                if (progress.getUserConsent()) {
                    handleUserConsentRequest(session);
                }
                break;
        }
    }

    private void handleUserConsentRequest(DownloadSession session) {
        if (!session.markConsentHandled()) {
            return;
        }
        if (session.mUserConsentType != USER_CONSENT_SIMPLE) {
            // Android's standard resolvable-errors UI can collect ordinary PPR
            // consent, but cannot satisfy Qualcomm's strong-confirmation flow.
            sendUserConsent(session, false,
                    EuiccCardManager.CANCEL_REASON_END_USER_REJECTED);
            return;
        }
        if (session.mPolicyAccepted) {
            sendUserConsent(session, true,
                    EuiccCardManager.CANCEL_REASON_END_USER_REJECTED);
        } else {
            session.requestPolicyResolution();
            // End the current modem transaction without treating the future
            // Android resolution dialog as a permanent user rejection.
            sendUserConsent(session, false,
                    EuiccCardManager.CANCEL_REASON_POSTPONED);
        }
    }

    private void handleConfirmationCodeRequest(DownloadSession session) {
        if (!session.markConfirmationHandled()) {
            return;
        }
        if (!TextUtils.isEmpty(session.mConfirmationCode)) {
            sendConfirmationCode(session, session.mConfirmationCode);
            return;
        }
        session.requestConfirmationResolution();
        sendConfirmationCode(session, null);
    }

    private void sendUserConsent(
            DownloadSession session, boolean accepted, int cancelReason) {
        mMainHandler.post(() -> {
            IUimLpaService backend = mBackend;
            if (backend == null) {
                session.complete(DownloadOutcome.error(session.mLastFailureCause));
                return;
            }
            try {
                int result = backend.uimLpaUserConsent_1_3(
                        session.mSlot, nextToken(), accepted, cancelReason,
                        session.mUserConsentType);
                if (result != QTI_RESULT_SUCCESS) {
                    session.complete(DownloadOutcome.error(session.mLastFailureCause));
                } else if (!accepted) {
                    scheduleCancellationFallback(session);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to answer the LPA user-consent request", e);
                session.complete(DownloadOutcome.error(session.mLastFailureCause));
            }
        });
    }

    private void sendConfirmationCode(
            DownloadSession session, @Nullable String confirmationCode) {
        mMainHandler.post(() -> {
            IUimLpaService backend = mBackend;
            if (backend == null) {
                session.complete(DownloadOutcome.error(session.mLastFailureCause));
                return;
            }
            try {
                int token = nextToken();
                session.mConfirmationRequestToken = token;
                int result = backend.uimLpaConfirmationcodeReq(
                        session.mSlot, token, confirmationCode);
                if (result != QTI_RESULT_SUCCESS) {
                    session.complete(DownloadOutcome.error(session.mLastFailureCause));
                } else if (confirmationCode == null) {
                    scheduleCancellationFallback(session);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to answer the LPA confirmation-code request", e);
                session.complete(DownloadOutcome.error(session.mLastFailureCause));
            }
        });
    }

    private void scheduleCancellationFallback(DownloadSession session) {
        // Qualcomm normally follows a negative answer with a terminal download
        // indication. Do not keep Android's resolution UI blocked for the full
        // download timeout if a modem revision omits that final indication.
        mMainHandler.postDelayed(() -> session.complete(session.failureOutcome()),
                RESOLUTION_CANCEL_TIMEOUT_MILLIS);
    }

    private QtiResult getProfiles(int slot) {
        return request(slot, (backend, token) -> backend.uimLpaGetProfiles(slot, token));
    }

    private int switchToSubscription(int slot, @Nullable String iccid) {
        if (iccid != null) {
            return toEuiccResult(request(slot, (backend, token) ->
                    backend.uimLpaEnableProfile(
                            slot, token, iccid.getBytes(StandardCharsets.UTF_8))));
        }
        QtiResult profiles = getProfiles(slot);
        if (!profiles.isSuccess()) {
            return RESULT_QTI_GENERIC_ERROR;
        }
        for (UimLpaProfile profile : profiles.getProfiles()) {
            if (profile.getProfileState()) {
                String enabledIccid = profileIccid(profile);
                if (enabledIccid == null) {
                    return RESULT_QTI_GENERIC_ERROR;
                }
                return toEuiccResult(request(slot, (backend, token) ->
                        backend.uimLpaDisableProfile(slot, token,
                                enabledIccid.getBytes(StandardCharsets.UTF_8))));
            }
        }
        return RESULT_OK;
    }

    private boolean enableDownloadedProfile(
            int slot, @Nullable Set<String> profilesBefore) {
        // Without a reliable before/after comparison, choosing a disabled profile
        // could activate an older subscription. Prefer a visible partial success.
        if (profilesBefore == null) {
            return false;
        }
        UimLpaProfile target = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            QtiResult after = getProfiles(slot);
            if (after.isSuccess()) {
                for (UimLpaProfile profile : after.getProfiles()) {
                    String iccid = profileIccid(profile);
                    if (iccid != null && !profilesBefore.contains(iccid)) {
                        if (target != null && !iccid.equals(profileIccid(target))) {
                            Log.e(TAG, "More than one new profile appeared after download");
                            return false;
                        }
                        target = profile;
                    }
                }
                if (target != null) {
                    break;
                }
            }
            SystemClock.sleep(1_000);
        }
        if (target == null) {
            return false;
        }
        if (target.getProfileState()) {
            return true;
        }
        String iccid = profileIccid(target);
        if (iccid == null) {
            return false;
        }
        QtiResult enabled = request(slot, (backend, token) ->
                backend.uimLpaEnableProfile(
                        slot, token, iccid.getBytes(StandardCharsets.UTF_8)));
        if (enabled.isSuccess()) {
            return true;
        }
        // Enabling can reset the radio before the response reaches the app. Verify the card state.
        SystemClock.sleep(1_000);
        QtiResult profiles = getProfiles(slot);
        if (profiles.isSuccess()) {
            for (UimLpaProfile profile : profiles.getProfiles()) {
                if (iccid.equals(profileIccid(profile)) && profile.getProfileState()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> getProfileIccids(QtiResult result) {
        // Callers only use this after checking isSuccess(), so an empty set means
        // that the card genuinely reported no profiles rather than a query error.
        Set<String> iccids = new HashSet<>();
        for (UimLpaProfile profile : result.getProfiles()) {
            String iccid = profileIccid(profile);
            if (iccid != null) {
                iccids.add(iccid);
            }
        }
        return iccids;
    }

    @Nullable
    private EuiccProfileInfo toEuiccProfile(UimLpaProfile profile) {
        String iccid = profileIccid(profile);
        if (iccid == null) {
            Log.w(TAG, "Skipping a profile with an invalid ICCID");
            return null;
        }
        return new EuiccProfileInfo.Builder(iccid)
                .setNickname(profile.getNickname())
                .setServiceProviderName(profile.getSpnName())
                .setProfileName(profile.getProfileName())
                .setState(profile.getProfileState()
                        ? EuiccProfileInfo.PROFILE_STATE_ENABLED
                        : EuiccProfileInfo.PROFILE_STATE_DISABLED)
                .setProfileClass(profile.getProfileClass())
                .setPolicyRules(profile.getProfilePolicyMask())
                .build();
    }

    @Nullable
    private String profileIccid(UimLpaProfile profile) {
        byte[] value = profile.getIccid();
        if (value == null || value.length == 0) {
            return null;
        }
        String iccid = new String(value, StandardCharsets.UTF_8).replace("\u0000", "").trim();
        if (iccid.isEmpty()) {
            return null;
        }
        for (int i = 0; i < iccid.length(); i++) {
            if (!Character.isDigit(iccid.charAt(i))) {
                return null;
            }
        }
        return iccid;
    }

    private int getCardIdForSlot(int slot) {
        UiccCardInfo card = getCardInfoForSlot(slot);
        if (card != null) {
            return card.getCardId();
        }
        TelephonyManager telephony = getSystemService(TelephonyManager.class);
        if (telephony == null) {
            return slot;
        }
        int defaultCardId = telephony.getCardIdForDefaultEuicc();
        return defaultCardId >= 0 ? defaultCardId : slot;
    }

    @Nullable
    private UiccCardInfo getCardInfoForSlot(int slot) {
        TelephonyManager telephony = getSystemService(TelephonyManager.class);
        if (telephony == null) {
            return null;
        }
        List<UiccCardInfo> cards = telephony.getUiccCardsInfo();
        if (cards == null) {
            return null;
        }
        for (UiccCardInfo card : cards) {
            if (card != null && card.isEuicc() && card.getPhysicalSlotIndex() == slot) {
                return card;
            }
        }
        return null;
    }

    private int convertResetOptions(int options) {
        int knownOptions = EuiccCardManager.RESET_OPTION_DELETE_OPERATIONAL_PROFILES
                | EuiccCardManager.RESET_OPTION_DELETE_FIELD_LOADED_TEST_PROFILES
                | EuiccCardManager.RESET_OPTION_RESET_DEFAULT_SMDP_ADDRESS;
        if (options == 0 || (options & ~knownOptions) != 0) {
            return -1;
        }
        int qtiOptions = 0;
        if ((options & EuiccCardManager.RESET_OPTION_DELETE_FIELD_LOADED_TEST_PROFILES) != 0) {
            qtiOptions |= 1;
        }
        if ((options & EuiccCardManager.RESET_OPTION_DELETE_OPERATIONAL_PROFILES) != 0) {
            qtiOptions |= 2;
        }
        if ((options & EuiccCardManager.RESET_OPTION_RESET_DEFAULT_SMDP_ADDRESS) != 0) {
            qtiOptions |= 4;
        }
        return qtiOptions;
    }

    private int nextToken() {
        return mNextToken.getAndUpdate(value -> value == Integer.MAX_VALUE ? 100_000 : value + 1);
    }

    private static int toEuiccResult(QtiResult result) {
        return result.isSuccess() ? RESULT_OK : RESULT_QTI_GENERIC_ERROR;
    }

    @Nullable
    private static String bytesToHex(@Nullable byte[] value) {
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private interface QtiRequest {
        int send(IUimLpaService backend, int token) throws RemoteException;
    }

    private static final class QtiResult {
        private final int mResponseCode;
        @Nullable private final byte[] mBytes;
        @Nullable private final UimLpaProfile[] mProfiles;

        private QtiResult(
                int responseCode, @Nullable byte[] bytes, @Nullable UimLpaProfile[] profiles) {
            mResponseCode = responseCode;
            mBytes = bytes;
            mProfiles = profiles;
        }

        static QtiResult code(int responseCode) {
            return new QtiResult(responseCode, null, null);
        }

        static QtiResult bytes(int responseCode, @Nullable byte[] bytes) {
            return new QtiResult(responseCode, bytes, null);
        }

        static QtiResult profiles(
                int responseCode, @Nullable UimLpaProfile[] profiles) {
            return new QtiResult(responseCode, null, profiles);
        }

        static QtiResult failure() {
            return code(-1);
        }

        boolean isSuccess() {
            return mResponseCode == QTI_RESULT_SUCCESS;
        }

        UimLpaProfile[] getProfiles() {
            return mProfiles == null ? new UimLpaProfile[0] : mProfiles;
        }
    }

    private static final class DownloadSession {
        private final int mSlot;
        private final int mToken;
        @Nullable private final String mConfirmationCode;
        private final boolean mPolicyAccepted;
        private final CompletableFuture<DownloadOutcome> mOutcome = new CompletableFuture<>();
        private boolean mConsentHandled;
        private boolean mConfirmationHandled;
        private volatile int mConfirmationRequestToken = -1;
        private volatile int mLastFailureCause;
        private volatile int mUserConsentType = USER_CONSENT_SIMPLE;
        private volatile int mRequestedOutcomeType = DownloadOutcome.TYPE_ERROR;

        DownloadSession(
                int slot, int token, @Nullable String confirmationCode,
                boolean policyAccepted) {
            mSlot = slot;
            mToken = token;
            mConfirmationCode = confirmationCode;
            mPolicyAccepted = policyAccepted;
        }

        synchronized boolean markConsentHandled() {
            if (mConsentHandled) {
                return false;
            }
            mConsentHandled = true;
            return true;
        }

        synchronized boolean markConfirmationHandled() {
            if (mConfirmationHandled) {
                return false;
            }
            mConfirmationHandled = true;
            return true;
        }

        void requestPolicyResolution() {
            mRequestedOutcomeType = DownloadOutcome.TYPE_POLICY_REQUIRED;
        }

        void requestConfirmationResolution() {
            mRequestedOutcomeType = DownloadOutcome.TYPE_CONFIRMATION_REQUIRED;
        }

        DownloadOutcome failureOutcome() {
            if (mRequestedOutcomeType == DownloadOutcome.TYPE_POLICY_REQUIRED) {
                return DownloadOutcome.policyRequired();
            }
            if (mRequestedOutcomeType == DownloadOutcome.TYPE_CONFIRMATION_REQUIRED) {
                return DownloadOutcome.confirmationRequired();
            }
            return DownloadOutcome.error(mLastFailureCause);
        }

        void complete(DownloadOutcome outcome) {
            mOutcome.complete(outcome);
        }
    }

    private static final class DownloadOutcome {
        private static final int TYPE_SUCCESS = 0;
        private static final int TYPE_ERROR = 1;
        private static final int TYPE_CONFIRMATION_REQUIRED = 2;
        private static final int TYPE_POLICY_REQUIRED = 3;

        private final int mType;
        private final int mFailureCause;

        private DownloadOutcome(int type, int failureCause) {
            mType = type;
            mFailureCause = failureCause;
        }

        static DownloadOutcome success() {
            return new DownloadOutcome(TYPE_SUCCESS, 0);
        }

        static DownloadOutcome error(int failureCause) {
            return new DownloadOutcome(TYPE_ERROR, failureCause);
        }

        static DownloadOutcome confirmationRequired() {
            return new DownloadOutcome(TYPE_CONFIRMATION_REQUIRED, 0);
        }

        static DownloadOutcome policyRequired() {
            return new DownloadOutcome(TYPE_POLICY_REQUIRED, 0);
        }
    }
}
