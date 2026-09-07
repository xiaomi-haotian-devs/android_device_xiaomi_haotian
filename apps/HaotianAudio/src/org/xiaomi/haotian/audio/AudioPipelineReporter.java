/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.Spatializer;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only snapshot of the live framework audio path. Never changes routing or effect state. */
final class AudioPipelineReporter {
    static final class Snapshot {
        final String engine;
        final String input;
        final String output;
        final String spatial;
        final String personal;
        final boolean spatializerEnabled;
        final boolean spatializerAvailable;
        final boolean hasActivePlayback;
        final boolean hasSpatializedPlayback;

        Snapshot(String engine, String input, String output, String spatial, String personal,
                boolean spatializerEnabled, boolean spatializerAvailable,
                boolean hasActivePlayback, boolean hasSpatializedPlayback) {
            this.engine = engine;
            this.input = input;
            this.output = output;
            this.spatial = spatial;
            this.personal = personal;
            this.spatializerEnabled = spatializerEnabled;
            this.spatializerAvailable = spatializerAvailable;
            this.hasActivePlayback = hasActivePlayback;
            this.hasSpatializedPlayback = hasSpatializedPlayback;
        }
    }

    private final Context context;
    private final AudioManager audioManager;
    private final PackageManager packageManager;

    AudioPipelineReporter(Context context) {
        this.context = context;
        audioManager = context.getSystemService(AudioManager.class);
        packageManager = context.getPackageManager();
    }

    Snapshot capture(AudioDeviceProfile profile, boolean effectsAvailable) {
        if (audioManager == null) {
            String unavailable = context.getString(R.string.audio_status_unavailable);
            return new Snapshot(unavailable, unavailable, unavailable, unavailable, unavailable,
                    false, false, false, false);
        }
        List<AudioPlaybackConfiguration> active = activePlayers();
        Spatializer spatializer = audioManager.getSpatializer();
        boolean spatializerSupported = spatializer != null
                && spatializer.getImmersiveAudioLevel()
                        != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
        boolean spatializerEnabled = spatializerSupported && spatializer.isEnabled();
        boolean spatializerAvailable = spatializerSupported && spatializer.isAvailable();
        boolean hasSpatializedPlayback = false;
        for (AudioPlaybackConfiguration configuration : active) {
            if (configuration.isSpatialized()) {
                hasSpatializedPlayback = true;
                break;
            }
        }
        return new Snapshot(engineSummary(profile, effectsAvailable), inputSummary(active),
                outputSummary(profile, active), spatialSummary(), personalSummary(profile),
                spatializerEnabled, spatializerAvailable, !active.isEmpty(),
                hasSpatializedPlayback);
    }

