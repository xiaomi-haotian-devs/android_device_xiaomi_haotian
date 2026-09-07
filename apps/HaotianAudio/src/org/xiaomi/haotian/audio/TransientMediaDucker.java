/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.media.AudioAttributes;
import android.media.AudioPlaybackConfiguration;
import android.media.PlayerProxy;
import android.media.VolumeShaper;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Applies route-scoped, reversible media attenuation without changing a stream-volume index.
 *
 * <p>This deliberately uses the same per-player {@link VolumeShaper} mechanism as framework audio
 * focus ducking. A fixed system-reserved shaper id keeps the policy independent from player-owned
 * shapers, while owner Binder tokens make every request self-cleaning when its client dies.</p>
 *
 * <p>All methods are called on {@link HaotianAudioService}'s single worker thread.</p>
 */
final class TransientMediaDucker {
    private static final String TAG = "HaotianMediaDucker";

    // Framework ids 1-4 are assigned to focus ducking, fade-out, connection mute and strong duck
    // in this platform branch. Keep Conversation Awareness in the next system-reserved slot so it
    // neither collides with those policies nor consumes a player's application-shaper quota.
    private static final int VOLUME_SHAPER_CONVERSATION_DUCK_ID = 5;
    private static final long RAMP_DURATION_MS = 500;
    private static final float UNITY_VOLUME = 1.0f;

    private static final VolumeShaper.Configuration SHAPER_HANDLE =
            new VolumeShaper.Configuration(VOLUME_SHAPER_CONVERSATION_DUCK_ID);
    private static final VolumeShaper.Operation PLAY_CREATE_IF_NEEDED =
            new VolumeShaper.Operation.Builder(VolumeShaper.Operation.PLAY)
                    .createIfNeeded()
                    .build();
    private static final VolumeShaper.Operation PLAY_CREATE_IF_NEEDED_AT_END =
            new VolumeShaper.Operation.Builder(PLAY_CREATE_IF_NEEDED)
                    .setXOffset(1.0f)
                    .build();

    private final Executor workerExecutor;
    private final ArrayMap<IBinder, DuckRequest> requests = new ArrayMap<>();
    private final SparseArray<AudioPlaybackConfiguration> duckedPlayers = new SparseArray<>();
    private final SparseArray<Boolean> playersWithOurShaper = new SparseArray<>();

    private List<AudioPlaybackConfiguration> playbackConfigurations = Collections.emptyList();
    private String activeDeviceKey = "";
    private float appliedVolume = UNITY_VOLUME;
    private boolean closed;

    TransientMediaDucker(Executor workerExecutor) {
        this.workerExecutor = workerExecutor;
    }

    void request(IBinder ownerToken, String deviceKey, float volume, int callerUid) {
        if (closed) return;
        DuckRequest request = requests.get(ownerToken);
        if (request == null) {
            IBinder.DeathRecipient deathRecipient =
                    () -> workerExecutor.execute(() -> release(ownerToken, true));
            try {
                ownerToken.linkToDeath(deathRecipient, 0);
            } catch (RemoteException exception) {
                Log.w(TAG, "Ignoring duck request from dead uid " + callerUid);
                return;
            }
            request = new DuckRequest(deathRecipient, callerUid);
            requests.put(ownerToken, request);
        }
        request.deviceKey = deviceKey;
        request.volume = volume;
        reconcile("request from uid " + callerUid);
    }

    void release(IBinder ownerToken) {
        release(ownerToken, false);
    }

    void clearAll(String reason) {
        if (requests.isEmpty()) return;
        for (int index = requests.size() - 1; index >= 0; index--) {
            IBinder token = requests.keyAt(index);
            DuckRequest request = requests.valueAt(index);
            unlinkDeathRecipient(token, request.deathRecipient);
        }
        requests.clear();
        reconcile(reason);
    }

    void onActiveOutputChanged(String deviceKey) {
        String resolved = deviceKey == null ? "" : deviceKey;
        if (TextUtils.equals(activeDeviceKey, resolved)) return;
        activeDeviceKey = resolved;
        reconcile("media output changed");
    }

