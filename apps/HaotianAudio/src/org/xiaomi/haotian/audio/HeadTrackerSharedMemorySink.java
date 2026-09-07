/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-producer lock-free pose ring consumed by the haotian Sensors sub-HAL.
 *
 * <p>The LocalSocket carries one file descriptor and remains only as a lifetime signal. Every
 * high-rate sample is a release/acquire shared-memory operation; it never uses Binder or a socket
 * transaction.</p>
 */
final class HeadTrackerSharedMemorySink implements NormalizedHeadPoseProvider.Sink, Closeable {
    private static final String TAG = "HaotianHeadTrackerShm";
    private static final String SOCKET_NAME = "haotian_headtracker_v2";
    private static final int MAGIC = 0x48545033; // "HTP3"
    private static final int VERSION = 2;
    private static final int HEADER_SIZE = 64;
    private static final int RECORD_SIZE = 64;
    private static final int CAPACITY = 128;
    private static final int BUFFER_SIZE = HEADER_SIZE + RECORD_SIZE * CAPACITY;
    private static final int PRODUCER_SEQUENCE_OFFSET = 32;
    private static final int CONSUMER_SEQUENCE_OFFSET = 40;
    private static final int CONSUMER_READ_TIMESTAMP_OFFSET = 48;
    private static final int CONSUMER_POST_TIMESTAMP_OFFSET = 56;
    private static final long OUTPUT_DISCONNECT_GRACE_MS = 10_000;
    private static final VarHandle LONGS = MethodHandles.byteBufferViewVarHandle(
            long[].class, ByteOrder.nativeOrder());