    private List<AudioPlaybackConfiguration> activePlayers() {
        List<AudioPlaybackConfiguration> result = new ArrayList<>();
        try {
            for (AudioPlaybackConfiguration configuration
                    : audioManager.getActivePlaybackConfigurations()) {
                if (configuration.getPlayerState()
                        == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                    result.add(configuration);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return result;
    }

    private String engineSummary(AudioDeviceProfile profile, boolean available) {
        if (profile == null) return context.getString(R.string.status_loading);
        String implementation;
        if (AudioDeviceProfile.IMPLEMENTATION_DOLBY.equals(profile.implementation)) {
            implementation = context.getString(R.string.effect_dolby);
        } else if (AudioDeviceProfile.IMPLEMENTATION_MISOUND.equals(profile.implementation)) {
            implementation = context.getString(R.string.effect_misound);
        } else {
            implementation = context.getString(R.string.effect_original);
        }
        if (AudioDeviceProfile.IMPLEMENTATION_NONE.equals(profile.implementation)) {
            return context.getString(R.string.engine_status_format, implementation,
                    context.getString(R.string.status_bypassed));
        }
        return context.getString(R.string.engine_status_format, implementation,
                available ? context.getString(R.string.status_running)
                        : context.getString(R.string.status_unavailable_short));
    }

    private String inputSummary(List<AudioPlaybackConfiguration> active) {
        if (active.isEmpty()) return context.getString(R.string.no_active_audio_tracks);
        StringBuilder builder = new StringBuilder(context.getResources().getQuantityString(
                R.plurals.active_audio_tracks, active.size(), active.size()));
        int shown = 0;
        for (AudioPlaybackConfiguration configuration : active) {
            if (shown++ == 2) {
                builder.append(context.getString(R.string.more_audio_tracks,
                        active.size() - 2));
                break;
            }
            AudioAttributes attributes = configuration.getAudioAttributes();
            int channelCount = channelCount(configuration.getChannelMask());
            builder.append('\n').append(appName(configuration.getClientUid()))
                    .append(" · ").append(usageName(attributes.getUsage()))
                    .append(" · ").append(sampleRate(configuration.getSampleRate()))
                    .append(" · ").append(channelName(channelCount))
                    .append(" · ").append(context.getString(R.string.audio_session,
                            configuration.getSessionId()));
            if (configuration.isSpatialized()) {
                builder.append(" · ").append(context.getString(R.string.track_spatialized));
            }
            try {
                if (configuration.isMuted()) {
                    builder.append(" · ").append(context.getString(R.string.track_muted));
                }
            } catch (RuntimeException ignored) {
            }
        }
        return builder.toString();
    }

    private String outputSummary(AudioDeviceProfile profile,
            List<AudioPlaybackConfiguration> active) {
        AudioDeviceInfo device = firstRoutedDevice(active);
        String route = profile != null ? profile.displayName : "";
        if (device != null && !TextUtils.isEmpty(device.getProductName())) {
            route = device.getProductName().toString();
        }
        if (TextUtils.isEmpty(route)) route = context.getString(R.string.unknown_audio_output);

        String mixRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        String frames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
        int latency = 0;
        try {
            latency = audioManager.getOutputLatency(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException ignored) {
        }
        StringBuilder builder = new StringBuilder(route)
                .append('\n').append(context.getString(R.string.output_mixer_format,
                        numericRate(mixRate), nonEmpty(frames), latency));
        if (device != null) {
            builder.append('\n').append(context.getString(R.string.output_capabilities,
                    rates(device.getSampleRates()), channelCounts(device.getChannelCounts()),
                    encodings(device.getEncodings())));
        }
        return builder.toString();
    }

    private String spatialSummary() {
        Spatializer spatializer = audioManager.getSpatializer();
        if (spatializer == null
                || spatializer.getImmersiveAudioLevel()
                        == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
            return context.getString(R.string.spatializer_not_supported);
        }
        String enabled = spatializer.isEnabled()
                ? context.getString(R.string.status_on) : context.getString(R.string.status_off);
        String available = spatializer.isAvailable()
                ? context.getString(R.string.status_available_short)
                : context.getString(R.string.status_unavailable_short);
        String actual = headTrackingMode(spatializer.getHeadTrackingMode());
        String desired = headTrackingMode(spatializer.getDesiredHeadTrackingMode());
        return context.getString(R.string.spatial_status_format, enabled, available, actual,
                desired, spatializer.isHeadTrackerAvailable()
                        ? context.getString(R.string.status_yes)
                        : context.getString(R.string.status_no));
    }

    private String personalSummary(AudioDeviceProfile profile) {
        if (profile == null) return context.getString(R.string.status_loading);
        boolean miSoundActive = AudioDeviceProfile.IMPLEMENTATION_MISOUND.equals(
                profile.implementation);
        String model = !TextUtils.isEmpty(profile.headsetModelName)
                ? profile.headsetModelName : profile.headsetModelId > 0
                        ? context.getString(R.string.headset_model_id, profile.headsetModelId)
                        : context.getString(R.string.headset_none);
        return context.getString(R.string.personal_status_format, model,
                personalState(profile.soundIdEnabled, miSoundActive),
                personalState(profile.hearingProfileEnabled, miSoundActive),
                personalState(profile.earScanEnabled, miSoundActive));
    }

    private AudioDeviceInfo firstRoutedDevice(List<AudioPlaybackConfiguration> active) {
        for (AudioPlaybackConfiguration configuration : active) {
            try {
                List<AudioDeviceInfo> devices = configuration.getAudioDeviceInfos();
                if (!devices.isEmpty()) return devices.get(0);
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private String appName(int uid) {
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return context.getString(R.string.unknown_audio_source);
        }
        try {
            CharSequence label = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packages[0], 0));
            return TextUtils.isEmpty(label) ? packages[0] : label.toString();
        } catch (PackageManager.NameNotFoundException exception) {
            return packages[0];
        }
    }

    private String usageName(int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_MEDIA:
                return context.getString(R.string.usage_media);
            case AudioAttributes.USAGE_GAME:
                return context.getString(R.string.usage_game);
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
            case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING:
                return context.getString(R.string.usage_call);
            case AudioAttributes.USAGE_ALARM:
                return context.getString(R.string.usage_alarm);
            case AudioAttributes.USAGE_NOTIFICATION:
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
                return context.getString(R.string.usage_notification);
            default:
                return AudioAttributes.usageToString(usage);
        }
    }

    private String headTrackingMode(int mode) {
        switch (mode) {
            case Spatializer.HEAD_TRACKING_MODE_RELATIVE_WORLD:
                return context.getString(R.string.head_tracking_world);
            case Spatializer.HEAD_TRACKING_MODE_RELATIVE_DEVICE:
                return context.getString(R.string.head_tracking_device);
            case Spatializer.HEAD_TRACKING_MODE_DISABLED:
                return context.getString(R.string.status_off);
            case Spatializer.HEAD_TRACKING_MODE_UNSUPPORTED:
                return context.getString(R.string.status_unsupported);
            default:
                return Integer.toString(mode);
        }
    }

    private String personalState(boolean enabled, boolean miSoundActive) {
        if (!enabled) return context.getString(R.string.status_off);
        return miSoundActive ? context.getString(R.string.status_on)
                : context.getString(R.string.status_saved_not_active);
    }

    private String sampleRate(int rate) {
        if (rate <= 0) return context.getString(R.string.audio_rate_unknown);
        if (rate % 1000 == 0) return String.format(Locale.ROOT, "%d kHz", rate / 1000);
        return String.format(Locale.ROOT, "%.1f kHz", rate / 1000f);
    }

    private String numericRate(String value) {
        try {
            return sampleRate(Integer.parseInt(value));
        } catch (RuntimeException exception) {
            return context.getString(R.string.audio_rate_unknown);
        }
    }

    private String rates(int[] values) {
        if (values == null || values.length == 0) return context.getString(R.string.status_dynamic);
        StringBuilder builder = new StringBuilder();
        for (int value : values) {
            if (builder.length() > 0) builder.append('/');
            builder.append(sampleRate(value).replace(" kHz", "k"));
        }
        return builder.toString();
    }

    private String channelCounts(int[] values) {
        if (values == null || values.length == 0) return context.getString(R.string.status_dynamic);
        StringBuilder builder = new StringBuilder();
        for (int value : values) {
            if (builder.length() > 0) builder.append('/');
            builder.append(value).append("ch");
        }
        return builder.toString();
    }

    private String encodings(int[] values) {
        if (values == null || values.length == 0) return context.getString(R.string.status_dynamic);
        StringBuilder builder = new StringBuilder();
        for (int value : values) {
            if (builder.length() > 0) builder.append('/');
            builder.append(AudioFormat.toLogFriendlyEncoding(value));
        }
        return builder.toString();
    }

    private String channelName(int count) {
        if (count == 1) return context.getString(R.string.channel_mono);
        if (count == 2) return context.getString(R.string.channel_stereo);
        if (count > 0) return context.getString(R.string.channel_count, count);
        return context.getString(R.string.channel_unknown);
    }

    private static int channelCount(int mask) {
        try {
            return mask == 0 ? 0 : AudioFormat.channelCountFromOutChannelMask(mask);
        } catch (IllegalArgumentException exception) {
            return 0;
        }
    }

    private static String nonEmpty(String value) {
        return TextUtils.isEmpty(value) ? "?" : value;
    }
}