    void onPlaybackConfigurationsChanged(
            List<AudioPlaybackConfiguration> configurations) {
        playbackConfigurations = configurations == null
                ? Collections.emptyList() : configurations;
        forgetReleasedPlayers();
        reconcile("playback changed");
    }

    void onAudioServerDown() {
        // Native players and their shapers no longer exist. Retain requests so fresh players are
        // attenuated immediately when AudioFlinger and playback clients reconnect.
        duckedPlayers.clear();
        playersWithOurShaper.clear();
    }

    void close() {
        if (closed) return;
        clearAll("service stopped");
        restoreAllPlayers();
        playbackConfigurations = Collections.emptyList();
        playersWithOurShaper.clear();
        closed = true;
    }

    private void release(IBinder ownerToken, boolean ownerDied) {
        DuckRequest request = requests.remove(ownerToken);
        if (request == null) return;
        if (!ownerDied) unlinkDeathRecipient(ownerToken, request.deathRecipient);
        reconcile(ownerDied
                ? "owner uid " + request.callerUid + " died"
                : "owner uid " + request.callerUid + " released");
    }

    private void reconcile(String reason) {
        float requestedVolume = requestedVolumeForActiveOutput();
        SparseArray<AudioPlaybackConfiguration> eligiblePlayers = eligiblePlayers();

        for (int index = duckedPlayers.size() - 1; index >= 0; index--) {
            int playerId = duckedPlayers.keyAt(index);
            if (eligiblePlayers.indexOfKey(playerId) >= 0 && requestedVolume < UNITY_VOLUME) {
                continue;
            }
            restorePlayer(duckedPlayers.valueAt(index));
            duckedPlayers.removeAt(index);
        }

        if (requestedVolume >= UNITY_VOLUME) {
            if (appliedVolume < UNITY_VOLUME) {
                Log.i(TAG, "Restored transient media duck: " + reason);
            }
            appliedVolume = UNITY_VOLUME;
            return;
        }

        boolean policyWasActive = appliedVolume < UNITY_VOLUME;
        boolean volumeChanged = Float.compare(requestedVolume, appliedVolume) != 0;
        VolumeShaper.Configuration configuration = createConfiguration(requestedVolume);

        if (volumeChanged) {
            for (int index = duckedPlayers.size() - 1; index >= 0; index--) {
                AudioPlaybackConfiguration player = duckedPlayers.valueAt(index);
                if (!apply(player, configuration, replaceOperation(false))) {
                    playersWithOurShaper.remove(player.getPlayerInterfaceId());
                    duckedPlayers.removeAt(index);
                }
            }
        }

        for (int index = 0; index < eligiblePlayers.size(); index++) {
            int playerId = eligiblePlayers.keyAt(index);
            if (duckedPlayers.indexOfKey(playerId) >= 0) continue;
            AudioPlaybackConfiguration player = eligiblePlayers.valueAt(index);
            boolean knownShaper = playersWithOurShaper.indexOfKey(playerId) >= 0;
            VolumeShaper.Operation operation = knownShaper
                    ? replaceOperation(policyWasActive)
                    : policyWasActive ? PLAY_CREATE_IF_NEEDED_AT_END : PLAY_CREATE_IF_NEEDED;
            if (apply(player, configuration, operation)) {
                duckedPlayers.put(playerId, player);
                playersWithOurShaper.put(playerId, Boolean.TRUE);
            }
        }

        if (!policyWasActive || volumeChanged) {
            Log.i(TAG, "Applied transient media duck to "
                    + Math.round(requestedVolume * 100.0f) + "%: " + reason);
        }
        appliedVolume = requestedVolume;
    }

    private float requestedVolumeForActiveOutput() {
        float volume = UNITY_VOLUME;
        for (int index = 0; index < requests.size(); index++) {
            DuckRequest request = requests.valueAt(index);
            if (TextUtils.equals(activeDeviceKey, request.deviceKey)) {
                volume = Math.min(volume, request.volume);
            }
        }
        return volume;
    }

