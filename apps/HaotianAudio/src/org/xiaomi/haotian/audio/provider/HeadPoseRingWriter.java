/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio.provider;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;

import org.xiaomi.haotian.audio.HeadPoseFrame;

import java.io.Closeable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Single-producer writer for the versioned pose ring owned by HaotianAudio. */
public final class HeadPoseRingWriter implements Closeable {
    private static final int MAGIC = 0x48545033; // "HTP3"
    private static final int VERSION = 2;
    private static final int HEADER_SIZE = 64;
    private static final int EXPECTED_RECORD_SIZE = 64;
    private static final int PRODUCER_SEQUENCE_OFFSET = 32;
    private static final VarHandle LONGS = MethodHandles.byteBufferViewVarHandle(
            long[].class, ByteOrder.nativeOrder());

    private final SharedMemory memory;
    private final ByteBuffer buffer;
    private final int recordSize;
    private final int capacity;
    private long sequence;
    private boolean closed;

    public static HeadPoseRingWriter open(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return null;
        SharedMemory memory = null;
        ByteBuffer buffer = null;
        try {
            memory = SharedMemory.fromFileDescriptor(descriptor);
            buffer = memory.mapReadWrite().order(ByteOrder.nativeOrder());
            if (buffer.capacity() < HEADER_SIZE || buffer.getInt(0) != MAGIC
                    || buffer.getInt(4) != VERSION) {
                throw new IllegalArgumentException("Unsupported pose ring");
            }
            int recordSize = buffer.getInt(8);
            int capacity = buffer.getInt(12);
            long required = HEADER_SIZE + (long) recordSize * capacity;
            if (recordSize != EXPECTED_RECORD_SIZE || capacity < 1 || capacity > 1024
                    || required > buffer.capacity()) {
                throw new IllegalArgumentException("Invalid pose ring dimensions");
            }
            long sequence = (long) LONGS.getAcquire(buffer, PRODUCER_SEQUENCE_OFFSET);
            return new HeadPoseRingWriter(memory, buffer, recordSize, capacity, sequence);
        } catch (Exception exception) {
            if (buffer != null) SharedMemory.unmap(buffer);
            if (memory != null) memory.close();
            return null;
        } finally {
            try {
                descriptor.close();
            } catch (Exception ignored) {
            }
        }
    }

    private HeadPoseRingWriter(SharedMemory memory, ByteBuffer buffer, int recordSize,
            int capacity, long sequence) {
        this.memory = memory;
        this.buffer = buffer;
        this.recordSize = recordSize;
        this.capacity = capacity;
        this.sequence = Math.max(0L, sequence);
    }

    public synchronized void publish(HeadPoseFrame frame) {
        if (closed || frame == null) return;
        long next = ++sequence;
        int offset = HEADER_SIZE + (int) (next % capacity) * recordSize;
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
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        SharedMemory.unmap(buffer);
        memory.close();
    }
}
