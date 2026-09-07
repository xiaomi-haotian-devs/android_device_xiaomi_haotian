/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.mibuds;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.SystemClock;

import com.jieli.bluetooth.impl.RcspAuth;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One narrow Xiaomi RCSP session. It deliberately implements no firmware, file-transfer,
 * voice-assistant, cloud or arbitrary-command surface.
 */
final class MiBudsTransport implements Closeable {
    interface Listener {
        void onTransportConnected();
        void onAuthenticated();
        void onConfig(int type, byte[] value);
        void onPose(long packetReceivedTimestampNanos, float yaw, float pitch, float roll);
        void onCommandRejected(int opcode, int status, boolean poseStreamCommand);
        void onTransportClosed(String reason);
    }

    static final UUID MIUI_SPP_UUID = UUID.fromString(
            "0000fd2d-0000-1000-8000-00805f9b34fb");

    static final int OPCODE_AUTH_CHECK = 0x50;
    static final int OPCODE_AUTH_RESULT = 0x51;
    static final int OPCODE_SET_CONFIG = 0xf2;
    static final int OPCODE_GET_CONFIG = 0xf3;
    static final int OPCODE_NOTIFY_CONFIG = 0xf4;

    static final int CONFIG_NOISE_LEVEL = 0x0b;
    static final int CONFIG_SOUND_PRESET = 0x07;
    static final int CONFIG_SPATIAL_WRITE = 0x1d;
    static final int CONFIG_SPATIAL_READ = 0x1e;
    static final int CONFIG_HEAD_POSE = 0x21;
    static final int CONFIG_LOW_LATENCY = 0x2f;
    static final int CONFIG_WEAR_NOTIFY = 0x7e;

    // Stock BluetoothExtension uses this as the F3/0x1d request value. This is a
    // stream-control request, not the app's one-byte F2 spatial-preference bitfield.
    private static final byte[] POSE_STREAM_REQUEST = new byte[] {
            (byte) 0xff, 0x01, 0x02, 0x01, 0x03, 0x02, 0x05, 0x01, (byte) 0xff
    };
    private static final long AUTH_TIMEOUT_MS = 5_000;
    private static final long CONNECT_TIMEOUT_MS = 15_000;
    private static final int MAX_READ = 4 * 1024;
    private static final String TAG = "HaotianMiBuds";

