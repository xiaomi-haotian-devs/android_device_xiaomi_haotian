/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.Spatializer;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single process-wide owner of Dolby, MiSound, Spatializer and transient media-ducking state.
 *
 * Settings and the patched stock workflow plugin only submit desired semantic state through the
 * signature-protected Binder interface. They never create their own global AudioEffect handles.
 */
public final class HaotianAudioService extends Service {
    static final String ACTION_STATE_CHANGED =
            "org.xiaomi.haotian.audio.action.STATE_CHANGED";
    static final String ACTION_RECENTER_HEAD_TRACKER =
            "org.xiaomi.haotian.audio.action.RECENTER_HEAD_TRACKER";
    private static final String ACTION_HEADPHONE_POSE_PROVIDER =
            "org.xiaomi.haotian.audio.action.HEADPHONE_POSE_PROVIDER";
    private static final String BIND_HEADPHONE_PROVIDER_PERMISSION =
            "org.xiaomi.haotian.audio.permission.BIND_HEADPHONE_PROVIDER";
    static final String EXTRA_APPLY_SUCCEEDED =
            "org.xiaomi.haotian.audio.extra.APPLY_SUCCEEDED";
    private static final String TAG = "HaotianAudioService";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HaotianAudioWorker");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor callbackExecutor = this::executeOnWorker;
    private final AtomicInteger headTrackerInitGeneration = new AtomicInteger();
    private int lastPlaybackFormatSignature;
    // Worker-owned, bounded reconciliation of the same device after an output rebuild.
    private int pipelineRestoreGeneration;
    private boolean pipelineRestorePending;
    private boolean pipelineRestoreReady;
    private boolean pipelineRestoreExhausted;

