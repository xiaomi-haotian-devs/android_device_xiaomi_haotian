/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;
import android.media.Spatializer;
import android.os.SystemClock;
import android.os.SystemProperties;

import java.util.List;
import java.util.Locale;

/** Read-only latency instrumentation used only while the head-tracking debug page is visible. */
final class HeadTrackingLatencyReporter implements BluetoothProfile.ServiceListener {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long CODEC_REFRESH_NANOS = 1_000_000_000L;
    private static final long ENCODER_SETTINGS_MAGIC = 0x48414f5449414e32L; // "HAOTIAN2"
    // These three values exist in the Bluetooth module implementation but are hidden from the
    // system SDK used to compile this platform app. Keep the A2DP bit assignments local instead
    // of widening the Bluetooth API surface solely for a read-only debug page.
    private static final int SAMPLE_RATE_16000 = 1 << 6;
    private static final int SAMPLE_RATE_24000 = 1 << 7;
    private static final int SAMPLE_RATE_32000 = 1 << 8;

    private final Context context;
    private final AudioManager audioManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final Spatializer spatializer;

    private BluetoothA2dp a2dp;
    private boolean poseListenerRegistered;
    private long lastCodecRefreshNanos;
    private String codecSummary = "—";
    private double codecFrameMillis = Double.NaN;
    private boolean started;

    HeadTrackingLatencyReporter(Context context) {
        this.context = context.getApplicationContext();
        audioManager = context.getSystemService(AudioManager.class);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        spatializer = audioManager == null ? null : audioManager.getSpatializer();
    }

    void start() {
        started = true;
        if (spatializer != null && !poseListenerRegistered) {
            try {
                spatializer.setOnHeadToSoundstagePoseUpdatedListener(
                        context.getMainExecutor(), (ignored, pose) ->
                                HeadPoseDebugState.onEffectPoseCallback());
                poseListenerRegistered = true;
            } catch (IllegalStateException | SecurityException ignored) {
                // Another diagnostic client may already own the listener on this instance.
            }
        }
        if (bluetoothAdapter != null && a2dp == null) {
            try {
                bluetoothAdapter.getProfileProxy(context, this, BluetoothProfile.A2DP);
            } catch (RuntimeException ignored) {
            }
        }
    }

