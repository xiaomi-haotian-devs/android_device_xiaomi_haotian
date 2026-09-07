/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import java.util.Arrays;

/** Bounded incremental codec for the RCSP-over-RFCOMM envelope used by Xiaomi Buds. */
final class RcspCodec {
    interface Sink {
        void onFrame(Frame frame);
    }

    static final class Frame {
        final int flag;
        final int opcode;
        final int sequence;
        final int status;
        final byte[] parameters;

        Frame(int flag, int opcode, int sequence, int status, byte[] parameters) {
            this.flag = flag;
            this.opcode = opcode;
            this.sequence = sequence;
            this.status = status;
            this.parameters = parameters;
        }

        boolean isCommand() {
            return (flag & 0x80) != 0;
        }

        boolean requestsResponse() {
            return (flag & 0x40) != 0;
        }
    }

    private static final int HEADER_SIZE = 7;
    private static final int MAX_BODY_SIZE = 8 * 1024;
    private static final int MAX_FRAME_SIZE = HEADER_SIZE + MAX_BODY_SIZE + 1;
    private final byte[] buffered = new byte[MAX_FRAME_SIZE * 2];
    private int bufferedSize;

    void accept(byte[] input, int offset, int length, Sink sink) {
        if (input == null || sink == null || offset < 0 || length < 0
                || offset + length > input.length) {
            return;
        }
        if (length > buffered.length - bufferedSize) {
            // A valid packet is at most MAX_FRAME_SIZE. Preserve only a possible
            // partial magic prefix instead of allowing an attacker-controlled grow.
            bufferedSize = 0;
            if (length > buffered.length) {
                offset += length - buffered.length;
                length = buffered.length;
            }
        }
        System.arraycopy(input, offset, buffered, bufferedSize, length);
        bufferedSize += length;
        drain(sink);
    }

    void reset() {
        bufferedSize = 0;
    }

    private void drain(Sink sink) {
        while (true) {
            int magic = findMagic();
            if (magic < 0) {
                preserveMagicPrefix();
                return;
            }
            if (magic > 0) discard(magic);
            if (bufferedSize < HEADER_SIZE) return;

            int bodySize = ((buffered[5] & 0xff) << 8) | (buffered[6] & 0xff);
            int totalSize = HEADER_SIZE + bodySize + 1;
            if (bodySize < 1 || bodySize > MAX_BODY_SIZE) {
                discard(1);
                continue;
            }
            if (bufferedSize < totalSize) return;
            if ((buffered[totalSize - 1] & 0xff) != 0xef) {
                discard(1);
                continue;
            }

            int flag = buffered[3] & 0xff;
            int opcode = buffered[4] & 0xff;
            boolean command = (flag & 0x80) != 0;
            int minimumBody = command ? 1 : 2;
            if (bodySize < minimumBody) {
                discard(totalSize);
                continue;
            }
            int status = command ? 0 : buffered[7] & 0xff;
            int sequence = buffered[command ? 7 : 8] & 0xff;
            int parameterOffset = command ? 8 : 9;
            int parameterLength = bodySize - minimumBody;
            byte[] parameters = Arrays.copyOfRange(
                    buffered, parameterOffset, parameterOffset + parameterLength);
            discard(totalSize);
            sink.onFrame(new Frame(flag, opcode, sequence, status, parameters));
        }
    }

    static byte[] command(int opcode, int sequence, byte[] parameters) {
        return encode(0xc4, opcode, sequence, 0, parameters);
    }

    static byte[] response(int opcode, int sequence, int status, byte[] parameters) {
        return encode(0x04, opcode, sequence, status, parameters);
    }

    private static byte[] encode(int flag, int opcode, int sequence, int status,
            byte[] parameters) {
        byte[] payload = parameters == null ? new byte[0] : parameters;
        boolean command = (flag & 0x80) != 0;
        int bodySize = payload.length + (command ? 1 : 2);
        if (bodySize > MAX_BODY_SIZE) throw new IllegalArgumentException("RCSP body too large");
        byte[] packet = new byte[HEADER_SIZE + bodySize + 1];
        packet[0] = (byte) 0xfe;
        packet[1] = (byte) 0xdc;
        packet[2] = (byte) 0xba;
        packet[3] = (byte) flag;
        packet[4] = (byte) opcode;
        packet[5] = (byte) (bodySize >>> 8);
        packet[6] = (byte) bodySize;
        int cursor = 7;
        if (!command) packet[cursor++] = (byte) status;
        packet[cursor++] = (byte) sequence;
        System.arraycopy(payload, 0, packet, cursor, payload.length);
        packet[packet.length - 1] = (byte) 0xef;
        return packet;
    }

    private int findMagic() {
        for (int i = 0; i + 2 < bufferedSize; i++) {
            if ((buffered[i] & 0xff) == 0xfe
                    && (buffered[i + 1] & 0xff) == 0xdc
                    && (buffered[i + 2] & 0xff) == 0xba) {
                return i;
            }
        }
        return -1;
    }

    private void preserveMagicPrefix() {
        if (bufferedSize >= 2
                && (buffered[bufferedSize - 2] & 0xff) == 0xfe
                && (buffered[bufferedSize - 1] & 0xff) == 0xdc) {
            buffered[0] = buffered[bufferedSize - 2];
            buffered[1] = buffered[bufferedSize - 1];
            bufferedSize = 2;
        } else if (bufferedSize >= 1 && (buffered[bufferedSize - 1] & 0xff) == 0xfe) {
            buffered[0] = buffered[bufferedSize - 1];
            bufferedSize = 1;
        } else {
            bufferedSize = 0;
        }
    }

    private void discard(int count) {
        if (count >= bufferedSize) {
            bufferedSize = 0;
            return;
        }
        System.arraycopy(buffered, count, buffered, 0, bufferedSize - count);
        bufferedSize -= count;
    }
}