    private SparseArray<AudioPlaybackConfiguration> eligiblePlayers() {
        SparseArray<AudioPlaybackConfiguration> result = new SparseArray<>();
        for (AudioPlaybackConfiguration configuration : playbackConfigurations) {
            if (configuration == null
                    || configuration.getPlayerState()
                            != AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                continue;
            }
            AudioAttributes attributes = configuration.getAudioAttributes();
            if (attributes == null) continue;
            int usage = attributes.getUsage();
            if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME) {
                continue;
            }
            result.put(configuration.getPlayerInterfaceId(), configuration);
        }
        return result;
    }

    private void forgetReleasedPlayers() {
        SparseArray<Boolean> presentPlayers = new SparseArray<>();
        for (AudioPlaybackConfiguration configuration : playbackConfigurations) {
            if (configuration != null) {
                presentPlayers.put(configuration.getPlayerInterfaceId(), Boolean.TRUE);
            }
        }
        for (int index = playersWithOurShaper.size() - 1; index >= 0; index--) {
            if (presentPlayers.indexOfKey(playersWithOurShaper.keyAt(index)) < 0) {
                playersWithOurShaper.removeAt(index);
            }
        }
    }

    private void restoreAllPlayers() {
        for (int index = duckedPlayers.size() - 1; index >= 0; index--) {
            restorePlayer(duckedPlayers.valueAt(index));
        }
        duckedPlayers.clear();
        appliedVolume = UNITY_VOLUME;
    }

    private static boolean apply(AudioPlaybackConfiguration configuration,
            VolumeShaper.Configuration shaper, VolumeShaper.Operation operation) {
        try {
            PlayerProxy proxy = configuration.getPlayerProxy();
            if (proxy == null) return false;
            proxy.applyVolumeShaper(shaper, operation);
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not duck media player "
                    + configuration.getPlayerInterfaceId(), exception);
            return false;
        }
    }

    private static void restorePlayer(AudioPlaybackConfiguration configuration) {
        try {
            PlayerProxy proxy = configuration.getPlayerProxy();
            if (proxy != null) {
                proxy.applyVolumeShaper(SHAPER_HANDLE, VolumeShaper.Operation.REVERSE);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not restore media player "
                    + configuration.getPlayerInterfaceId(), exception);
        }
    }

    private static VolumeShaper.Configuration createConfiguration(float volume) {
        return new VolumeShaper.Configuration.Builder()
                .setId(VOLUME_SHAPER_CONVERSATION_DUCK_ID)
                .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_CUBIC_MONOTONIC)
                .setCurve(new float[] {0.0f, 1.0f}, new float[] {UNITY_VOLUME, volume})
                .setDuration(RAMP_DURATION_MS)
                .build();
    }

    private static VolumeShaper.Operation replaceOperation(boolean skipRamp) {
        VolumeShaper.Operation.Builder builder =
                new VolumeShaper.Operation.Builder(VolumeShaper.Operation.PLAY)
                        .replace(VOLUME_SHAPER_CONVERSATION_DUCK_ID, true);
        if (skipRamp) builder.setXOffset(1.0f);
        return builder.build();
    }

    private static void unlinkDeathRecipient(
            IBinder ownerToken, IBinder.DeathRecipient deathRecipient) {
        try {
            ownerToken.unlinkToDeath(deathRecipient, 0);
        } catch (RuntimeException ignored) {
            // Binder death may race a normal release; either path has already removed the request.
        }
    }

    private static final class DuckRequest {
        final IBinder.DeathRecipient deathRecipient;
        final int callerUid;
        String deviceKey;
        float volume;

        DuckRequest(IBinder.DeathRecipient deathRecipient, int callerUid) {
            this.deathRecipient = deathRecipient;
            this.callerUid = callerUid;
        }
    }
}