    private final BluetoothDevice device;
    private final Handler callbackHandler;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MiBudsRfcomm");
        thread.setDaemon(true);
        return thread;
    });
    private final Object writeLock = new Object();
    private final RcspCodec codec = new RcspCodec();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile BluetoothSocket socket;
    private volatile OutputStream output;
    private volatile String failureReason = "Control connection ended";
    private volatile boolean authenticated;
    private volatile boolean poseStreaming;
    private RcspAuth crypto;
    private byte[] hostExpectedProof;
    private int hostAuthSequence = -1;
    private int hostResultSequence = -1;
    private boolean targetVerified;
    private boolean hostResultAccepted;
    private boolean peerChallengeAnswered;
    private boolean peerVerified;
    private volatile int poseCommandSequence = -1;
    private int nextSequence = 1;

    private final Runnable authTimeout = () -> {
        if (!authenticated && !closed.get()) fail("Earbud authentication timed out");
    };
    private final Runnable connectTimeout = () -> {
        if (output == null && !closed.get()) fail("Earbud control connection timed out");
    };

    MiBudsTransport(BluetoothDevice device, Handler callbackHandler, Listener listener) {
        if (device == null || callbackHandler == null || listener == null) {
            throw new IllegalArgumentException("Missing transport dependency");
        }
        this.device = device;
        this.callbackHandler = callbackHandler;
        this.listener = listener;
    }

    void connect() {
        if (!started.compareAndSet(false, true)) return;
        io.execute(this::runSession);
    }

    boolean isAuthenticated() {
        return authenticated && !closed.get();
    }

    boolean queryConfigs(int... types) {
        if (!isAuthenticated() || types == null || types.length == 0 || types.length > 64) {
            return false;
        }
        byte[] parameters = new byte[types.length * 2];
        int cursor = 0;
        for (int type : types) {
            parameters[cursor++] = (byte) (type >>> 8);
            parameters[cursor++] = (byte) type;
        }
        return sendCommand(OPCODE_GET_CONFIG, parameters) >= 0;
    }

    boolean setConfig(int type, byte[] value) {
        if (!isAuthenticated() || value == null || value.length > 253) return false;
        byte[] parameters = new byte[value.length + 3];
        parameters[0] = (byte) (value.length + 2);
        parameters[1] = (byte) (type >>> 8);
        parameters[2] = (byte) type;
        System.arraycopy(value, 0, parameters, 3, value.length);
        return sendCommand(OPCODE_SET_CONFIG, parameters) >= 0;
    }

    boolean setPoseStreaming(boolean enabled) {
        if (!isAuthenticated()) return false;
        byte[] request = POSE_STREAM_REQUEST.clone();
        request[request.length - 1] = enabled ? (byte) 0xff : 0x00;
        byte[] parameters = new byte[request.length + 2];
        parameters[0] = 0x00;
        parameters[1] = (byte) CONFIG_SPATIAL_WRITE;
        System.arraycopy(request, 0, parameters, 2, request.length);
        boolean previousStreaming = poseStreaming;
        poseStreaming = enabled;
        int sequence = sendCommand(OPCODE_GET_CONFIG, parameters, true);
        boolean sent = sequence >= 0;
        if (!sent) poseStreaming = previousStreaming;
        return sent;
    }

    private void runSession() {
        try {
            crypto = new RcspAuth();
            if (!crypto.isInitialized()) throw new IOException("Authentication library rejected init");
            BluetoothSocket opening = device.createRfcommSocketToServiceRecord(MIUI_SPP_UUID);
            socket = opening;
            callbackHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
            opening.connect();
            callbackHandler.removeCallbacks(connectTimeout);
            if (closed.get()) return;
            output = opening.getOutputStream();
            post(listener::onTransportConnected);
            startAuthentication();
            callbackHandler.postDelayed(authTimeout, AUTH_TIMEOUT_MS);

            InputStream input = opening.getInputStream();
            byte[] readBuffer = new byte[MAX_READ];
            while (!closed.get()) {
                int count = input.read(readBuffer);
                if (count < 0) throw new IOException("RFCOMM stream closed");
                if (count == 0) continue;
                long receivedTimestamp = SystemClock.elapsedRealtimeNanos();
                codec.accept(readBuffer, 0, count,
                        frame -> handleFrame(frame, receivedTimestamp));
            }
        } catch (LinkageError error) {
            failureReason = "Authentication library unavailable";
        } catch (IOException | RuntimeException error) {
            if (!closed.get()) failureReason = "Earbud control connection failed";
        } finally {
            closeSocket();
            callbackHandler.removeCallbacks(connectTimeout);
            callbackHandler.removeCallbacks(authTimeout);
            if (closed.compareAndSet(false, true)) postClosed(failureReason);
        }
    }

    private void startAuthentication() throws IOException {
        byte[] challenge = crypto.getRandomData();
        if (challenge == null || challenge.length != 17 || challenge[0] != 0) {
            throw new IOException("Authentication challenge unavailable");
        }
        byte[] expected = crypto.getAuthData(challenge);
        if (expected == null || expected.length != 17) {
            throw new IOException("Authentication proof unavailable");
        }
        hostExpectedProof = expected;
        hostAuthSequence = sendCommand(OPCODE_AUTH_CHECK, challenge);
        if (hostAuthSequence < 0) throw new IOException("Authentication write failed");
    }

    private void handleFrame(RcspCodec.Frame frame, long receivedTimestampNanos) {
        if (closed.get()) return;
        if (frame.isCommand()) {
            handleCommand(frame, receivedTimestampNanos);
        } else {
            handleResponse(frame, receivedTimestampNanos);
        }
    }

    private void handleResponse(RcspCodec.Frame frame, long receivedTimestampNanos) {
        if (frame.opcode == OPCODE_AUTH_CHECK && frame.sequence == hostAuthSequence) {
            if (frame.status != 0 || hostExpectedProof == null
                    || !MessageDigest.isEqual(hostExpectedProof, frame.parameters)) {
                fail("Earbud authentication proof did not match");
                return;
            }
            targetVerified = true;
            hostExpectedProof = null;
            hostResultSequence = sendCommand(OPCODE_AUTH_RESULT, new byte[] {0x01, 0x00});
            if (hostResultSequence < 0) fail("Could not finish earbud authentication");
            return;
        }
        if (frame.opcode == OPCODE_AUTH_RESULT && frame.sequence == hostResultSequence) {
            if (frame.status != 0 || frame.parameters.length != 1
                    || frame.parameters[0] != 0x01) {
                fail("Earbud rejected authentication result");
                return;
            }
            hostResultAccepted = true;
            completeAuthenticationIfReady();
            return;
        }
        boolean poseStreamResponse = frame.opcode == OPCODE_GET_CONFIG
                && frame.sequence == poseCommandSequence;
        if (poseStreamResponse) poseCommandSequence = -1;
        if (frame.status != 0) {
            if (poseStreamResponse) poseStreaming = false;
            int opcode = frame.opcode;
            int status = frame.status;
            post(() -> listener.onCommandRejected(opcode, status, poseStreamResponse));
            return;
        }
        if (frame.opcode == OPCODE_GET_CONFIG || frame.opcode == OPCODE_NOTIFY_CONFIG) {
            dispatchConfigs(frame.parameters, receivedTimestampNanos);
        }
    }

    private void handleCommand(RcspCodec.Frame frame, long receivedTimestampNanos) {
        if (frame.opcode == OPCODE_AUTH_CHECK) {
            byte[] proof = frame.parameters.length == 17 && frame.parameters[0] == 0x01
                    ? crypto.getAuthData(frame.parameters) : null;
            if (proof == null || proof.length != 17) {
                sendResponse(frame, 1, new byte[0]);
                fail("Invalid peer authentication challenge");
                return;
            }
            sendResponse(frame, 0, proof);
            peerChallengeAnswered = true;
            return;
        }
        if (frame.opcode == OPCODE_AUTH_RESULT) {
            boolean valid = peerChallengeAnswered && frame.parameters.length == 2
                    && frame.parameters[0] == 0x01 && frame.parameters[1] == 0x00;
            sendResponse(frame, valid ? 0 : 1, new byte[] {0x01});
            if (!valid) {
                fail("Peer authentication failed");
                return;
            }
            peerVerified = true;
            completeAuthenticationIfReady();
            return;
        }

        // Firmware notifications require an RCSP response. Acknowledge before doing
        // any parsing or Binder work so the high-rate pose path cannot stall the bud.
        if (frame.requestsResponse()) sendResponse(frame, 0, new byte[0]);
        if (!authenticated) return;
        if (frame.opcode == OPCODE_NOTIFY_CONFIG || frame.opcode == OPCODE_GET_CONFIG) {
            dispatchConfigs(frame.parameters, receivedTimestampNanos);
        }
    }

    private void completeAuthenticationIfReady() {
        if (authenticated || !targetVerified || !hostResultAccepted || !peerVerified) return;
        authenticated = true;
        callbackHandler.removeCallbacks(authTimeout);
        post(listener::onAuthenticated);
    }

    private void dispatchConfigs(byte[] parameters, long receivedTimestampNanos) {
        int cursor = 0;
        while (cursor + 3 <= parameters.length) {
            int itemSize = parameters[cursor] & 0xff;
            if (itemSize < 2 || cursor + 1 + itemSize > parameters.length) return;
            int type = ((parameters[cursor + 1] & 0xff) << 8)
                    | (parameters[cursor + 2] & 0xff);
            byte[] value = Arrays.copyOfRange(parameters, cursor + 3,
                    cursor + 1 + itemSize);
            if (type == CONFIG_HEAD_POSE && value.length >= 12) {
                ByteBuffer pose = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
                float yaw = pose.getFloat();
                float pitch = pose.getFloat();
                float roll = pose.getFloat();
                if (Float.isFinite(yaw) && Float.isFinite(pitch) && Float.isFinite(roll)) {
                    post(() -> listener.onPose(
                            receivedTimestampNanos, yaw, pitch, roll));
                }
            } else {
                post(() -> listener.onConfig(type, value));
            }
            cursor += itemSize + 1;
        }
    }

    private int sendCommand(int opcode, byte[] parameters) {
        return sendCommand(opcode, parameters, false);
    }

    private int sendCommand(int opcode, byte[] parameters, boolean poseStreamCommand) {
        synchronized (writeLock) {
            if (closed.get() || output == null) return -1;
            int sequence = nextSequence++ & 0xff;
            // Publish the command classification before bytes reach the peer: its
            // response can arrive on the reader thread immediately after flush().
            if (poseStreamCommand) poseCommandSequence = sequence;
            try {
                output.write(RcspCodec.command(opcode, sequence, parameters));
                output.flush();
                return sequence;
            } catch (IOException | RuntimeException error) {
                if (poseStreamCommand && poseCommandSequence == sequence) {
                    poseCommandSequence = -1;
                }
                fail("Earbud control write failed");
                return -1;
            }
        }
    }

    private void sendResponse(RcspCodec.Frame request, int status, byte[] parameters) {
        synchronized (writeLock) {
            if (closed.get() || output == null) return;
            try {
                output.write(RcspCodec.response(
                        request.opcode, request.sequence, status, parameters));
                output.flush();
            } catch (IOException | RuntimeException error) {
                fail("Earbud control response failed");
            }
        }
    }

    private void fail(String reason) {
        if (closed.get()) return;
        failureReason = reason;
        closeSocket();
    }

    private void post(Runnable runnable) {
        if (!closed.get()) callbackHandler.post(runnable);
    }

    private void postClosed(String reason) {
        callbackHandler.post(() -> listener.onTransportClosed(reason));
    }

    @Override
    public void close() {
        boolean wasOpen = !closed.get();
        if (wasOpen && authenticated && poseStreaming) {
            // Best effort: the earbud must not keep a high-rate IMU producer enabled
            // after the phone drops its consumer.
            setPoseStreaming(false);
        }
        closed.set(true);
        callbackHandler.removeCallbacks(connectTimeout);
        callbackHandler.removeCallbacks(authTimeout);
        closeSocket();
        io.shutdownNow();
    }

    private void closeSocket() {
        BluetoothSocket current = socket;
        socket = null;
        output = null;
        if (current == null) return;
        try {
            current.close();
        } catch (IOException ignored) {
        }
    }
}