    void stop() {
        started = false;
        if (spatializer != null && poseListenerRegistered) {
            try {
                spatializer.clearOnHeadToSoundstagePoseUpdatedListener();
            } catch (IllegalStateException | SecurityException ignored) {
            }
            poseListenerRegistered = false;
        }
        BluetoothA2dp closing = a2dp;
        a2dp = null;
        if (bluetoothAdapter != null && closing != null) {
            try {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, closing);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onServiceConnected(int profile, BluetoothProfile proxy) {
        if (profile == BluetoothProfile.A2DP && proxy instanceof BluetoothA2dp) {
            if (!started) {
                try {
                    bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                } catch (RuntimeException ignored) {
                }
                return;
            }
            a2dp = (BluetoothA2dp) proxy;
            lastCodecRefreshNanos = 0;
        }
    }

    @Override
    public void onServiceDisconnected(int profile) {
        if (profile == BluetoothProfile.A2DP) a2dp = null;
    }

    Snapshot capture(HeadPoseDebugState.Snapshot pose) {
        refreshCodec(pose.captureTimestampNanos, pose.outputKey);

        long decode = delta(pose.decodedTimestampNanos, pose.packetReceivedTimestampNanos);
        long appDispatch = delta(pose.sinkReceivedTimestampNanos,
                pose.decodedTimestampNanos);
        long sharedWrite = delta(pose.publishEndTimestampNanos,
                pose.publishStartTimestampNanos);
        long halAcquire = delta(pose.halReadTimestampNanos,
                pose.halSourcePublishTimestampNanos);
        long halPost = delta(pose.halPostTimestampNanos, pose.halReadTimestampNanos);
        long poseToEffect = delta(pose.effectCallbackTimestampNanos,
                pose.effectSourceTimestampNanos);
        long effectAge = delta(pose.captureTimestampNanos,
                pose.effectCallbackTimestampNanos);

        int outputLatencyMillis = -1;
        int sampleRate = 0;
        int framesPerBuffer = 0;
        if (audioManager != null) {
            try {
                outputLatencyMillis = audioManager.getOutputLatency(AudioManager.STREAM_MUSIC);
            } catch (RuntimeException ignored) {
            }
            sampleRate = parsePositive(audioManager.getProperty(
                    AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            framesPerBuffer = parsePositive(audioManager.getProperty(
                    AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
        }

        double mixerBlockMillis = sampleRate > 0 && framesPerBuffer > 0
                ? framesPerBuffer * 1000.0 / sampleRate : Double.NaN;
        int configuredPredictionMillis = SystemProperties.getInt(
                "audio.spatializer.prediction_duration_ms", -1);
        int predictionMillis = configuredPredictionMillis >= 0
                ? configuredPredictionMillis : 120;
        double estimatedTotalMillis = poseToEffect >= 0 && outputLatencyMillis >= 0
                ? toMillis(poseToEffect) + outputLatencyMillis : Double.NaN;
        double residualMillis = Double.isNaN(estimatedTotalMillis)
                ? Double.NaN : estimatedTotalMillis - predictionMillis;

        return new Snapshot(decode, appDispatch, sharedWrite, halAcquire, halPost,
                poseToEffect, effectAge, outputLatencyMillis, sampleRate, framesPerBuffer,
                mixerBlockMillis, codecSummary, codecFrameMillis, predictionMillis,
                estimatedTotalMillis, residualMillis,
                poseListenerRegistered && pose.effectCallbackTimestampNanos > 0,
                pose.halConsumerSequence, pose.publishedSequence);
    }

    private void refreshCodec(long nowNanos, String outputKey) {
        if (nowNanos - lastCodecRefreshNanos < CODEC_REFRESH_NANOS) return;
        lastCodecRefreshNanos = nowNanos;
        BluetoothA2dp proxy = a2dp;
        if (proxy == null) {
            codecSummary = "—";
            codecFrameMillis = Double.NaN;
            return;
        }
        try {
            BluetoothDevice device = findRoutedDevice(proxy.getConnectedDevices(), outputKey);
            BluetoothCodecStatus status = device == null ? null : proxy.getCodecStatus(device);
            BluetoothCodecConfig config = status == null ? null : status.getCodecConfig();
            if (config == null) {
                codecSummary = "—";
                codecFrameMillis = Double.NaN;
                return;
            }
            int codecType = config.getCodecType();
            int rate = sampleRate(config.getSampleRate());
            codecFrameMillis = codecFrameMillis(codecType, rate, config);
            codecSummary = codecName(codecType)
                    + " · " + (rate > 0 ? formatRate(rate) : "? Hz")
                    + " · " + backend(config);
        } catch (RuntimeException ignored) {
            codecSummary = "—";
            codecFrameMillis = Double.NaN;
        }
    }

    private static BluetoothDevice findRoutedDevice(
            List<BluetoothDevice> connected, String outputKey) {
        if (connected == null || connected.isEmpty()) return null;
        String key = outputKey == null ? "" : outputKey.toLowerCase(Locale.ROOT);
        for (BluetoothDevice device : connected) {
            String address = device.getAddress();
            if (address != null && key.contains(address.toLowerCase(Locale.ROOT))) return device;
        }
        // The head-tracking provider only starts for the routed Classic A2DP peer. If AudioPolicy
        // omitted its address from the stable key, a single connected A2DP device is unambiguous.
        return connected.size() == 1 ? connected.get(0) : null;
    }

    private static String codecName(int codecType) {
        switch (codecType) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC:
                return "SBC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC:
                return "AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX:
                return "aptX";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD:
                return "aptX HD";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC:
                return "LDAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3:
                return "LC3";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS:
                return "Opus";
            default:
                return "codec " + codecType;
        }
    }

    private String backend(BluetoothCodecConfig config) {
        if (config.getCodecSpecific4() != ENCODER_SETTINGS_MAGIC) {
            return context.getString(R.string.head_tracking_backend_auto);
        }
        int backend = (int) (config.getCodecSpecific3() & 0x3L);
        if (backend == 1) {
            return context.getString(R.string.head_tracking_backend_hardware_requested);
        }
        if (backend == 2) {
            return context.getString(R.string.head_tracking_backend_software_requested);
        }
        return context.getString(R.string.head_tracking_backend_auto);
    }

    private static double codecFrameMillis(int codec, int rate, BluetoothCodecConfig config) {
        if (rate <= 0) return Double.NaN;
        if (codec == BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC) {
            return 1024.0 * 1000.0 / rate;
        }
        if (codec == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC
                && config.getCodecSpecific4() == ENCODER_SETTINGS_MAGIC) {
            int blockCode = (int) ((config.getCodecSpecific3() >> 5) & 0x7L);
            int subbandCode = (int) ((config.getCodecSpecific3() >> 8) & 0x3L);
            int blocks = blockCode == 1 ? 4 : blockCode == 2 ? 8
                    : blockCode == 3 ? 12 : 16;
            int subbands = subbandCode == 1 ? 4 : 8;
            return blocks * subbands * 1000.0 / rate;
        }
        return Double.NaN;
    }

    private static int sampleRate(int mask) {
        if ((mask & BluetoothCodecConfig.SAMPLE_RATE_192000) != 0) return 192000;
        if ((mask & BluetoothCodecConfig.SAMPLE_RATE_176400) != 0) return 176400;
        if ((mask & BluetoothCodecConfig.SAMPLE_RATE_96000) != 0) return 96000;
        if ((mask & BluetoothCodecConfig.SAMPLE_RATE_88200) != 0) return 88200;
        if ((mask & BluetoothCodecConfig.SAMPLE_RATE_48000) != 0) return 48000;
        if ((mask & BluetoothCodecConfig.SAMPLE_RATE_44100) != 0) return 44100;
        if ((mask & SAMPLE_RATE_32000) != 0) return 32000;
        if ((mask & SAMPLE_RATE_24000) != 0) return 24000;
        if ((mask & SAMPLE_RATE_16000) != 0) return 16000;
        return 0;
    }

    private static String formatRate(int rate) {
        return rate % 1000 == 0 ? String.format(Locale.ROOT, "%d kHz", rate / 1000)
                : String.format(Locale.ROOT, "%.1f kHz", rate / 1000f);
    }

    private static int parsePositive(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static long delta(long end, long start) {
        return end > 0 && start > 0 && end >= start ? end - start : -1;
    }

    static double toMillis(long nanos) {
        return nanos < 0 ? Double.NaN : nanos / (double) NANOS_PER_MILLI;
    }

    static final class Snapshot {
        final long decodeNanos;
        final long appDispatchNanos;
        final long sharedWriteNanos;
        final long halAcquireNanos;
        final long halPostNanos;
        final long poseToEffectNanos;
        final long effectAgeNanos;
        final int outputLatencyMillis;
        final int sampleRate;
        final int framesPerBuffer;
        final double mixerBlockMillis;
        final String codecSummary;
        final double codecFrameMillis;
        final int predictionMillis;
        final double estimatedTotalMillis;
        final double residualMillis;
        final boolean effectTapAvailable;
        final long halSequence;
        final long producerSequence;

        Snapshot(long decodeNanos, long appDispatchNanos, long sharedWriteNanos,
                long halAcquireNanos, long halPostNanos, long poseToEffectNanos,
                long effectAgeNanos, int outputLatencyMillis, int sampleRate,
                int framesPerBuffer, double mixerBlockMillis, String codecSummary,
                double codecFrameMillis, int predictionMillis, double estimatedTotalMillis,
                double residualMillis, boolean effectTapAvailable, long halSequence,
                long producerSequence) {
            this.decodeNanos = decodeNanos;
            this.appDispatchNanos = appDispatchNanos;
            this.sharedWriteNanos = sharedWriteNanos;
            this.halAcquireNanos = halAcquireNanos;
            this.halPostNanos = halPostNanos;
            this.poseToEffectNanos = poseToEffectNanos;
            this.effectAgeNanos = effectAgeNanos;
            this.outputLatencyMillis = outputLatencyMillis;
            this.sampleRate = sampleRate;
            this.framesPerBuffer = framesPerBuffer;
            this.mixerBlockMillis = mixerBlockMillis;
            this.codecSummary = codecSummary;
            this.codecFrameMillis = codecFrameMillis;
            this.predictionMillis = predictionMillis;
            this.estimatedTotalMillis = estimatedTotalMillis;
            this.residualMillis = residualMillis;
            this.effectTapAvailable = effectTapAvailable;
            this.halSequence = halSequence;
            this.producerSequence = producerSequence;
        }
    }
}
