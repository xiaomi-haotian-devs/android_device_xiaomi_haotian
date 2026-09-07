/* SPDX-License-Identifier: Apache-2.0 */
package org.xiaomi.haotian.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.Spatializer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Activity-scoped read-only tap of the sensor already selected by the native spatializer. */
final class NativeHeadTrackerReader {
    static final String PROVIDER = "android.native-head-tracker";
    private static final int PARAMETER = 0x48545044;
    private final Spatializer spatializer;
    private final OutputDeviceManager outputs;
    private HandlerThread thread;
    private volatile Handler worker;
    private volatile Result latest;
    private volatile boolean running;
    private AudioOutputIdentity identity;

    static final class Result {
        final HeadPoseDebugState.Snapshot pose;
        final String details;
        final boolean sonyYawCorrected;
        Result(HeadPoseDebugState.Snapshot pose, String details, boolean sonyYawCorrected) {
            this.pose = pose;
            this.details = details;
            this.sonyYawCorrected = sonyYawCorrected;
        }
    }

    NativeHeadTrackerReader(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        spatializer = audio.getSpatializer();
        outputs = new OutputDeviceManager(audio, Runnable::run, output -> {});
    }

    void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("NativeHeadTrackerDebug");
        thread.start();
        worker = new Handler(thread.getLooper());
        final Handler owner = worker;
        owner.post(new Runnable() {
            @Override public void run() {
                if (!running || worker != owner) return;
                Result result = read();
                if (!running || worker != owner) return;
                latest = result;
                owner.postDelayed(this, 100);
            }
        });
    }

    void stop() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        latest = null;
    }

    Result latest() {
        Result result = latest;
        return result == null ? null : new Result(
                result.pose.atTime(SystemClock.elapsedRealtimeNanos()), result.details,
                result.sonyYawCorrected);
    }

    private synchronized Result read() {
        try {
            OutputDeviceManager.ActiveOutput output = outputs.current();
            if (identity == null || !identity.deviceKey.equals(output.key)) {
                identity = new AudioOutputIdentity(output);
            }
            // Keep provider-based devices on their existing, richer shared-memory diagnostics.
            HeadPoseDebugState.Snapshot shared = HeadPoseDebugState.snapshot();
            if (shared.outputKey.equals(output.key) && shared.hasOutput()) return null;
            if (!identity.isBluetoothClassicAudio()) return null;
            byte[] packet = new byte[384];
            spatializer.getEffectParameter(PARAMETER, packet);
            ByteBuffer b = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 0x48545031 || b.getInt(4) != 1) return null;
            boolean present = (b.getInt(12) & 1) != 0;
            if (present && text(packet, 256).equals("Apple via haotian AACP")) return null;
            if (present && !matchesUuid(packet, identity.getBluetoothIdentityAddress())) {
                // A route change may reach AudioPolicy before Spatializer replaces its sensor.
                present = false;
            }
            long now = SystemClock.elapsedRealtimeNanos();
            long sample = present ? b.getLong(24) : 0;
            long received = present ? b.getLong(32) : 0;
            long sequence = present ? b.getLong(40) : 0;
            if (sample < 0 || sample > now || received < 0 || received > now) return null;
            float x = present ? b.getFloat(96) : 0;
            float y = present ? b.getFloat(100) : 0;
            float z = present ? b.getFloat(104) : 0;
            float w = present && sequence > 0 ? b.getFloat(108) : 1;
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(w)) return null;
            HeadPoseDebugState.Snapshot pose = new HeadPoseDebugState.Snapshot(
                    output.displayName, output.key, PROVIDER, "", sequence,
                    sample == 0 ? Long.MAX_VALUE : now - sample,
                    sample == 0 ? -1 : Math.max(0, received - sample),
                    present ? b.getFloat(48) : 0, now,
                    0, 0, received, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    x, y, z, w, present ? b.getFloat(112) : 0,
                    present ? b.getFloat(116) : 0, present ? b.getFloat(120) : 0,
                    sequence > 0 ? 1 : 0, present ? b.getInt(20) : 0);
            String details = present ? String.format(Locale.ROOT,
                    "%s · %s\nhandle %d · %s\nraw r: %+.4f, %+.4f, %+.4f rad"
                    + "\nraw v: %+.4f, %+.4f, %+.4f rad/s",
                    text(packet, 128), text(packet, 256), b.getInt(8),
                    b.getInt(16) == 1 ? "Sony XM5: Z reversed" : "standard / unchanged",
                    b.getFloat(72), b.getFloat(76), b.getFloat(80),
                    b.getFloat(84), b.getFloat(88), b.getFloat(92)) : "";
            return new Result(pose, details, present && b.getInt(16) == 1);
        } catch (RuntimeException exception) {
            // Older framework, inactive effect, service restart or permission failure.
            return null;
        }
    }

    private static boolean matchesUuid(byte[] bytes, String address) {
        if (address == null) return false;
        String[] parts = address.split(":");
        if (parts.length != 6) return false;
        for (int i = 0; i < 8; i++) if (bytes[56 + i] != 0) return false;
        if (bytes[64] != 'B' || bytes[65] != 'T') return false;
        for (int i = 0; i < 6; i++) {
            if ((bytes[66 + i] & 255) != Integer.parseInt(parts[i], 16)) return false;
        }
        return true;
    }

    private static String text(byte[] bytes, int start) {
        int end = start;
        while (end < start + 128 && bytes[end] != 0) end++;
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }
}