    private final Object lock = new Object();
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HaotianHeadTrackerShm");
        thread.setDaemon(true);
        return thread;
    });

    private LocalServerSocket server;
    private LocalSocket halConnection;
    private AudioOutputIdentity output;
    private PoseBuffer poseBuffer;
    private String poseBufferDeviceKey = "";
    private int attachmentGeneration;
    private int outputGeneration;
    private String externalProviderId = "";
    private long monitoredSequence;
    private boolean closed;
    private final Runnable monitorExternalPose = new Runnable() {
        @Override
        public void run() {
            boolean keepMonitoring;
            synchronized (lock) {
                keepMonitoring = !closed && !externalProviderId.isEmpty();
                if (keepMonitoring && output != null && poseBuffer != null) {
                    monitorLatestPoseLocked();
                }
            }
            if (keepMonitoring) mainHandler.postDelayed(this, 20);
        }
    };

    HeadTrackerSharedMemorySink(Context context) {
        this.context = context.getApplicationContext();
        try {
            server = new LocalServerSocket(SOCKET_NAME);
            acceptExecutor.execute(this::acceptLoop);
        } catch (IOException exception) {
            Log.e(TAG, "Could not create Sensors HAL shared-memory endpoint", exception);
        }
    }

    @Override
    public void onOutputChanged(AudioOutputIdentity requestedOutput) {
        HeadPoseDebugState.onOutputChanged(requestedOutput);
        synchronized (lock) {
            String newKey = requestedOutput == null ? "" : requestedOutput.deviceKey;
            output = requestedOutput;
            final int generation = ++outputGeneration;
            if (newKey.isEmpty()) {
                // A2DP route changes briefly report no active output while Bluetooth reconnects.
                // Preserve the ring and dynamic sensor across that harmless gap instead of
                // destroying and republishing the head tracker on every profile bounce.
                if (poseBuffer != null) {
                    mainHandler.postDelayed(
                            () -> expireDisconnectedOutput(generation),
                            OUTPUT_DISCONNECT_GRACE_MS);
                }
                return;
            }
            if (newKey.equals(poseBufferDeviceKey)) return;

            disconnectProducerLocked();
            // The registry only selects this sink after a headphone provider has accepted the
            // active A2DP peer. Publish the dynamic sensor before motion calibration finishes
            // so AudioService can discover it during the same routing update instead of
            // caching a missing (-1) head-tracker handle.
            ensurePoseBufferLocked();
        }
    }

    @Override
    public void onPose(HeadPoseFrame frame) {
        final long sinkReceivedNanos = SystemClock.elapsedRealtimeNanos();
        synchronized (lock) {
            if (closed || output == null || !frame.deviceKey.equals(output.deviceKey)) return;
            if (poseBuffer == null && !ensurePoseBufferLocked()) {
                HeadPoseDebugState.onPose(frame, sinkReceivedNanos, 0, 0, 0, 0, 0, 0);
                return;
            }
            long publishStartNanos = SystemClock.elapsedRealtimeNanos();
            long publishedSequence = poseBuffer.publish(frame);
            long publishEndNanos = SystemClock.elapsedRealtimeNanos();
            ConsumerTelemetry consumer = poseBuffer.readConsumerTelemetry();
            HeadPoseDebugState.onPose(frame, sinkReceivedNanos, publishedSequence,
                    publishStartNanos, publishEndNanos, consumer.sequence,
                    consumer.readTimestampNanos, consumer.postTimestampNanos);
            if (halConnection == null) attachLocked();
        }
    }

    @Override
    public void onProviderError(String providerId, String deviceKey, String reason) {
        HeadPoseDebugState.onProviderError(providerId, deviceKey, reason);
        Log.w(TAG, providerId + ": " + reason);
    }

    @Override
    public void close() {
        HeadPoseDebugState.clear();
        synchronized (lock) {
            closed = true;
            outputGeneration++;
            disconnectProducerLocked();
            closeQuietly(halConnection);
            halConnection = null;
            closeQuietly(server);
            server = null;
        }
        acceptExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    /** Returns a writable duplicate to the selected signature-trusted companion provider. */
    ParcelFileDescriptor openWritablePoseBuffer(String deviceAddress) {
        synchronized (lock) {
            if (closed || output == null || deviceAddress == null || deviceAddress.isEmpty()
                    || !deviceAddress.equalsIgnoreCase(output.getBluetoothTransportAddress())
                    || !ensurePoseBufferLocked()) {
                return null;
            }
            try {
                return ParcelFileDescriptor.dup(poseBuffer.memory.getFileDescriptor());
            } catch (IOException exception) {
                Log.w(TAG, "Could not duplicate writable pose buffer", exception);
                return null;
            }
        }
    }

    void setExternalProvider(String providerId) {
        synchronized (lock) {
            externalProviderId = providerId == null ? "" : providerId;
            monitoredSequence = 0L;
        }
        mainHandler.removeCallbacks(monitorExternalPose);
        if (providerId != null && !providerId.isEmpty()) {
            mainHandler.post(monitorExternalPose);
        }
    }

    private void acceptLoop() {
        while (true) {
            LocalServerSocket current;
            synchronized (lock) {
                if (closed || server == null) return;
                current = server;
            }
            try {
                LocalSocket accepted = current.accept();
                synchronized (lock) {
                    if (closed) {
                        closeQuietly(accepted);
                        return;
                    }
                    closeQuietly(halConnection);
                    halConnection = accepted;
                    attachLocked();
                }
            } catch (IOException exception) {
                synchronized (lock) {
                    if (!closed) Log.w(TAG, "Sensors HAL endpoint disconnected");
                    halConnection = null;
                }
            }
        }
    }

    private void attachLocked() {
        if (poseBuffer == null || halConnection == null) return;
        try {
            halConnection.setFileDescriptorsForSend(
                    new FileDescriptor[] {poseBuffer.memory.getFileDescriptor()});
            ByteBuffer handshake = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            handshake.putInt(MAGIC).putInt(VERSION).putInt(BUFFER_SIZE).putInt(0);
            halConnection.getOutputStream().write(handshake.array());
            halConnection.getOutputStream().flush();
            halConnection.setFileDescriptorsForSend(null);
            Log.i(TAG, "Shared pose ring attached to Sensors HAL");
            scheduleDynamicSensorRefreshLocked();
        } catch (IOException exception) {
            closeQuietly(halConnection);
            halConnection = null;
        }
    }

    private void disconnectProducerLocked() {
        attachmentGeneration++;
        closeQuietly(halConnection);
        halConnection = null;
        if (poseBuffer != null) poseBuffer.close();
        poseBuffer = null;
        poseBufferDeviceKey = "";
        monitoredSequence = 0L;
    }

    private void monitorLatestPoseLocked() {
        PoseBuffer.MonitoredFrame monitored = poseBuffer.readLatest(
                output.deviceKey, externalProviderId, monitoredSequence);
        if (monitored == null) return;
        monitoredSequence = monitored.sequence;
        long now = SystemClock.elapsedRealtimeNanos();
        ConsumerTelemetry consumer = poseBuffer.readConsumerTelemetry();
        HeadPoseDebugState.onPose(monitored.frame, now, monitored.sequence,
                now, now, consumer.sequence, consumer.readTimestampNanos,
                consumer.postTimestampNanos);
    }

    private void expireDisconnectedOutput(int generation) {
        synchronized (lock) {
            if (closed || output != null || generation != outputGeneration) return;
            Log.i(TAG, "Releasing head tracker after output disconnect grace period");
            disconnectProducerLocked();
        }
    }

    private void scheduleDynamicSensorRefreshLocked() {
        final int generation = ++attachmentGeneration;
        // The HIDL dynamic-sensor callback is followed by its FMQ meta event after a deliberate
        // one-second ordering delay in the sub-HAL. Refreshing earlier would let
        // SystemSensorManager cache an empty list again, so notify after that window and retry
        // once for a busy boot.
        for (long delayMs : new long[] {1_500, 3_000}) {
            mainHandler.postDelayed(() -> notifyDynamicSensorChanged(generation), delayMs);
        }
    }

    private void notifyDynamicSensorChanged(int generation) {
        synchronized (lock) {
            if (closed || halConnection == null || generation != attachmentGeneration) return;
        }
        try {
            context.sendBroadcast(new Intent(Intent.ACTION_DYNAMIC_SENSOR_CHANGED)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY));
            Log.i(TAG, "Notified framework of dynamic head-tracker change");
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not refresh framework dynamic-sensor cache", exception);
        }
    }

    private boolean ensurePoseBufferLocked() {
        if (poseBuffer != null) return true;
        try {
            String address = output == null ? "" : output.getBluetoothIdentityAddress();
            if (address.isEmpty()) return false;
            poseBuffer = new PoseBuffer(bluetoothUuid(address));
            poseBufferDeviceKey = output.deviceKey;
            attachLocked();
            return true;
        } catch (ErrnoException | IOException exception) {
            Log.e(TAG, "Could not create pose shared memory", exception);
            disconnectProducerLocked();
            return false;
        }
    }

    private static byte[] bluetoothUuid(String address) {
        byte[] uuid = new byte[16];
        uuid[8] = 0x42; // B
        uuid[9] = 0x54; // T
        String[] parts = address.toLowerCase(Locale.ROOT).split(":");
        if (parts.length != 6) throw new IllegalArgumentException("Invalid Bluetooth address");
        for (int i = 0; i < parts.length; i++) {
            uuid[10 + i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return uuid;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static final class PoseBuffer implements Closeable {
        final SharedMemory memory;
        final ByteBuffer buffer;
        long sequence;

        PoseBuffer(byte[] uuid) throws ErrnoException, IOException {
            memory = createMemfdSharedMemory();
            buffer = memory.mapReadWrite().order(ByteOrder.nativeOrder());
            buffer.putInt(0, MAGIC);
            buffer.putInt(4, VERSION);
            buffer.putInt(8, RECORD_SIZE);
            buffer.putInt(12, CAPACITY);
            buffer.position(16);
            buffer.put(uuid);
            LONGS.setRelease(buffer, PRODUCER_SEQUENCE_OFFSET, 0L);
            LONGS.setRelease(buffer, CONSUMER_SEQUENCE_OFFSET, 0L);
            buffer.putLong(CONSUMER_READ_TIMESTAMP_OFFSET, 0L);
            buffer.putLong(CONSUMER_POST_TIMESTAMP_OFFSET, 0L);
        }

        private static SharedMemory createMemfdSharedMemory()
                throws ErrnoException, IOException {
            // SharedMemory.create() may use legacy ashmem, whose character-device fstat size is
            // zero. The vendor-side Sensors sub-HAL validates the received descriptor with fstat
            // before mmap, so use a sealed-capable memfd with a real st_size on this path.
            FileDescriptor descriptor = Os.memfd_create(
                    "haotian-head-pose", OsConstants.MFD_CLOEXEC | 0x0002 /* MFD_ALLOW_SEALING */);
            try {
                Os.ftruncate(descriptor, BUFFER_SIZE);
                return SharedMemory.fromFileDescriptor(ParcelFileDescriptor.dup(descriptor));
            } finally {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException ignored) {
                }
            }
        }

        long publish(HeadPoseFrame frame) {
            long current = (long) LONGS.getAcquire(buffer, PRODUCER_SEQUENCE_OFFSET);
            sequence = Math.max(sequence, current);
            long next = ++sequence;
            int slot = (int) (next % CAPACITY);
            int offset = HEADER_SIZE + slot * RECORD_SIZE;
            buffer.putLong(offset + 8, frame.timestampNanos);
            buffer.putFloat(offset + 16, frame.qx);
            buffer.putFloat(offset + 20, frame.qy);
            buffer.putFloat(offset + 24, frame.qz);
            buffer.putFloat(offset + 28, frame.qw);
            buffer.putFloat(offset + 32, frame.vx);
            buffer.putFloat(offset + 36, frame.vy);
            buffer.putFloat(offset + 40, frame.vz);
            buffer.putFloat(offset + 44, frame.confidence);
            buffer.putInt(offset + 48, frame.discontinuityCount & 0xff);
            LONGS.setRelease(buffer, offset, next);
            LONGS.setRelease(buffer, PRODUCER_SEQUENCE_OFFSET, next);
            return next;
        }

        ConsumerTelemetry readConsumerTelemetry() {
            long before = (long) LONGS.getAcquire(buffer, CONSUMER_SEQUENCE_OFFSET);
            if (before <= 0) return ConsumerTelemetry.EMPTY;
            long readNanos = buffer.getLong(CONSUMER_READ_TIMESTAMP_OFFSET);
            long postNanos = buffer.getLong(CONSUMER_POST_TIMESTAMP_OFFSET);
            long after = (long) LONGS.getAcquire(buffer, CONSUMER_SEQUENCE_OFFSET);
            if (before != after || readNanos <= 0) return ConsumerTelemetry.EMPTY;
            return new ConsumerTelemetry(after, readNanos, postNanos);
        }

        MonitoredFrame readLatest(String deviceKey, String providerId, long consumedSequence) {
            long producerSequence = (long) LONGS.getAcquire(buffer, PRODUCER_SEQUENCE_OFFSET);
            if (producerSequence <= consumedSequence) return null;
            int offset = HEADER_SIZE + (int) (producerSequence % CAPACITY) * RECORD_SIZE;
            long recordSequence = (long) LONGS.getAcquire(buffer, offset);
            if (recordSequence != producerSequence) return null;
            long timestampNanos = buffer.getLong(offset + 8);
            float qx = buffer.getFloat(offset + 16);
            float qy = buffer.getFloat(offset + 20);
            float qz = buffer.getFloat(offset + 24);
            float qw = buffer.getFloat(offset + 28);
            float vx = buffer.getFloat(offset + 32);
            float vy = buffer.getFloat(offset + 36);
            float vz = buffer.getFloat(offset + 40);
            float confidence = buffer.getFloat(offset + 44);
            int discontinuity = buffer.getInt(offset + 48) & 0xff;
            if ((long) LONGS.getAcquire(buffer, offset) != recordSequence) return null;
            try {
                long decodedNanos = Math.max(timestampNanos, SystemClock.elapsedRealtimeNanos());
                HeadPoseFrame frame = new HeadPoseFrame(deviceKey, providerId,
                        timestampNanos, decodedNanos, qx, qy, qz, qw,
                        vx, vy, vz, confidence, discontinuity);
                return new MonitoredFrame(producerSequence, frame);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        @Override
        public void close() {
            SharedMemory.unmap(buffer);
            memory.close();
        }

        static final class MonitoredFrame {
            final long sequence;
            final HeadPoseFrame frame;

            MonitoredFrame(long sequence, HeadPoseFrame frame) {
                this.sequence = sequence;
                this.frame = frame;
            }
        }
    }

    private static final class ConsumerTelemetry {
        static final ConsumerTelemetry EMPTY = new ConsumerTelemetry(0, 0, 0);

        final long sequence;
        final long readTimestampNanos;
        final long postTimestampNanos;

        ConsumerTelemetry(long sequence, long readTimestampNanos, long postTimestampNanos) {
            this.sequence = sequence;
            this.readTimestampNanos = readTimestampNanos;
            this.postTimestampNanos = postTimestampNanos;
        }
    }
}
