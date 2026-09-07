/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.text.TextUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Read-only live view of the input path. This class never opens or reroutes a microphone. */
final class MicrophoneStatusReporter {
    private static final UUID OZO_EFFECT_TYPE =
            UUID.fromString("56d6b082-1a83-455a-84a8-9db3a35cf532");
    private static final UUID OZO_EFFECT_IMPLEMENTATION =
            UUID.fromString("7e384a3b-7850-4a64-a097-884250d8a737");
    private static final String OZO_NOTIFY_SERVICE =
            "com.android.ozoaudio.notify.IOzoNotify/default";

    static final class Snapshot {
        final String capture;
        final String format;
        final String device;
        final String ozo;
        final String route;
        final boolean recording;
        final boolean ozoReady;
        final int deviceChannels;

        Snapshot(String capture, String format, String device, String ozo, String route,
                boolean recording, boolean ozoReady, int deviceChannels) {
            this.capture = capture;
            this.format = format;
            this.device = device;
            this.ozo = ozo;
            this.route = route;
            this.recording = recording;
            this.ozoReady = ozoReady;
            this.deviceChannels = deviceChannels;
        }
    }

    private final Context context;
    private final AudioManager audioManager;

    MicrophoneStatusReporter(Context context) {
        this.context = context;
        audioManager = context.getSystemService(AudioManager.class);
    }

    Snapshot capture() {
        List<AudioRecordingConfiguration> recordings = activeRecordings();
        AudioRecordingConfiguration primary = preferredRecording(recordings);
        boolean effectRegistered = isOzoEffectRegistered();
        boolean serviceOnline = isOzoServiceOnline();
        boolean licenseVisible = new File("/odm/etc/ozosdk.license").isFile();
        boolean vendorEnabled = SystemProperties.getBoolean(
                "ro.vendor.audio.zoom.directionalrecord.support", false);
        // The stock notify service is an AIDL lazy service and is normally stopped while idle.
        // Its non-running state therefore is status information, not an unavailable capability.
        boolean ozoReady = effectRegistered && licenseVisible && vendorEnabled;

        String ozo = context.getString(R.string.microphone_ozo_status_format,
                yesNo(effectRegistered), yesNo(serviceOnline), yesNo(licenseVisible),
                yesNo(vendorEnabled));
        if (primary == null) {
            return new Snapshot(
                    context.getString(R.string.microphone_capture_inactive),
                    context.getString(R.string.microphone_input_format_inactive),
                    context.getString(R.string.microphone_active_device_none), ozo,
                    context.getString(R.string.microphone_route_inactive), false, ozoReady, 0);
        }

        AudioFormat deviceFormat = primary.getFormat();
        AudioFormat clientFormat = primary.getClientFormat();
        int deviceChannels = deviceFormat != null ? deviceFormat.getChannelCount() : 0;
        int clientChannels = clientFormat != null ? clientFormat.getChannelCount() : 0;
        boolean ozoActive = containsEffect(primary.getEffects(), OZO_EFFECT_TYPE,
                OZO_EFFECT_IMPLEMENTATION)
                || containsEffect(primary.getClientEffects(), OZO_EFFECT_TYPE,
                        OZO_EFFECT_IMPLEMENTATION);

        String capture = context.getString(R.string.microphone_capture_active_format,
                sourceName(primary.getClientAudioSource()),
                TextUtils.isEmpty(primary.getClientPackageName())
                        ? context.getString(R.string.microphone_unknown_application)
                        : primary.getClientPackageName(),
                primary.getClientAudioSessionId(),
                primary.isClientSilenced()
                        ? context.getString(R.string.microphone_capture_silenced)
                        : context.getString(R.string.microphone_capture_audible));
        String format = context.getString(R.string.microphone_input_format_active_format,
                formatName(clientFormat), formatName(deviceFormat));
        String device = deviceName(primary.getAudioDevice());
        String route = context.getString(
                deviceChannels >= 4 ? R.string.microphone_route_four_channel
                        : R.string.microphone_route_channel_format,
                deviceChannels > 0 ? deviceChannels : clientChannels,
                ozoActive ? context.getString(R.string.microphone_ozo_active)
                        : context.getString(R.string.microphone_ozo_not_active));
        return new Snapshot(capture, format, device, ozo, route, true, ozoReady,
                deviceChannels);
    }

    private List<AudioRecordingConfiguration> activeRecordings() {
        if (audioManager == null) return Collections.emptyList();
        try {
            return audioManager.getActiveRecordingConfigurations();
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private static AudioRecordingConfiguration preferredRecording(
            List<AudioRecordingConfiguration> recordings) {
        if (recordings.isEmpty()) return null;
        for (AudioRecordingConfiguration recording : recordings) {
            if (recording.getClientAudioSource() == MediaRecorder.AudioSource.CAMCORDER) {
                return recording;
            }
        }
        return recordings.get(0);
    }

    private static boolean isOzoEffectRegistered() {
        try {
            AudioEffect.Descriptor[] effects = AudioEffect.queryEffects();
            if (effects == null) return false;
            for (AudioEffect.Descriptor effect : effects) {
                if (OZO_EFFECT_TYPE.equals(effect.type)
                        || OZO_EFFECT_IMPLEMENTATION.equals(effect.uuid)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static boolean isOzoServiceOnline() {
        try {
            IBinder binder = ServiceManager.checkService(OZO_NOTIFY_SERVICE);
            return binder != null && binder.isBinderAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean containsEffect(List<AudioEffect.Descriptor> effects, UUID type,
            UUID implementation) {
        for (AudioEffect.Descriptor effect : effects) {
            if (type.equals(effect.type) || implementation.equals(effect.uuid)) return true;
        }
        return false;
    }

    private String yesNo(boolean value) {
        return context.getString(value ? R.string.status_yes : R.string.status_no);
    }

    private String sourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC:
                return context.getString(R.string.microphone_source_mic);
            case MediaRecorder.AudioSource.CAMCORDER:
                return context.getString(R.string.microphone_source_camcorder);
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return context.getString(R.string.microphone_source_voice_recognition);
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                return context.getString(R.string.microphone_source_voice_communication);
            case MediaRecorder.AudioSource.UNPROCESSED:
                return context.getString(R.string.microphone_source_unprocessed);
            default:
                return context.getString(R.string.microphone_source_number, source);
        }
    }

    private static String formatName(AudioFormat format) {
        if (format == null) return "—";
        return String.format(Locale.ROOT, "%d Hz · %d ch · %s", format.getSampleRate(),
                format.getChannelCount(), AudioFormat.toLogFriendlyEncoding(format.getEncoding()));
    }

    private String deviceName(AudioDeviceInfo device) {
        if (device == null) return context.getString(R.string.microphone_active_device_unknown);
        String product = device.getProductName() != null
                ? device.getProductName().toString() : "";
        String address = device.getAddress();
        if (TextUtils.isEmpty(product)) product = context.getString(R.string.microphone_builtin_mic);
        if (TextUtils.isEmpty(address)) {
            return context.getString(R.string.microphone_device_format, product, device.getType());
        }
        return context.getString(R.string.microphone_device_address_format,
                product, device.getType(), address);
    }
}