    private AudioManager audioManager;
    private InputManager inputManager;
    private Spatializer spatializer;
    private AudioEffectController effectController;
    private TransientMediaDucker transientMediaDucker;
    private AudioProfileStore profileStore;
    private OutputDeviceManager outputDeviceManager;
    private HeadPoseProviderRegistry headPoseProviders;
    private HeadTrackerSharedMemorySink headPoseSink;
    private volatile OutputDeviceManager.ActiveOutput activeOutput;
    private volatile AudioDeviceProfile activeProfile;
    private boolean playbackCallbackRegistered;
    private boolean keyGestureListenerRegistered;
    private boolean volumeChangedReceiverRegistered;
    private boolean spatializerStateListenerRegistered;
    private boolean headTrackerAvailableListenerRegistered;
    private boolean spatializerOutputListenerRegistered;

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        List<AudioPlaybackConfiguration> configurations) {
                    // AudioManager invokes this on the main handler. Keep all policy state and
                    // Spatializer calls serialized with profile application on the worker.
                    List<AudioPlaybackConfiguration> snapshot = List.copyOf(configurations);
                    executeOnWorker(() -> onPlaybackConfigurationChanged(snapshot));
                }
            };

    private final InputManager.KeyGestureEventListener keyGestureEventListener = event -> {
        if (event.getKeyGestureType() == KeyGestureEvent.KEY_GESTURE_TYPE_VOLUME_UP
                && event.getAction() == KeyGestureEvent.ACTION_GESTURE_COMPLETE
                && !event.isCancelled()) {
            transientMediaDucker.clearAll("volume-up key pressed");
        }
    };

    private final Spatializer.OnSpatializerStateChangedListener spatializerStateListener =
            new Spatializer.OnSpatializerStateChangedListener() {
                @Override
                public void onSpatializerEnabledChanged(Spatializer changed, boolean enabled) {
                    if (enabled) applyActiveHeadTracking("spatializer enabled", false);
                    else schedulePipelineRestore("spatializer disabled");
                }

                @Override
                public void onSpatializerAvailableChanged(Spatializer changed, boolean available) {
                    if (available) applyActiveHeadTracking("spatializer available", false);
                    schedulePipelineRestore("spatializer availability changed");
                }
            };

    private final Spatializer.OnHeadTrackerAvailableListener headTrackerAvailableListener =
            (changed, available) -> {
                if (available) applyActiveHeadTracking("head tracker available", false);
                else schedulePipelineRestore("head tracker unavailable");
            };

    private final Spatializer.OnSpatializerOutputChangedListener spatializerOutputListener =
            (changed, output) -> {
                if (output != 0) {
                    // A new output owns a new native pose controller even when all Java-side
                    // desired state is unchanged, so this is the one lifecycle callback that
                    // deliberately forces a native re-assertion.
                    applyActiveHeadTracking("spatializer output " + output, true);
                }
                schedulePipelineRestore("spatializer output changed");
            };

    private final BroadcastReceiver bluetoothIdentityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED.equals(action)) {
                executeOnWorker(() -> {
                    cancelPipelineRestore();
                    schedulePipelineRestore("Bluetooth codec changed");
                });
                return;
            }
            if (BluetoothDevice.ACTION_UUID.equals(action)
                    || BluetoothDevice.ACTION_NAME_CHANGED.equals(action)
                    || BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                reconcileActiveOutput();
            }
        }
    };

    private final BroadcastReceiver volumeChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AudioManager.ACTION_VOLUME_CHANGED.equals(intent.getAction())) {
                return;
            }
            int stream = intent.getIntExtra(
                    AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.USE_DEFAULT_STREAM_TYPE);
            int volume = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
            int previous = intent.getIntExtra(AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, -1);
            if (stream == AudioManager.STREAM_MUSIC && volume > previous) {
                executeOnWorker(() -> transientMediaDucker.clearAll("media volume raised"));
            }
        }
    };

    private final AudioManager.AudioServerStateCallback audioServerCallback =
            new AudioManager.AudioServerStateCallback() {
                @Override
                public void onAudioServerDown() {
                    executeOnWorker(() -> {
                        cancelPipelineRestore();
                        transientMediaDucker.onAudioServerDown();
                        effectController.onAudioServerDown();
                        notifyStateChanged();
                    });
                }

                @Override
                public void onAudioServerUp() {
                    scheduleApplyActive();
                    executeOnWorker(() -> transientMediaDucker.onPlaybackConfigurationsChanged(
                            List.copyOf(audioManager.getActivePlaybackConfigurations())));
                }
            };

    private final IHaotianAudioService.Stub binder = new IHaotianAudioService.Stub() {
        @Override
        public int getApiVersion() {
            return MiSoundCompatContract.API_VERSION;
        }

        @Override
        public String getActiveDeviceKey() {
            return currentDeviceKey();
        }

        @Override
        public AudioDeviceProfile getActiveProfile() {
            AudioDeviceProfile profile = activeProfile;
            return profile == null ? null : new AudioDeviceProfile(profile);
        }

        @Override
        public AudioDeviceProfile getProfile(String deviceKey) {
            String resolved = resolveDeviceKey(deviceKey);
            OutputDeviceManager.ActiveOutput output = activeOutput;
            String displayName = output != null && TextUtils.equals(output.key, resolved)
                    ? output.displayName : resolved;
            return profileStore.load(resolved, displayName);
        }

        @Override
        public void saveProfile(AudioDeviceProfile profile, boolean applyIfActive) {
            if (profile == null) throw new IllegalArgumentException("profile must not be null");
            AudioDeviceProfile requested = new AudioDeviceProfile(profile);
            executeOnWorker(() -> saveProfileOnWorker(requested, applyIfActive));
        }

        @Override
        public void requestApply() {
            scheduleApplyActive();
        }

        @Override
        public boolean areEffectsAvailable() {
            return effectController.areEffectsAvailable();
        }

        @Override
        public boolean isHeadTrackerAvailable() {
            // Spatializer reports no tracker whenever its native effect is disabled, even though
            // the independent companion transport and dynamic sensor are still producing poses.
            // Keep the settings state truthful while immersive rendering is temporarily off.
            return HeadPoseDebugState.hasRecentFrame()
                    || effectController.isHeadTrackerAvailable();
        }

        @Override
        public void requestTransientMediaDuck(
                IBinder ownerToken, String deviceKey, float volume) {
            if (ownerToken == null) {
                throw new IllegalArgumentException("ownerToken must not be null");
            }
            if (TextUtils.isEmpty(deviceKey)) {
                throw new IllegalArgumentException("deviceKey must not be empty");
            }
            if (!Float.isFinite(volume) || volume < 0.0f || volume > 1.0f) {
                throw new IllegalArgumentException("volume must be finite and between 0 and 1");
            }
            int callerUid = Binder.getCallingUid();
            executeOnWorker(() -> transientMediaDucker.request(
                    ownerToken, deviceKey, volume, callerUid));
        }

        @Override
        public void releaseTransientMediaDuck(IBinder ownerToken) {
            if (ownerToken == null) return;
            executeOnWorker(() -> transientMediaDucker.release(ownerToken));
        }

        @Override
        public void setSoundIdProfile(String deviceKey, String profileId, float[] gains,
                boolean enabled) {
            float[] requestedGains = AudioDeviceProfile.copy(gains);
            mutateProfile(deviceKey, profile -> {
                profile.soundIdProfileId = profileId == null ? "" : profileId;
                profile.soundIdGains = requestedGains;
                profile.soundIdEnabled = enabled && profile.soundIdGains.length
                        == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT;
            });
        }

        @Override
        public void setHearingProfile(String deviceKey, float[] leftGains, float[] rightGains,
                boolean enabled) {
            float[] requestedLeftGains = AudioDeviceProfile.copy(leftGains);
            float[] requestedRightGains = AudioDeviceProfile.copy(rightGains);
            mutateProfile(deviceKey, profile -> {
                profile.hearingLeftGains = requestedLeftGains;
                profile.hearingRightGains = requestedRightGains;
                profile.hearingProfileEnabled = enabled
                        && profile.hearingLeftGains.length
                                == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT
                        && profile.hearingRightGains.length
                                == AudioDeviceProfile.PERSONAL_AUDIO_BAND_COUNT;
            });
        }

        @Override
        public void setEarScanProfile(String deviceKey, float[] filterValues, boolean enabled) {
            float[] requestedFilterValues = AudioDeviceProfile.copy(filterValues);
            mutateProfile(deviceKey, profile -> {
                profile.earScanFilterValues = requestedFilterValues;
                profile.earScanEnabled = enabled && profile.earScanFilterValues.length
                        == AudioDeviceProfile.EAR_SCAN_COEFFICIENT_COUNT;
            });
        }

        @Override
        public void setHeadsetModel(String deviceKey, int modelId, String modelName) {
            mutateProfile(deviceKey, profile -> {
                profile.headsetModelId = Math.max(0, Math.min(25, modelId));
                profile.headsetModelName = modelName == null ? "" : modelName;
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = getSystemService(AudioManager.class);
        inputManager = getSystemService(InputManager.class);
        effectController = new AudioEffectController(this);
        transientMediaDucker = new TransientMediaDucker(callbackExecutor);
        profileStore = new AudioProfileStore(this);
        headPoseSink = new HeadTrackerSharedMemorySink(this);
        headPoseProviders = new HeadPoseProviderRegistry(headPoseSink,
                () -> executeOnWorker(this::onEffectiveHeadTrackingPolicyChanged));
        discoverHeadphoneProviders();
        if (audioManager == null) {
            Log.e(TAG, "AudioManager is unavailable");
            return;
        }
        spatializer = audioManager.getSpatializer();
        registerSpatializerLifecycleCallbacks();
        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler);
            playbackCallbackRegistered = true;
            List<AudioPlaybackConfiguration> initialPlayback =
                    List.copyOf(audioManager.getActivePlaybackConfigurations());
            executeOnWorker(() -> onPlaybackConfigurationChanged(initialPlayback));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not monitor playback changes", exception);
        }
        if (inputManager != null) {
            try {
                inputManager.registerKeyGestureEventListener(
                        callbackExecutor, keyGestureEventListener);
                keyGestureListenerRegistered = true;
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not monitor volume-up key gestures", exception);
            }
        }
        try {
            registerReceiver(volumeChangedReceiver,
                    new IntentFilter(AudioManager.ACTION_VOLUME_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
            volumeChangedReceiverRegistered = true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not monitor media volume changes", exception);
        }

        outputDeviceManager = new OutputDeviceManager(
                audioManager, callbackExecutor, this::onActiveOutputChanged);
        IntentFilter bluetoothIdentityFilter = new IntentFilter();
        bluetoothIdentityFilter.addAction(BluetoothDevice.ACTION_UUID);
        bluetoothIdentityFilter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        bluetoothIdentityFilter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        bluetoothIdentityFilter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        // Bluetooth broadcasts originate from the separately privileged Bluetooth UID rather
        // than this app or the core system UID, so Android requires an exported dynamic receiver.
        // These platform actions are protected broadcasts; recovery re-reads the actual
        // route and saved profile rather than trusting broadcast payloads.
        registerReceiver(bluetoothIdentityReceiver, bluetoothIdentityFilter,
                Context.RECEIVER_EXPORTED);
        outputDeviceManager.start();
        try {
            audioManager.setAudioServerStateCallback(getMainExecutor(), audioServerCallback);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not monitor audioserver state", exception);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RECENTER_HEAD_TRACKER.equals(intent.getAction())) {
            executeOnWorker(() -> {
                HeadPoseProviderRegistry providers = headPoseProviders;
                if (providers == null || !providers.recenter()) {
                    // Do not change a companion provider's recenter semantics on failure or
                    // while wear-gated. Native fallback is only for an unclaimed A2DP route.
                    OutputDeviceManager.ActiveOutput output = activeOutput;
                    if (providers == null || providers.hasProviderForCurrentOutput()
                            || output == null
                            || output.type != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return;
                    try {
                        getSystemService(android.media.AudioManager.class).getSpatializer()
                                .recenterHeadTracker();
                    } catch (RuntimeException exception) {
                        Log.w(TAG, "Native head tracker recenter unavailable", exception);
                    }
                }
            });
            return START_STICKY;
        }
        // Application/boot and settings entry can all start this persistent service. Do not
        // re-send the complete vendor effect state for an already healthy active output.
        if (activeProfile == null || !effectController.areEffectsAvailable()) {
            scheduleApplyActive();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        headTrackerInitGeneration.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        if (playbackCallbackRegistered && audioManager != null) {
            try {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not remove playback callback", exception);
            }
            playbackCallbackRegistered = false;
        }
        if (keyGestureListenerRegistered && inputManager != null) {
            try {
                inputManager.unregisterKeyGestureEventListener(keyGestureEventListener);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not remove volume-up key listener", exception);
            }
            keyGestureListenerRegistered = false;
        }
        if (volumeChangedReceiverRegistered) {
            try {
                unregisterReceiver(volumeChangedReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            volumeChangedReceiverRegistered = false;
        }
        unregisterSpatializerLifecycleCallbacks();
        if (outputDeviceManager != null) outputDeviceManager.stop();
        try {
            unregisterReceiver(bluetoothIdentityReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (headPoseProviders != null) headPoseProviders.close();
        if (headPoseSink != null) headPoseSink.close();
        if (audioManager != null) {
            try {
                audioManager.clearAudioServerStateCallback();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not clear audioserver callback", exception);
            }
        }
        executeOnWorker(() -> {
            if (transientMediaDucker != null) transientMediaDucker.close();
            if (effectController != null) effectController.release();
        });
        worker.shutdown();
        super.onDestroy();
    }

    private void registerSpatializerLifecycleCallbacks() {
        if (spatializer == null) return;
        try {
            spatializer.addOnSpatializerStateChangedListener(
                    callbackExecutor, spatializerStateListener);
            spatializerStateListenerRegistered = true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not monitor Spatializer state", exception);
        }
        try {
            spatializer.addOnHeadTrackerAvailableListener(
                    callbackExecutor, headTrackerAvailableListener);
            headTrackerAvailableListenerRegistered = true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not monitor head-tracker availability", exception);
        }
        try {
            spatializer.setOnSpatializerOutputChangedListener(
                    callbackExecutor, spatializerOutputListener);
            spatializerOutputListenerRegistered = true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not monitor Spatializer output", exception);
        }
    }

    private void unregisterSpatializerLifecycleCallbacks() {
        if (spatializer == null) return;
        if (spatializerOutputListenerRegistered) {
            try {
                spatializer.clearOnSpatializerOutputChangedListener();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not remove Spatializer output listener", exception);
            }
            spatializerOutputListenerRegistered = false;
        }
        if (headTrackerAvailableListenerRegistered) {
            try {
                spatializer.removeOnHeadTrackerAvailableListener(
                        headTrackerAvailableListener);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not remove head-tracker availability listener", exception);
            }
            headTrackerAvailableListenerRegistered = false;
        }
        if (spatializerStateListenerRegistered) {
            try {
                spatializer.removeOnSpatializerStateChangedListener(spatializerStateListener);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not remove Spatializer state listener", exception);
            }
            spatializerStateListenerRegistered = false;
        }
    }

    private void onPlaybackConfigurationChanged(
            List<AudioPlaybackConfiguration> configurations) {
        transientMediaDucker.onPlaybackConfigurationsChanged(configurations);
        int signature = 1;
        boolean hasActiveMedia = false;
        for (AudioPlaybackConfiguration configuration : configurations) {
            if (!configuration.isActive()
                    || configuration.getAudioAttributes().getUsage()
                            != AudioAttributes.USAGE_MEDIA) {
                continue;
            }
            hasActiveMedia = true;
            signature = 31 * signature + configuration.getChannelMask();
            signature = 31 * signature + configuration.getSampleRate();
            signature = 31 * signature + (configuration.isSpatialized() ? 1 : 0);
        }
        if (!hasActiveMedia) {
            lastPlaybackFormatSignature = 0;
            return;
        }
        if (signature == lastPlaybackFormatSignature) return;
        lastPlaybackFormatSignature = signature;
        // Native Spatializer::updateActiveTracks() now activates/deactivates its pose controller
        // for both stereo and immersive tracks. Do not force sensor initialization for every
        // player-format callback; doing so churns sensor/mode state while the output rebuild from
        // a user immersive-sound toggle is still converging.
        applyActiveHeadTracking("media format/spatialization changed", false);
        schedulePipelineRestore("media format/spatialization changed");
    }

    private void cancelPipelineRestore() {
        ++pipelineRestoreGeneration;
        pipelineRestorePending = false;
        pipelineRestoreExhausted = false;
    }

    private void schedulePipelineRestore(String reason) {
        AudioDeviceProfile profile = activeProfile;
        OutputDeviceManager.ActiveOutput output = activeOutput;
        if (pipelineRestorePending) {
            if ((spatializer != null && !spatializer.isEnabled())
                    || (effectiveHeadTrackingMode(profile)
                                    != AudioDeviceProfile.HEAD_TRACKING_DISABLED
                            && effectController != null
                            && !effectController.isHeadTrackerAvailable())) {
                pipelineRestoreReady = false;
            }
            return;
        }
        // A failed apply can itself generate more state callbacks. Only a new
        // codec/device generation or an explicit user edit may restart a failed batch.
        if (pipelineRestoreExhausted) return;
        if (output == null || profile == null
                || !profile.spatialEnabled
                || AudioEffectController.IMPLEMENTATION_NONE.equals(profile.implementation)) {
            return;
        }
        pipelineRestorePending = true;
        pipelineRestoreReady = false;
        final int generation = ++pipelineRestoreGeneration;
        final String deviceKey = output.key;
        // Codec notification precedes AudioPolicy/HAL convergence. Each attempt checks
        // the real route; callbacks caused by our own apply cannot extend this batch.
        long[] delaysMs = {300, 1_000, 2_500, 5_000};
        for (int attempt = 0; attempt < delaysMs.length; ++attempt) {
            final boolean lastAttempt = attempt == delaysMs.length - 1;
            mainHandler.postDelayed(() -> executeOnWorker(() -> {
                if (generation != pipelineRestoreGeneration) return;
                if (pipelineRestoreReady) {
                    // Keep the batch armed until its deadline to absorb callbacks from
                    // our successful apply, without repeatedly rewriting native state.
                    if (lastAttempt) pipelineRestorePending = false;
                    return;
                }
                AudioDeviceProfile desired = activeProfile;
                OutputDeviceManager.ActiveOutput current = outputDeviceManager.current();
                if (desired == null || !desired.spatialEnabled
                        || AudioEffectController.IMPLEMENTATION_NONE.equals(desired.implementation)
                        || !TextUtils.equals(deviceKey, desired.deviceKey)
                        || !TextUtils.equals(deviceKey, current.key)) {
                    cancelPipelineRestore();
                    return;
                }
                boolean restored = false;
                try {
                    // Do not tear down Dolby/MiSound while the output is temporarily
                    // unavailable. Restore the device flag first, then check routability.
                    if (effectController.ensureSpatialDeviceEnabled(current, desired)
                            && spatializer.isAvailable()) {
                        restored = applyProfileOnWorker(desired);
                        if (restored) {
                            applyActiveHeadTracking("pipeline recovery: " + reason, true);
                            restored = effectiveHeadTrackingMode(desired)
                                            == AudioDeviceProfile.HEAD_TRACKING_DISABLED
                                    || effectController.isHeadTrackerAvailable();
                        }
                    }
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Audio pipeline recovery waiting after " + reason, exception);
                }
                if (restored) {
                    pipelineRestoreReady = true;
                    Log.i(TAG, "Audio pipeline restored after " + reason);
                    notifyStateChanged();
                }
                if (lastAttempt) {
                    pipelineRestorePending = false;
                    pipelineRestoreExhausted = !restored;
                    Log.i(TAG, "Audio pipeline recovery after " + reason + ": "
                            + (restored ? "ready" : "still unavailable; saved profile retained"));
                    notifyStateChanged();
                }
            }), delaysMs[attempt]);
        }
    }

    private void applyActiveHeadTracking(String reason, boolean forceNativeRestore) {
        AudioDeviceProfile profile = activeProfile;
        OutputDeviceManager.ActiveOutput output = activeOutput;
        int requestedMode = effectiveHeadTrackingMode(profile);
        if (effectController == null || output == null
                || requestedMode == AudioDeviceProfile.HEAD_TRACKING_DISABLED) {
            return;
        }
        AudioOutputIdentity identity = new AudioOutputIdentity(output);
        boolean restored = forceNativeRestore
                ? effectController.restoreHeadTracking(identity, requestedMode)
                : effectController.initializeHeadTracking(identity, requestedMode);
        Log.i(TAG, "Head-tracking restore after " + reason + ": "
                + (restored ? "applied" : "waiting for tracker"));
        if (restored && effectController.isHeadTrackerAvailable()) {
            // A successful output callback supersedes any delayed discovery retries scheduled by
            // the route callback. Leaving those armed caused another forced mode write shortly
            // after an immersive-sound toggle had already settled.
            headTrackerInitGeneration.incrementAndGet();
        } else if (!restored) {
            scheduleHeadTrackerInitialization(output);
        }
    }

    private void onActiveOutputChanged(OutputDeviceManager.ActiveOutput output) {
        OutputDeviceManager.ActiveOutput previous = activeOutput;
        boolean changed = previous == null || !TextUtils.equals(previous.key, output.key);
        activeOutput = output;
        transientMediaDucker.onActiveOutputChanged(output.key);
        if (changed) {
            cancelPipelineRestore();
            activeProfile = profileStore.load(output.key, output.displayName);
        }
        // Re-evaluate the provider even when the route key is unchanged. Companion-visible UUID
        // and name metadata can arrive just after A2DP routing, so the first query may find none.
        if (headPoseProviders != null) {
            headPoseProviders.onActiveOutputChanged(output);
            headPoseProviders.setTrackingRequested(
                    isHeadTrackingRequested(activeProfile));
        }
        if (isHeadTrackingRequested(activeProfile)
                && (changed || effectController == null
                        || !effectController.isHeadTrackerAvailable())) {
            scheduleHeadTrackerInitialization(output);
        } else if (!isHeadTrackingRequested(activeProfile)) {
            headTrackerInitGeneration.incrementAndGet();
        }
        if (!changed) {
            schedulePipelineRestore("same-device route updated");
            return;
        }
        applyActiveOnWorker();
        schedulePipelineRestore("active output changed");
    }

    private void scheduleHeadTrackerInitialization(OutputDeviceManager.ActiveOutput output) {
        final int generation = headTrackerInitGeneration.incrementAndGet();
        final AudioOutputIdentity identity = new AudioOutputIdentity(output);
        // Dynamic sensor registration and AudioService routing run on separate processes. Retry
        // only this cheap metadata operation while they converge; pose samples never cross Binder.
        long[] delaysMs = {500, 1_500, 3_000, 6_000};
        for (long delayMs : delaysMs) {
            mainHandler.postDelayed(() -> {
                if (generation != headTrackerInitGeneration.get() || worker.isShutdown()) return;
                executeOnWorker(() -> {
                    OutputDeviceManager.ActiveOutput current = activeOutput;
                    if (generation != headTrackerInitGeneration.get() || current == null
                            || !TextUtils.equals(current.key, identity.deviceKey)) {
                        return;
                    }
                    AudioDeviceProfile profile = activeProfile;
                    int requestedMode = effectiveHeadTrackingMode(profile);
                    if (requestedMode != AudioDeviceProfile.HEAD_TRACKING_DISABLED) {
                        boolean initialized = effectController.restoreHeadTracking(
                                identity, requestedMode);
                        if (initialized && effectController.isHeadTrackerAvailable()) {
                            // Cancel the remaining delayed probes once framework sensor discovery
                            // and the per-device association have both converged.
                            headTrackerInitGeneration.incrementAndGet();
                        }
                    }
                });
            }, delayMs);
        }
    }

    private void reconcileActiveOutput() {
        if (outputDeviceManager == null) return;
        executeOnWorker(() -> onActiveOutputChanged(outputDeviceManager.current()));
    }

    private void scheduleApplyActive() {
        executeOnWorker(() -> {
            if (outputDeviceManager != null) {
                OutputDeviceManager.ActiveOutput current = outputDeviceManager.current();
                OutputDeviceManager.ActiveOutput previous = activeOutput;
                if (previous == null || !TextUtils.equals(previous.key, current.key)) {
                    onActiveOutputChanged(current);
                    return;
                }
                // This also retries provider selection after Bluetooth identity data arrived.
                if (headPoseProviders != null) {
                    headPoseProviders.onActiveOutputChanged(current);
                    headPoseProviders.setTrackingRequested(
                            isHeadTrackingRequested(activeProfile));
                }
            }
            OutputDeviceManager.ActiveOutput output = activeOutput;
            if (output != null) {
                activeProfile = profileStore.load(output.key, output.displayName);
                applyActiveOnWorker();
                schedulePipelineRestore("saved profile reapplied");
            }
        });
    }

    private void applyActiveOnWorker() {
        AudioDeviceProfile profile = activeProfile;
        if (profile == null) return;
        applyProfileOnWorker(profile);
        notifyStateChanged();
    }

    private boolean applyProfileOnWorker(AudioDeviceProfile profile) {
        OutputDeviceManager.ActiveOutput output = activeOutput;
        if (headPoseProviders != null) {
            headPoseProviders.setTrackingRequested(isHeadTrackingRequested(profile));
        }
        AudioDeviceProfile runtimeProfile = new AudioDeviceProfile(profile);
        int effectiveMode = effectiveHeadTrackingMode(profile);
        runtimeProfile.headTrackingMode = effectiveMode;
        if (!effectController.ensureSpatialDeviceEnabled(output, runtimeProfile)) return false;
        boolean applied = effectController.apply(runtimeProfile);
        if (applied && output != null
                && effectiveMode != AudioDeviceProfile.HEAD_TRACKING_DISABLED) {
            // Associate the per-device tracker with the requested state. If enabling Spatializer
            // creates a new native output, its output callback performs the single forced native
            // re-assertion after that output is ready.
            effectController.initializeHeadTracking(
                    new AudioOutputIdentity(output), effectiveMode);
        }
        return applied;
    }

    private void executeOnWorker(Runnable task) {
        if (worker.isShutdown()) return;
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // An in-flight framework callback may race with onDestroy() after its listener has
            // been unregistered. Service teardown already discarded the requested work.
        }
    }

    private void discoverHeadphoneProviders() {
        PackageManager packageManager = getPackageManager();
        Intent query = new Intent(ACTION_HEADPHONE_POSE_PROVIDER);
        int flags = PackageManager.MATCH_SYSTEM_ONLY
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        List<ResolveInfo> resolved = new ArrayList<>(
                packageManager.queryIntentServices(query, flags));
        resolved.sort((left, right) -> {
            ServiceInfo leftInfo = left.serviceInfo;
            ServiceInfo rightInfo = right.serviceInfo;
            String leftName = leftInfo == null ? ""
                    : new ComponentName(leftInfo.packageName, leftInfo.name)
                            .flattenToShortString();
            String rightName = rightInfo == null ? ""
                    : new ComponentName(rightInfo.packageName, rightInfo.name)
                            .flattenToShortString();
            return leftName.compareTo(rightName);
        });
        int registered = 0;
        for (ResolveInfo candidate : resolved) {
            ServiceInfo info = candidate.serviceInfo;
            if (info == null || !BIND_HEADPHONE_PROVIDER_PERMISSION.equals(
                    info.permission)
                    || packageManager.checkSignatures(getPackageName(), info.packageName)
                            != PackageManager.SIGNATURE_MATCH) {
                continue;
            }
            ComponentName component = new ComponentName(info.packageName, info.name);
            headPoseProviders.register(new RemoteHeadPoseProvider(this, component, headPoseSink,
                    () -> executeOnWorker(() -> {
                        HeadPoseProviderRegistry providers = headPoseProviders;
                        if (providers != null) providers.onProvidersChanged();
                    })));
            registered++;
        }
        if (registered == 0) {
            Log.w(TAG, "No signature-trusted headphone pose provider is installed");
        } else {
            Log.i(TAG, "Registered " + registered + " headphone pose provider(s)");
        }
    }

    private static boolean isHeadTrackingRequested(AudioDeviceProfile profile) {
        return profile != null && profile.spatialEnabled
                && !AudioDeviceProfile.IMPLEMENTATION_NONE.equals(profile.implementation)
                && profile.headTrackingMode
                        != AudioDeviceProfile.HEAD_TRACKING_DISABLED;
    }

    private int effectiveHeadTrackingMode(AudioDeviceProfile profile) {
        if (!isHeadTrackingRequested(profile)
                || (headPoseProviders != null && !headPoseProviders.isWearGateOpen())) {
            return AudioDeviceProfile.HEAD_TRACKING_DISABLED;
        }
        return profile.headTrackingMode;
    }

    private void onEffectiveHeadTrackingPolicyChanged() {
        AudioDeviceProfile profile = activeProfile;
        if (profile == null) return;
        if (!applyProfileOnWorker(profile)) {
            Log.e(TAG, "Could not apply headphone wear-state head-tracking policy");
        }
        int effectiveMode = effectiveHeadTrackingMode(profile);
        if (effectiveMode != AudioDeviceProfile.HEAD_TRACKING_DISABLED) {
            applyActiveHeadTracking("both headphones worn", true);
        } else {
            headTrackerInitGeneration.incrementAndGet();
            Log.i(TAG, "Head tracking suspended because at least one headphone is not worn");
        }
        notifyStateChanged();
    }

    private void saveProfileOnWorker(AudioDeviceProfile requested, boolean applyIfActive) {
        boolean activeRequest = applyIfActive
                && TextUtils.equals(requested.deviceKey, currentDeviceKey());
        if (!activeRequest) {
            try {
                profileStore.save(requested);
                notifyApplyResult(true);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Could not save inactive audio profile", exception);
                notifyApplyResult(false);
            }
            return;
        }

        AudioDeviceProfile previous = activeProfile == null
                ? null : new AudioDeviceProfile(activeProfile);
        // An explicit user edit supersedes all recovery work for the previous profile.
        cancelPipelineRestore();
        headTrackerInitGeneration.incrementAndGet();
        boolean applied = applyProfileOnWorker(requested);
        if (applied) {
            try {
                activeProfile = profileStore.save(requested);
                notifyApplyResult(true);
                return;
            } catch (RuntimeException exception) {
                Log.e(TAG, "Could not persist applied audio profile", exception);
            }
        }

        // Applying is transactional from the settings client's point of view. Never leave the
        // requested profile selected when the vendor effect or Spatializer rejected it.
        activeProfile = previous;
        if (previous != null && !applyProfileOnWorker(previous)) {
            Log.e(TAG, "Could not restore the previous audio profile after apply failure");
        }
        notifyApplyResult(false);
    }

    private void mutateProfile(String requestedDeviceKey, ProfileMutation mutation) {
        executeOnWorker(() -> mutateProfileOnWorker(requestedDeviceKey, mutation));
    }

    private void mutateProfileOnWorker(String requestedDeviceKey, ProfileMutation mutation) {
        String deviceKey = resolveDeviceKey(requestedDeviceKey);
        OutputDeviceManager.ActiveOutput output = activeOutput;
        String displayName = output != null && TextUtils.equals(output.key, deviceKey)
                ? output.displayName : deviceKey;
        AudioDeviceProfile profile = profileStore.load(deviceKey, displayName);
        AudioDeviceProfile previous = new AudioDeviceProfile(profile);
        mutation.apply(profile);
        boolean activeRequest = TextUtils.equals(profile.deviceKey, currentDeviceKey());
        if (activeRequest && !applyProfileOnWorker(profile)) {
            activeProfile = previous;
            applyProfileOnWorker(previous);
            notifyApplyResult(false);
            return;
        }
        try {
            AudioDeviceProfile saved = profileStore.save(profile);
            if (activeRequest) activeProfile = saved;
            notifyApplyResult(true);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not persist personal audio profile", exception);
            if (activeRequest) {
                activeProfile = previous;
                applyProfileOnWorker(previous);
            }
            notifyApplyResult(false);
        }
    }

    private String resolveDeviceKey(String requested) {
        return TextUtils.isEmpty(requested) ? currentDeviceKey() : requested;
    }

    private String currentDeviceKey() {
        OutputDeviceManager.ActiveOutput output = activeOutput;
        return output == null ? OutputDeviceManager.SPEAKER_KEY : output.key;
    }

    private void notifyStateChanged() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }

    private void notifyApplyResult(boolean succeeded) {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_APPLY_SUCCEEDED, succeeded));
    }

    private interface ProfileMutation {
        void apply(AudioDeviceProfile profile);
    }
}
