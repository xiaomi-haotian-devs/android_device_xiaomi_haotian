/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.airpods;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.xiaomi.haotian.audio.HeadPoseFrame;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** AirPods Pro 3 AACP/RTBuddy provider over Bluetooth Classic L2CAP PSM 0x1001. */
final class AirPodsPro3HeadPoseProvider implements NormalizedHeadPoseProvider {
    private static final String TAG = "HaotianAirPodsHT";
    private static final String PROVIDER_ID = "airpods-pro3-aacp";
    private static final ParcelUuid PODS_UUID = new ParcelUuid(
            UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"));
    private static final ParcelUuid ZERO_UUID = new ParcelUuid(new UUID(0L, 0L));
    private static final int ATT_PSM = 31;
    private static final int BATTERY_COMPONENT_RIGHT = 2;
    private static final int BATTERY_COMPONENT_LEFT = 4;
    private static final int MAX_ATT_VALUE_BYTES = 512;
    private static final int RETAINED_AACP_MESSAGE_CONVERSATION_AWARENESS = 0x4b;
    private static final int RETAINED_AACP_MESSAGE_CUSTOM_EQ = 0x63;
    private static final int[] RETAINED_AACP_TRANSPORT_MESSAGES = {
            0x0e, // active audio source
            0x11, // Smart Routing response
            0x2e, // connected TiPi devices
            0x31, // proximity IRK/encryption keys
    };
    private static final long INITIALIZATION_STEP_DELAY_MS = 200;
    private static final long STREAM_STALL_TIMEOUT_MS = 3_000;
    private static final long STREAM_WATCHDOG_INTERVAL_MS = 1_000;
    private static final long[] RETRY_SECONDS = {1, 2, 4, 8, 16, 30};

    private final BluetoothAdapter adapter;
    private final AtomicInteger threadIndex = new AtomicInteger();
    private final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(3,
            runnable -> {
                Thread thread = new Thread(runnable,
                        "AirPodsPro3Aacp-" + threadIndex.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicInteger commandSequence = new AtomicInteger(0x120);
    private final Object writerLock = new Object();

    private final Object lock = new Object();
    interface StateListener {
        void onStateChanged(AirPodsState state);
    }

    interface StemPressListener {
        void onStemPress(int pressType, int bud);
    }

    private volatile boolean requested;
    private volatile boolean poseStreamingRequested;
    private int sessionGeneration;
    private AudioOutputIdentity output;
    private Sink sink;
    private BluetoothSocket socket;
    private BluetoothSocket attSocket;
    private int retryIndex;
    private int unvalidatedFailures;
    private volatile boolean rejectedPeer;
    private volatile SessionEvidence activeEvidence;
    private volatile AttSession activeAttSession;
    private final StateListener stateListener;
    private final StemPressListener stemPressListener;
    private volatile int requestedStemConfig;
    private AirPodsState state = new AirPodsState();
    // Ear-detection notifications are ordered as primary/secondary, not left/right. Battery
    // notifications identify the primary pod by placing its component first.
    private int primaryPodComponent;
    private int primaryWear = AirPodsState.WEAR_UNKNOWN;
    private int secondaryWear = AirPodsState.WEAR_UNKNOWN;

    AirPodsPro3HeadPoseProvider(BluetoothAdapter adapter, StateListener stateListener,
            StemPressListener stemPressListener) {
        this.adapter = adapter;
        this.stateListener = stateListener;
        this.stemPressListener = stemPressListener;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supports(AudioOutputIdentity requestedOutput) {
        // Names are user-editable, so they are only a cheap pre-filter. A usable provider is not
        // published until the peer returns a structurally valid RTBuddy motion stream.
        if (adapter == null || requestedOutput == null) {
            Log.i(TAG, "Ignoring output: Bluetooth adapter or output identity is unavailable");
            return false;
        }
        if (!requestedOutput.isBluetoothClassicAudio()) {
            Log.i(TAG, "Ignoring non-A2DP output type " + requestedOutput.deviceType);
            return false;
        }
        String address = requestedOutput.getBluetoothIdentityAddress();
        if (address.isEmpty()) {
            Log.i(TAG, "Ignoring A2DP output without a valid Bluetooth identity address");
            return false;
        }
        try {
            String transport = requestedOutput.getBluetoothTransportAddress();
            if (transport.isEmpty()) {
                Log.i(TAG, "Ignoring A2DP output without a valid transport address");
                return false;
            }
            BluetoothDevice device = adapter.getRemoteDevice(transport);
            boolean bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
            boolean podsService = hasPodsService(device);
            boolean nameHint = looksLikeAirPodsPro(requestedOutput, device);
            Log.i(TAG, "A2DP candidate: bonded=" + bonded + ", podsUuid=" + podsService
                    + ", nameHint=" + nameHint);
            // The proprietary Pods SDP service is stronger evidence than a mutable display name.
            // Model/stream validation still happens before publishing a dynamic Android sensor.
            return bonded && (podsService || nameHint);
        } catch (IllegalArgumentException | SecurityException exception) {
            Log.w(TAG, "Could not inspect active A2DP peer", exception);
            return false;
        }
    }

    @Override
    public void start(AudioOutputIdentity requestedOutput, Sink requestedSink) {
        if (!supports(requestedOutput)) throw new IllegalArgumentException("Unsupported route");
        stop();
        final int generation;
        synchronized (lock) {
            output = requestedOutput;
            sink = requestedSink;
            requested = true;
            // The AACP session also transports battery, wear and listening-mode state. Motion is
            // enabled independently by HeadPoseProviderRegistry only when head tracking is active.
            poseStreamingRequested = false;
            retryIndex = 0;
            unvalidatedFailures = 0;
            rejectedPeer = false;
            generation = ++sessionGeneration;
        }
        executor.execute(() -> connectAndRead(generation));
    }

    @Override
    public void stop() {
        BluetoothSocket closing;
        BluetoothSocket closingAtt;
        SessionEvidence stoppingEvidence;
        AttSession stoppingAtt;
        synchronized (lock) {
            requested = false;
            poseStreamingRequested = false;
            sessionGeneration++;
            closing = socket;
            socket = null;
            closingAtt = attSocket;
            attSocket = null;
            stoppingEvidence = activeEvidence;
            activeEvidence = null;
            stoppingAtt = activeAttSession;
            activeAttSession = null;
        }
        if (stoppingEvidence != null) stoppingEvidence.closeSession();
        if (stoppingAtt != null) stoppingAtt.closeSession();
        closeQuietly(closing);
        closeQuietly(closingAtt);
        updateDisconnectedState();
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }

    @Override
    public boolean recenter() {
        SessionEvidence evidence = activeEvidence;
        return evidence != null && evidence.recenter();
    }

    @Override
    public void setPoseStreamingEnabled(boolean enabled) {
        SessionEvidence evidence;
        synchronized (lock) {
            if (!requested) {
                poseStreamingRequested = false;
                return;
            }
            if (poseStreamingRequested == enabled) return;
            poseStreamingRequested = enabled;
            evidence = activeEvidence;
        }
        if (evidence != null) {
            executor.execute(() -> evidence.setPoseStreamingEnabled(enabled));
        }
    }

    AirPodsState getState() {
        synchronized (lock) {
            return new AirPodsState(state);
        }
    }

    boolean setNoiseControlMode(String deviceAddress, int mode) {
        if (mode < AirPodsState.NOISE_OFF || mode > AirPodsState.NOISE_ADAPTIVE) return false;
        return setControlValue(deviceAddress, 0x0d, new byte[] {(byte) mode});
    }

    boolean setControlValue(String deviceAddress, int identifier, byte[] value) {
        if (identifier < 0 || identifier > 0xff || value == null
                || value.length < 1 || value.length > 4) return false;
        SessionEvidence evidence;
        synchronized (lock) {
            if (!requested || !state.connected
                    || (!deviceAddress.isEmpty()
                            && !deviceAddress.equalsIgnoreCase(state.deviceAddress))) {
                return false;
            }
            evidence = activeEvidence;
        }
        if (evidence == null) return false;
        byte[] requestedValue = java.util.Arrays.copyOf(value, value.length);
        executor.execute(() -> evidence.setControlValue(identifier, requestedValue));
        return true;
    }

    void setStemConfig(int config) {
        if ((config & ~0x0f) != 0) throw new IllegalArgumentException("Invalid stem config");
        requestedStemConfig = config;
        SessionEvidence evidence = activeEvidence;
        if (evidence != null) {
            executor.execute(() -> evidence.setControlValue(0x39,
                    new byte[] {(byte) config}));
        }
    }

    boolean rename(String deviceAddress, String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String requestedName = name.trim();
        if (requestedName.getBytes(StandardCharsets.UTF_8).length > 255) return false;
        SessionEvidence evidence;
        synchronized (lock) {
            if (!requested || !state.connected
                    || (!deviceAddress.isEmpty()
                            && !deviceAddress.equalsIgnoreCase(state.deviceAddress))) {
                return false;
            }
            evidence = activeEvidence;
        }
        if (evidence == null) return false;
        executor.execute(() -> evidence.rename(requestedName));
        return true;
    }

    boolean refreshAttValue(String deviceAddress, int handle) {
        if (handle < 1 || handle > 0xffff) return false;
        AttSession session;
        synchronized (lock) {
            if (!matchesConnectedDeviceLocked(deviceAddress) || !state.attConnected) return false;
            session = activeAttSession;
        }
        if (session == null) return false;
        executor.execute(() -> session.requestRead(handle));
        return true;
    }

    boolean setAttValue(String deviceAddress, int handle, byte[] value) {
        if (handle < 1 || handle > 0xffff || value == null
                || value.length > MAX_ATT_VALUE_BYTES) return false;
        AttSession session;
        synchronized (lock) {
            if (!matchesConnectedDeviceLocked(deviceAddress) || !state.attConnected) return false;
            session = activeAttSession;
        }
        if (session == null) return false;
        byte[] requestedValue = java.util.Arrays.copyOf(value, value.length);
        executor.execute(() -> session.requestWrite(handle, requestedValue));
        return true;
    }

    boolean sendAacpMessage(String deviceAddress, int opcode, byte[] body) {
        if (opcode < 0 || opcode > 0xff || body == null || body.length > 512) return false;
        SessionEvidence evidence;
        synchronized (lock) {
            if (!matchesConnectedDeviceLocked(deviceAddress)) return false;
            evidence = activeEvidence;
        }
        if (evidence == null) return false;
        byte[] requestedBody = java.util.Arrays.copyOf(body, body.length);
        executor.execute(() -> evidence.sendMessage(opcode, requestedBody));
        return true;
    }

    private boolean matchesConnectedDeviceLocked(String deviceAddress) {
        return requested && state.connected && (deviceAddress.isEmpty()
                || deviceAddress.equalsIgnoreCase(state.deviceAddress));
    }

    boolean matchesConnectedDevice(String deviceAddress) {
        synchronized (lock) {
            return matchesConnectedDeviceLocked(deviceAddress == null ? "" : deviceAddress);
        }
    }

    private void connectAndRead(int generation) {
        int originalPriority = Process.getThreadPriority(Process.myTid());
        try {
            // This thread blocks in the L2CAP read for almost all of its lifetime. Display-class
            // priority lets it drain each tiny pose SDU promptly under CPU pressure without using
            // real-time/audio priority or competing with AudioFlinger.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            connectAndReadAtHighPriority(generation);
        } finally {
            Process.setThreadPriority(originalPriority);
        }
    }

    private void connectAndReadAtHighPriority(int generation) {
        AudioOutputIdentity currentOutput;
        Sink currentSink;
        synchronized (lock) {
            if (!requested || generation != sessionGeneration) return;
            currentOutput = output;
            currentSink = sink;
        }
        BluetoothSocket connected = null;
        SessionEvidence evidence = null;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(
                    currentOutput.getBluetoothTransportAddress());
            // The public createL2capChannel() is LE-only, while AACP uses a Bluetooth Classic
            // fixed PSM. createL2capSocket() is part of the Bluetooth module implementation API,
            // so platform_apis alone does not put it on this app's compile classpath.
            connected = createClassicL2capSocket(device, AacpProtocol.PSM);
            synchronized (lock) {
                if (!requested || generation != sessionGeneration
                        || output != currentOutput) return;
                socket = connected;
            }
            connected.connect();
            Log.i(TAG, "AACP channel connected for active A2DP output");

            OutputStream writer = connected.getOutputStream();
            initializeAacpSession(writer);
            updateConnectedState(currentOutput);

            SessionEvidence establishedEvidence =
                    new SessionEvidence(writer, connected, currentOutput, currentSink,
                            generation);
            evidence = establishedEvidence;
            synchronized (lock) {
                if (isCurrent(currentOutput, connected, generation)) {
                    activeEvidence = establishedEvidence;
                }
            }
            establishedEvidence.setControlValue(0x39,
                    new byte[] {(byte) requestedStemConfig});
            executor.execute(() -> connectAtt(currentOutput, generation, establishedEvidence));
            // Model-information notifications are optional and are not a prerequisite for the
            // devmotion6 service. Waiting for one before sending the start command deadlocks on
            // current AirPods Pro 3 firmware, which only emits motion after that command.
            if (isPoseStreamingRequested(currentOutput, generation)) {
                establishedEvidence.maybeStartProbe();
            }
            BluetoothSocket sessionSocket = connected;
            ScheduledFuture<?> validationTimeout = executor.schedule(() -> {
                if (isPoseStreamingRequested(currentOutput, generation)
                        && !establishedEvidence.hasStream()
                        && isCurrent(currentOutput, sessionSocket, generation)) {
                    currentSink.onProviderError(PROVIDER_ID, currentOutput.deviceKey,
                            "No AirPods motion stream after AACP initialization");
                    closeQuietly(sessionSocket);
                }
            }, 8, TimeUnit.SECONDS);
            InputStream reader = connected.getInputStream();
            byte[] buffer = new byte[4096];
            try {
                while (isCurrent(currentOutput, connected, generation)) {
                    int count = reader.read(buffer);
                    if (count < 0) throw new IOException("AACP peer closed");
                    if (count == 0) continue;
                    byte[] packet = new byte[count];
                    System.arraycopy(buffer, 0, packet, 0, count);
                    establishedEvidence.accept(packet);
                }
            } finally {
                validationTimeout.cancel(false);
            }
        } catch (IOException | RuntimeException exception) {
            if (isRequested(currentOutput, generation)) {
                Log.w(TAG, "AACP session ended: " + exception.getClass().getSimpleName());
                currentSink.onProviderError(PROVIDER_ID, currentOutput.deviceKey,
                        "AACP session unavailable");
            }
        } finally {
            closeAttForSession(currentOutput, generation);
            synchronized (lock) {
                if (socket == connected) socket = null;
                if (activeEvidence == evidence) activeEvidence = null;
            }
            if (evidence != null) evidence.closeSession();
            closeQuietly(connected);
            if (isRequested(currentOutput, generation) && !rejectedPeer) {
                updateDisconnectedState();
                // Do not carry a stale "one bud removed" gate across an AACP reconnect. Unknown
                // permits a fresh probe; the next ear notification will immediately restore the
                // real two-bud policy and stop motion again when necessary.
                currentSink.onWearStateChanged(NormalizedHeadPoseProvider.WearState.UNKNOWN);
                // Status-only sessions deliberately never start RTBuddy. Do not blacklist a
                // valid accessory merely because head tracking was disabled for this session.
                if (isPoseStreamingRequested(currentOutput, generation)
                        && (evidence == null || !evidence.hasStream())) {
                    synchronized (lock) {
                        if (++unvalidatedFailures >= 3) rejectedPeer = true;
                    }
                    if (rejectedPeer) {
                        currentSink.onProviderError(PROVIDER_ID, currentOutput.deviceKey,
                                "Active A2DP peer did not expose AACP");
                    }
                } else if (evidence != null) {
                    synchronized (lock) {
                        unvalidatedFailures = 0;
                    }
                }
                if (!rejectedPeer) scheduleRetry(generation);
            }
        }
    }

    private void scheduleRetry(int generation) {
        long delay;
        synchronized (lock) {
            if (!requested || generation != sessionGeneration) return;
            delay = RETRY_SECONDS[Math.min(retryIndex++, RETRY_SECONDS.length - 1)];
        }
        executor.schedule(() -> connectAndRead(generation), delay, TimeUnit.SECONDS);
    }

    private static BluetoothSocket createClassicL2capSocket(BluetoothDevice device, int psm)
            throws IOException {
        return createClassicL2capSocket(device, psm, PODS_UUID);
    }

    private static BluetoothSocket createClassicL2capSocket(BluetoothDevice device, int psm,
            ParcelUuid serviceUuid) throws IOException {
        // Supplying the proprietary service UUID matches the socket setup used by Apple's AACP
        // peers. A fixed-PSM socket with a zero UUID connects on current Android Bluetooth stacks,
        // but some AirPods firmware then accepts no AACP commands on that channel.
        try {
            Constructor<BluetoothSocket> constructor = BluetoothSocket.class
                    .getDeclaredConstructor(BluetoothDevice.class, int.class, boolean.class,
                            boolean.class, int.class, ParcelUuid.class);
            constructor.setAccessible(true);
            return constructor.newInstance(device, 3 /* TYPE_L2CAP */, true, true, psm,
                    serviceUuid);
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException
                | InvocationTargetException | RuntimeException exception) {
            Log.w(TAG, "UUID-backed L2CAP socket unavailable; using fixed-PSM API", exception);
        }
        try {
            Method method = BluetoothDevice.class.getMethod("createL2capSocket", int.class);
            Object result = method.invoke(device, psm);
            if (result instanceof BluetoothSocket) return (BluetoothSocket) result;
            throw new IOException("Bluetooth Classic L2CAP API returned no socket");
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IOException("Bluetooth Classic L2CAP API is unavailable", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IOException("Bluetooth Classic L2CAP socket creation failed", cause);
        }
    }

    private void connectAtt(AudioOutputIdentity currentOutput, int generation,
            SessionEvidence expectedEvidence) {
        BluetoothSocket connected = null;
        AttSession session = null;
        try {
            synchronized (lock) {
                if (!requested || generation != sessionGeneration || output != currentOutput
                        || activeEvidence != expectedEvidence || attSocket != null) {
                    return;
                }
            }
            BluetoothDevice device = adapter.getRemoteDevice(
                    currentOutput.getBluetoothTransportAddress());
            connected = createClassicL2capSocket(device, ATT_PSM, ZERO_UUID);
            synchronized (lock) {
                if (!requested || generation != sessionGeneration || output != currentOutput
                        || activeEvidence != expectedEvidence || attSocket != null) {
                    closeQuietly(connected);
                    return;
                }
                attSocket = connected;
            }
            connected.connect();
            AttSession established = new AttSession(connected.getOutputStream(), connected,
                    currentOutput, generation);
            session = established;
            synchronized (lock) {
                if (!isCurrentAttLocked(currentOutput, connected, generation)) return;
                activeAttSession = established;
            }
            updateAttConnectedState(true);
            established.initialize();
            established.readLoop(connected.getInputStream());
        } catch (IOException | RuntimeException exception) {
            if (isRequested(currentOutput, generation)) {
                Log.i(TAG, "AirPods ATT channel unavailable: "
                        + exception.getClass().getSimpleName());
            }
        } finally {
            synchronized (lock) {
                if (attSocket == connected) attSocket = null;
                if (activeAttSession == session) activeAttSession = null;
            }
            if (session != null) session.closeSession();
            closeQuietly(connected);
            boolean retry;
            synchronized (lock) {
                retry = requested && generation == sessionGeneration && output == currentOutput
                        && activeEvidence == expectedEvidence && attSocket == null;
            }
            if (retry) {
                updateAttConnectedState(false);
                executor.schedule(() -> connectAtt(currentOutput, generation, expectedEvidence),
                        5, TimeUnit.SECONDS);
            }
        }
    }

    private void closeAttForSession(AudioOutputIdentity expected, int generation) {
        BluetoothSocket closing = null;
        AttSession session = null;
        synchronized (lock) {
            if (output == expected && generation == sessionGeneration) {
                closing = attSocket;
                session = activeAttSession;
                attSocket = null;
                activeAttSession = null;
            }
        }
        if (session != null) session.closeSession();
        closeQuietly(closing);
    }

    private boolean isCurrentAttLocked(AudioOutputIdentity expected,
            BluetoothSocket expectedSocket, int generation) {
        return requested && generation == sessionGeneration && output == expected
                && attSocket == expectedSocket;
    }

    private void initializeAacpSession(OutputStream writer) throws IOException {
        // AirPods are timing-sensitive here. Keep each control request in its own flushed write,
        // then repeat the initial handshake once before probing RTBuddy. This is the sequence used
        // by the previously working integration and by independent LibrePods implementations.
        writePacket(writer, AacpProtocol.handshake());
        writePacket(writer, AacpProtocol.featureFlags());
        writePacket(writer, AacpProtocol.notificationRequest());
        writePacket(writer, AacpProtocol.proximityKeysRequest());

        writePacket(writer, AacpProtocol.handshake());
        SystemClock.sleep(INITIALIZATION_STEP_DELAY_MS);
        writePacket(writer, AacpProtocol.featureFlags());
        SystemClock.sleep(INITIALIZATION_STEP_DELAY_MS);
        writePacket(writer, AacpProtocol.notificationRequest());
        SystemClock.sleep(INITIALIZATION_STEP_DELAY_MS);
        writePacket(writer, AacpProtocol.proximityKeysRequest());
        Log.i(TAG, "AACP initialization sequence sent");
    }

    private static boolean looksLikeAirPodsPro(AudioOutputIdentity output,
            BluetoothDevice device) {
        if (containsAirPodsPro(output.displayName) || containsAirPodsPro(device.getName())) {
            return true;
        }
        return containsAirPodsPro(metadata(device, BluetoothDevice.METADATA_MODEL_NAME))
                && containsApple(metadata(device, BluetoothDevice.METADATA_MANUFACTURER_NAME));
    }

    private static boolean hasPodsService(BluetoothDevice device) {
        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null) return false;
            for (ParcelUuid uuid : uuids) {
                if (PODS_UUID.equals(uuid)) return true;
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not read Bluetooth service UUIDs", exception);
        }
        return false;
    }

    private static String metadata(BluetoothDevice device, int key) {
        try {
            byte[] value = device.getMetadata(key);
            return value == null ? "" : new String(value, StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static boolean containsAirPodsPro(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT)
                .contains("airpods pro");
    }

    private static boolean containsApple(String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains("apple");
    }

    private boolean isRequested(AudioOutputIdentity expected, int generation) {
        synchronized (lock) {
            return requested && generation == sessionGeneration && output == expected;
        }
    }

    private boolean isCurrent(AudioOutputIdentity expected, BluetoothSocket expectedSocket,
            int generation) {
        synchronized (lock) {
            return requested && generation == sessionGeneration && output == expected
                    && socket == expectedSocket;
        }
    }

    private boolean isPoseStreamingRequested(AudioOutputIdentity expected, int generation) {
        synchronized (lock) {
            return requested && poseStreamingRequested && generation == sessionGeneration
                    && output == expected;
        }
    }

    private static void closeQuietly(BluetoothSocket target) {
        if (target == null) return;
        try {
            target.close();
        } catch (IOException ignored) {
        }
    }

    private void writePacket(OutputStream writer, byte[] packet) throws IOException {
        synchronized (writerLock) {
            writer.write(packet);
            writer.flush();
        }
    }

    private static void logDebug(String message) {
        if (Build.IS_DEBUGGABLE) Log.d(TAG, message);
    }

    /** Minimal, bounded ATT bearer. Characteristic semantics remain in HaotianAirPods. */
    private final class AttSession {
        private final OutputStream writer;
        private final BluetoothSocket sessionSocket;
        private final AudioOutputIdentity sessionOutput;
        private final int sessionGeneration;
        private final Object attWriterLock = new Object();
        private final ArrayDeque<AttOperation> operations = new ArrayDeque<>();
        private AttOperation inFlight;
        private ScheduledFuture<?> requestTimeout;
        private final AtomicBoolean closed = new AtomicBoolean();

        AttSession(OutputStream writer, BluetoothSocket socket, AudioOutputIdentity output,
                int generation) {
            this.writer = writer;
            sessionSocket = socket;
            sessionOutput = output;
            sessionGeneration = generation;
        }

        void initialize() {
            // These are opaque handles requested by the companion. Notification descriptors are
            // adjacent to the two values that expose them on current AirPods firmware.
            requestRead(0x18);
            requestRead(0x1b);
            requestRead(0x2a);
            requestWrite(0x19, new byte[] {0x01});
            requestWrite(0x2b, new byte[] {0x01});
        }

        void requestRead(int handle) {
            if (closed.get() || handle < 1 || handle > 0xffff) return;
            synchronized (attWriterLock) {
                if (closed.get() || hasQueuedReadLocked(handle) || operations.size() >= 64) return;
                operations.addLast(new AttOperation(0x0a, handle, new byte[0]));
                sendNextLocked();
            }
        }

        void requestWrite(int handle, byte[] value) {
            if (closed.get() || handle < 1 || handle > 0xffff
                    || value == null || value.length > MAX_ATT_VALUE_BYTES) return;
            synchronized (attWriterLock) {
                if (closed.get() || operations.size() >= 64) return;
                operations.addLast(new AttOperation(0x12, handle,
                        java.util.Arrays.copyOf(value, value.length)));
                sendNextLocked();
            }
        }

        private boolean hasQueuedReadLocked(int handle) {
            if (inFlight != null && inFlight.opcode == 0x0a && inFlight.handle == handle) {
                return true;
            }
            for (AttOperation operation : operations) {
                if (operation.opcode == 0x0a && operation.handle == handle) return true;
            }
            return false;
        }

        private void sendNextLocked() {
            if (closed.get() || inFlight != null) return;
            inFlight = operations.pollFirst();
            if (inFlight == null) return;
            byte[] pdu = new byte[3 + inFlight.value.length];
            pdu[0] = (byte) inFlight.opcode;
            pdu[1] = (byte) inFlight.handle;
            pdu[2] = (byte) (inFlight.handle >>> 8);
            System.arraycopy(inFlight.value, 0, pdu, 3, inFlight.value.length);
            try {
                writer.write(pdu);
                writer.flush();
                AttOperation sent = inFlight;
                requestTimeout = executor.schedule(() -> {
                    synchronized (attWriterLock) {
                        if (!closed.get() && inFlight == sent) {
                            Log.i(TAG, "ATT request timed out: opcode=" + sent.opcode
                                    + ", handle=" + sent.handle);
                            closeQuietly(sessionSocket);
                        }
                    }
                }, 2, TimeUnit.SECONDS);
            } catch (IOException exception) {
                inFlight = null;
                closeQuietly(sessionSocket);
            }
        }

        void readLoop(InputStream reader) throws IOException {
            byte[] buffer = new byte[4096];
            while (!closed.get() && isCurrentAtt(sessionOutput, sessionSocket,
                    sessionGeneration)) {
                int count = reader.read(buffer);
                if (count < 0) throw new IOException("ATT peer closed");
                if (count == 0) continue;
                accept(java.util.Arrays.copyOf(buffer, count));
            }
        }

        private void accept(byte[] pdu) throws IOException {
            if (pdu.length < 1) return;
            int opcode = pdu[0] & 0xff;
            if (opcode == 0x0b) {
                int handle = -1;
                synchronized (attWriterLock) {
                    if (inFlight != null && inFlight.opcode == 0x0a) {
                        handle = inFlight.handle;
                        cancelRequestTimeoutLocked();
                        inFlight = null;
                        sendNextLocked();
                    }
                }
                if (handle >= 0) {
                    updateAttValueState(handle,
                            java.util.Arrays.copyOfRange(pdu, 1, pdu.length));
                }
            } else if (opcode == 0x13) {
                int handle = -1;
                byte[] writtenValue = null;
                synchronized (attWriterLock) {
                    if (inFlight != null && inFlight.opcode == 0x12) {
                        handle = inFlight.handle;
                        writtenValue = java.util.Arrays.copyOf(
                                inFlight.value, inFlight.value.length);
                        cancelRequestTimeoutLocked();
                        inFlight = null;
                        if (!hasQueuedReadLocked(handle)) {
                            operations.addFirst(new AttOperation(0x0a, handle, new byte[0]));
                        }
                        sendNextLocked();
                    }
                }
                if (handle >= 0 && writtenValue != null) {
                    updateAttValueState(handle, writtenValue);
                }
            } else if ((opcode == 0x1b || opcode == 0x1d) && pdu.length >= 3) {
                int handle = (pdu[1] & 0xff) | ((pdu[2] & 0xff) << 8);
                updateAttValueState(handle,
                        java.util.Arrays.copyOfRange(pdu, 3, pdu.length));
                if (opcode == 0x1d) writeAttConfirmation();
            } else if (opcode == 0x01 && pdu.length >= 5) {
                int requestOpcode = pdu[1] & 0xff;
                int handle = (pdu[2] & 0xff) | ((pdu[3] & 0xff) << 8);
                synchronized (attWriterLock) {
                    if (inFlight != null && inFlight.opcode == requestOpcode
                            && inFlight.handle == handle) {
                        cancelRequestTimeoutLocked();
                        inFlight = null;
                        sendNextLocked();
                    }
                }
                logDebug("ATT request rejected: opcode=" + requestOpcode
                        + ", handle=" + handle + ", error=" + (pdu[4] & 0xff));
            }
        }

        private void writeAttConfirmation() throws IOException {
            synchronized (attWriterLock) {
                if (closed.get()) return;
                writer.write(new byte[] {0x1e});
                writer.flush();
            }
        }

        void closeSession() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (attWriterLock) {
                cancelRequestTimeoutLocked();
                operations.clear();
                inFlight = null;
            }
            closeQuietly(sessionSocket);
        }

        private void cancelRequestTimeoutLocked() {
            if (requestTimeout != null) requestTimeout.cancel(false);
            requestTimeout = null;
        }

        private final class AttOperation {
            final int opcode;
            final int handle;
            final byte[] value;

            AttOperation(int opcode, int handle, byte[] value) {
                this.opcode = opcode;
                this.handle = handle;
                this.value = value;
            }
        }
    }

    private boolean isCurrentAtt(AudioOutputIdentity expected, BluetoothSocket expectedSocket,
            int generation) {
        synchronized (lock) {
            return isCurrentAttLocked(expected, expectedSocket, generation);
        }
    }

    private final class SessionEvidence {
        private final OutputStream writer;
        private final BluetoothSocket sessionSocket;
        private final AudioOutputIdentity sessionOutput;
        private final Sink sessionSink;
        private final int sessionGeneration;
        private final Set<Integer> discoveredServices =
                Collections.synchronizedSet(new LinkedHashSet<>());
        private final Set<Integer> attemptedServices =
                Collections.synchronizedSet(new LinkedHashSet<>());
        private final AtomicBoolean streaming = new AtomicBoolean();
        private final AtomicBoolean sessionClosed = new AtomicBoolean();
        private final AirPodsMotionDecoder decoder;
        private volatile String model = "";
        private boolean receivedPacket;
        private volatile boolean streamValidated;
        private volatile long lastMotionPacketNanos;
        private int probeIndex;
        private volatile ScheduledFuture<?> scheduledProbe;
        private volatile ScheduledFuture<?> streamWatchdog;

        SessionEvidence(OutputStream writer, BluetoothSocket socket,
                AudioOutputIdentity output, Sink sink, int generation) {
            this.writer = writer;
            sessionSocket = socket;
            sessionOutput = output;
            sessionSink = sink;
            sessionGeneration = generation;
            decoder = new AirPodsMotionDecoder(output.deviceKey, PROVIDER_ID);
        }

        void accept(byte[] packet) throws IOException {
            final long packetReceivedNanos = SystemClock.elapsedRealtimeNanos();
            if (sessionClosed.get()) return;
            if (!AacpProtocol.isAacpPacket(packet)) return;
            if (!receivedPacket) {
                receivedPacket = true;
                Log.w(TAG, "Received first AACP packet: opcode="
                        + (packet.length > 4 ? packet[4] & 0xff : -1)
                        + ", length=" + packet.length);
            }
            AacpProtocol.DeviceInformation information = AacpProtocol.deviceInformation(packet);
            if (information != null && !information.modelNumber.isEmpty()) {
                model = information.modelNumber;
                if (!AacpProtocol.isAirPodsPro3Model(model)) {
                    rejectedPeer = true;
                    throw new IOException("AACP peer is not AirPods Pro 3");
                }
                updateDeviceInformation(information);
                logDebug("AirPods Pro 3 model evidence accepted");
            }
            int[][] batteries = AacpProtocol.batteryFromNotification(packet);
            if (batteries != null) {
                int[] leftRightWear = updateBatteryState(batteries);
                if (leftRightWear != null) {
                    sessionSink.onWearStateChanged(new NormalizedHeadPoseProvider.WearState(
                            true, leftRightWear[0] == AirPodsState.WEAR_IN_EAR,
                            leftRightWear[1] == AirPodsState.WEAR_IN_EAR));
                }
            }
            int[] wear = AacpProtocol.earStateFromNotification(packet);
            if (wear != null) {
                int[] leftRightWear = updateWearState(wear[0], wear[1]);
                sessionSink.onWearStateChanged(new NormalizedHeadPoseProvider.WearState(
                        true, leftRightWear[0] == AirPodsState.WEAR_IN_EAR,
                        leftRightWear[1] == AirPodsState.WEAR_IN_EAR));
            }
            AacpProtocol.ControlCommand control =
                    AacpProtocol.controlCommandFromNotification(packet);
            if (control != null) updateControlState(control.identifier, control.value);
            AacpProtocol.StemPress stemPress = AacpProtocol.stemPressFromNotification(packet);
            if (stemPress != null && stemPressListener != null) {
                stemPressListener.onStemPress(stemPress.pressType, stemPress.bud);
            }
            byte[] customEq = AacpProtocol.messageBody(
                    packet, RETAINED_AACP_MESSAGE_CUSTOM_EQ);
            if (customEq != null) {
                updateAacpMessageState(RETAINED_AACP_MESSAGE_CUSTOM_EQ, customEq);
            }
            byte[] conversationAwareness = AacpProtocol.messageBody(
                    packet, RETAINED_AACP_MESSAGE_CONVERSATION_AWARENESS);
            if (conversationAwareness != null) {
                updateAacpMessageState(
                        RETAINED_AACP_MESSAGE_CONVERSATION_AWARENESS,
                        conversationAwareness);
            }
            for (int opcode : RETAINED_AACP_TRANSPORT_MESSAGES) {
                byte[] body = AacpProtocol.messageBody(packet, opcode);
                if (body != null) updateAacpMessageState(opcode, body);
            }
            discoveredServices.addAll(AacpProtocol.discoverMotionServices(packet));

            AacpProtocol.CommandPayload command = AacpProtocol.commandPayload(packet);
            if (command != null && command.payload.length >= 58
                    && command.payload[0] == 1 && command.payload[9] == 3) {
                discoveredServices.add(command.service);
                streamValidated = true;
                if (!isPoseStreamingRequested(sessionOutput, sessionGeneration)) {
                    // A few already-buffered motion frames may arrive after the stop command.
                    // Keep parsing low-rate AACP notifications for future wear detection, but do
                    // not decode or publish disabled high-rate pose data.
                    return;
                }
                lastMotionPacketNanos = packetReceivedNanos;
                if (streaming.compareAndSet(false, true)) {
                    Log.i(TAG, "Validated AirPods motion stream on service "
                            + command.service);
                }
                retryIndex = 0;
                cancelProbe();
                startStreamWatchdog();
                // commandPayload() already performed the bounded protobuf parse above. Reusing it
                // avoids doing the same allocation-heavy parse twice for every 50 Hz pose frame.
                HeadPoseFrame frame = decoder.decode(command, packetReceivedNanos);
                if (frame != null) sessionSink.onPose(frame);
                return;
            }
            maybeStartProbe();
        }

        private boolean hasStream() {
            // Historical per-session evidence must survive teardown. Using the process-wide
            // streaming flag here caused finally{} to mark every successful session as an
            // unvalidated peer immediately after clearing that flag.
            return streamValidated;
        }

        private boolean recenter() {
            if (!streaming.get() || !hasStream()) return false;
            decoder.recenter();
            return true;
        }

        private synchronized void startStreamWatchdog() {
            if (streamWatchdog != null) return;
            streamWatchdog = executor.scheduleAtFixedRate(() -> {
                if (!isCurrent(sessionOutput, sessionSocket, sessionGeneration)) {
                    cancelStreamWatchdog();
                    return;
                }
                if (!isPoseStreamingRequested(sessionOutput, sessionGeneration)) {
                    cancelStreamWatchdog();
                    return;
                }
                long lastPacket = lastMotionPacketNanos;
                if (lastPacket == 0L || SystemClock.elapsedRealtimeNanos() - lastPacket
                        <= TimeUnit.MILLISECONDS.toNanos(STREAM_STALL_TIMEOUT_MS)) {
                    return;
                }
                // AirPods can leave the authenticated L2CAP channel open after RTBuddy motion
                // delivery has stopped. A blocking read then never fails and the old code could
                // remain permanently stale. Closing only this control socket lets the existing
                // retry path establish a fresh stream without touching A2DP audio.
                streaming.set(false);
                sessionSink.onProviderError(PROVIDER_ID, sessionOutput.deviceKey,
                        "AirPods motion stream stalled; reconnecting");
                cancelStreamWatchdog();
                closeQuietly(sessionSocket);
            }, STREAM_WATCHDOG_INTERVAL_MS, STREAM_WATCHDOG_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        }

        private void maybeStartProbe() throws IOException {
            if (scheduledProbe != null
                    || !isPoseStreamingRequested(sessionOutput, sessionGeneration)) return;
            scheduledProbe = executor.scheduleAtFixedRate(() -> {
                if (!isPoseStreamingRequested(sessionOutput, sessionGeneration)
                        || streaming.get()
                        || !isCurrent(sessionOutput, sessionSocket, sessionGeneration)) {
                    cancelProbe();
                    return;
                }
                List<Integer> candidates;
                synchronized (discoveredServices) {
                    candidates = new ArrayList<>(discoveredServices);
                }
                // Prefer a dynamically advertised service, then the real devmotion6 service at
                // 50 Hz. Service 14 is the older 25 Hz exchange and is deliberately last-resort.
                addUnique(candidates, AacpProtocol.SERVICE_DEVMOTION6);
                addUnique(candidates, AacpProtocol.SERVICE_OBSERVED_DYNAMIC);
                addUnique(candidates, AacpProtocol.SERVICE_LEGACY_MOTION);
                if (probeIndex >= candidates.size()) {
                    cancelProbe();
                    sessionSink.onProviderError(PROVIDER_ID, sessionOutput.deviceKey,
                            "No devmotion6 stream from validated AirPods Pro 3");
                    closeQuietly(sessionSocket);
                    return;
                }
                int service = candidates.get(probeIndex++);
                try {
                    boolean advertised;
                    synchronized (discoveredServices) {
                        advertised = discoveredServices.contains(service);
                    }
                    boolean highRate = advertised
                            || service == AacpProtocol.SERVICE_DEVMOTION6
                            || service == AacpProtocol.SERVICE_OBSERVED_DYNAMIC;
                    synchronized (writerLock) {
                        if (sessionClosed.get()) return;
                        attemptedServices.add(service);
                        writer.write(AacpProtocol.motionSetting(
                                commandSequence.incrementAndGet(), service, true, highRate));
                        writer.flush();
                    }
                    Log.i(TAG, "Requested RTBuddy motion service " + service + " at "
                            + (highRate ? "50 Hz" : "25 Hz"));
                } catch (IOException exception) {
                    closeQuietly(sessionSocket);
                }
            }, 0, 1200, TimeUnit.MILLISECONDS);
        }

        void cancelProbe() {
            ScheduledFuture<?> future = scheduledProbe;
            if (future != null) future.cancel(false);
            scheduledProbe = null;
        }

        private synchronized void cancelStreamWatchdog() {
            ScheduledFuture<?> future = streamWatchdog;
            if (future != null) future.cancel(false);
            streamWatchdog = null;
        }

        void cancelScheduledTasks() {
            cancelProbe();
            cancelStreamWatchdog();
        }

        void setPoseStreamingEnabled(boolean enabled) {
            if (sessionClosed.get()) return;
            if (enabled) {
                streaming.set(false);
                probeIndex = 0;
                try {
                    maybeStartProbe();
                } catch (IOException exception) {
                    closeQuietly(sessionSocket);
                }
                return;
            }
            streaming.set(false);
            cancelScheduledTasks();
            sendMotionState(false);
        }

        void setControlValue(int identifier, byte[] value) {
            if (sessionClosed.get()) return;
            try {
                writePacket(writer, AacpProtocol.controlCommand(identifier, value));
                // Most controls do not echo immediately. Keep the companion state consistent
                // with a successfully written request; a later accessory notification remains
                // authoritative and can replace it.
                updateControlState(identifier, value);
            } catch (IOException | RuntimeException exception) {
                sessionSink.onProviderError(PROVIDER_ID, sessionOutput.deviceKey,
                        "Could not change AirPods control " + identifier);
            }
        }

        void rename(String name) {
            if (sessionClosed.get()) return;
            try {
                writePacket(writer, AacpProtocol.rename(name));
            } catch (IOException | RuntimeException exception) {
                sessionSink.onProviderError(PROVIDER_ID, sessionOutput.deviceKey,
                        "Could not rename AirPods");
            }
        }

        void sendMessage(int opcode, byte[] body) {
            if (sessionClosed.get()) return;
            try {
                writePacket(writer, AacpProtocol.message(opcode, body));
            } catch (IOException | RuntimeException exception) {
                sessionSink.onProviderError(PROVIDER_ID, sessionOutput.deviceKey,
                        "Could not send AirPods AACP message " + opcode);
            }
        }

        void closeSession() {
            streaming.set(false);
            cancelScheduledTasks();
            if (!sessionClosed.compareAndSet(false, true)) return;
            sendMotionState(false);
        }

        private void sendMotionState(boolean enabled) {
            if (attemptedServices.isEmpty()) return;
            try {
                List<Integer> services;
                synchronized (attemptedServices) {
                    services = new ArrayList<>(attemptedServices);
                }
                synchronized (writerLock) {
                    for (int service : services) {
                        writer.write(AacpProtocol.motionSetting(
                                commandSequence.incrementAndGet(), service, enabled));
                    }
                    writer.flush();
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }

        private void addUnique(List<Integer> values, int value) {
            if (!values.contains(value)) values.add(value);
        }
    }

    private void updateConnectedState(AudioOutputIdentity output) {
        AirPodsState snapshot;
        synchronized (lock) {
            state.connected = true;
            state.deviceKey = output.deviceKey;
            // Settings opens the companion with CachedBluetoothDevice's transport address. Keep
            // the public control-state address in that same address domain; the identity address
            // remains reserved for the head-tracker UUID used by AudioService.
            String transportAddress = output.getBluetoothTransportAddress();
            state.deviceAddress = transportAddress.isEmpty()
                    ? output.getBluetoothIdentityAddress() : transportAddress;
            state.displayName = output.displayName;
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
    }

    private void updateDisconnectedState() {
        AirPodsState snapshot;
        synchronized (lock) {
            if (!state.connected && state.leftWear == AirPodsState.WEAR_UNKNOWN
                    && state.rightWear == AirPodsState.WEAR_UNKNOWN) {
                return;
            }
            state.connected = false;
            state.leftBattery = AirPodsState.BATTERY_UNKNOWN;
            state.rightBattery = AirPodsState.BATTERY_UNKNOWN;
            state.caseBattery = AirPodsState.BATTERY_UNKNOWN;
            state.leftBatteryStatus = AirPodsState.BATTERY_STATUS_UNKNOWN;
            state.rightBatteryStatus = AirPodsState.BATTERY_STATUS_UNKNOWN;
            state.caseBatteryStatus = AirPodsState.BATTERY_STATUS_UNKNOWN;
            state.leftWear = AirPodsState.WEAR_UNKNOWN;
            state.rightWear = AirPodsState.WEAR_UNKNOWN;
            primaryPodComponent = 0;
            primaryWear = AirPodsState.WEAR_UNKNOWN;
            secondaryWear = AirPodsState.WEAR_UNKNOWN;
            state.noiseControlMode = AirPodsState.NOISE_UNKNOWN;
            state.controlIdentifiers = new int[0];
            state.controlValues = new byte[0][];
            state.attConnected = false;
            state.attHandles = new int[0];
            state.attValues = new byte[0][];
            state.aacpMessageOpcodes = new int[0];
            state.aacpMessageValues = new byte[0][];
            state.aacpMessageSequences = new long[0];
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
    }

    private void updateAttConnectedState(boolean connected) {
        AirPodsState snapshot;
        synchronized (lock) {
            if (state.attConnected == connected && (connected || state.attHandles.length == 0)) {
                return;
            }
            state.attConnected = connected;
            if (!connected) {
                state.attHandles = new int[0];
                state.attValues = new byte[0][];
            }
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
    }

    private void updateAttValueState(int handle, byte[] value) {
        if (value.length > MAX_ATT_VALUE_BYTES) return;
        AirPodsState snapshot;
        synchronized (lock) {
            int count = Math.min(state.attHandles.length, state.attValues.length);
            int index = -1;
            for (int i = 0; i < count; i++) {
                if (state.attHandles[i] == handle) {
                    index = i;
                    break;
                }
            }
            byte[] copied = java.util.Arrays.copyOf(value, value.length);
            if (index >= 0) {
                state.attValues[index] = copied;
            } else {
                state.attHandles = java.util.Arrays.copyOf(state.attHandles, count + 1);
                state.attValues = java.util.Arrays.copyOf(state.attValues, count + 1);
                state.attHandles[count] = handle;
                state.attValues[count] = copied;
            }
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
    }

    private void updateAacpMessageState(int opcode, byte[] value) {
        if (value.length > 512) return;
        AirPodsState snapshot;
        synchronized (lock) {
            int count = Math.min(state.aacpMessageOpcodes.length,
                    state.aacpMessageValues.length);
            int index = -1;
            for (int i = 0; i < count; i++) {
                if (state.aacpMessageOpcodes[i] == opcode) {
                    index = i;
                    break;
                }
            }
            byte[] copied = java.util.Arrays.copyOf(value, value.length);
            if (index >= 0) {
                state.aacpMessageValues[index] = copied;
                if (state.aacpMessageSequences.length < count) {
                    state.aacpMessageSequences = java.util.Arrays.copyOf(
                            state.aacpMessageSequences, count);
                }
                state.aacpMessageSequences[index]++;
            } else if (count < 32) {
                state.aacpMessageOpcodes = java.util.Arrays.copyOf(
                        state.aacpMessageOpcodes, count + 1);
                state.aacpMessageValues = java.util.Arrays.copyOf(
                        state.aacpMessageValues, count + 1);
                state.aacpMessageSequences = java.util.Arrays.copyOf(
                        state.aacpMessageSequences, count + 1);
                state.aacpMessageOpcodes[count] = opcode;
                state.aacpMessageValues[count] = copied;
                state.aacpMessageSequences[count] = 1;
            }
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
    }

    private void updateDeviceInformation(AacpProtocol.DeviceInformation information) {
        AirPodsState snapshot;
        synchronized (lock) {
            if (!information.name.isEmpty()) state.displayName = information.name;
            state.modelNumber = information.modelNumber;
            state.manufacturer = information.manufacturer;
            state.serialNumber = information.serialNumber;
            state.leftSerialNumber = information.leftSerialNumber;
            state.rightSerialNumber = information.rightSerialNumber;
            state.version1 = information.version1;
            state.version2 = information.version2;
            state.version3 = information.version3;
            state.hardwareRevision = information.hardwareRevision;
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
    }

    private int[] updateWearState(int primary, int secondary) {
        AirPodsState snapshot;
        int[] leftRightWear;
        synchronized (lock) {
            primaryWear = primary;
            secondaryWear = secondary;
            applyWearMappingLocked();
            leftRightWear = new int[] {state.leftWear, state.rightWear};
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
        return leftRightWear;
    }

    private void applyWearMappingLocked() {
        if (primaryPodComponent == BATTERY_COMPONENT_LEFT) {
            state.leftWear = primaryWear;
            state.rightWear = secondaryWear;
        } else if (primaryPodComponent == BATTERY_COMPONENT_RIGHT) {
            state.leftWear = secondaryWear;
            state.rightWear = primaryWear;
        } else {
            state.leftWear = AirPodsState.WEAR_UNKNOWN;
            state.rightWear = AirPodsState.WEAR_UNKNOWN;
        }
    }

    private void updateControlState(int identifier, byte[] value) {
        AirPodsState snapshot;
        synchronized (lock) {
            int count = Math.min(state.controlIdentifiers.length, state.controlValues.length);
            int index = -1;
            for (int i = 0; i < count; i++) {
                if (state.controlIdentifiers[i] == identifier) {
                    index = i;
                    break;
                }
            }
            byte[] copied = java.util.Arrays.copyOf(value, value.length);
            if (index >= 0) {
                state.controlValues[index] = copied;
            } else {
                state.controlIdentifiers = java.util.Arrays.copyOf(
                        state.controlIdentifiers, count + 1);
                state.controlValues = java.util.Arrays.copyOf(state.controlValues, count + 1);
                state.controlIdentifiers[count] = identifier;
                state.controlValues[count] = copied;
            }
            if (identifier == 0x0d && value.length > 0) {
                int mode = value[0] & 0xff;
                state.noiseControlMode = mode >= AirPodsState.NOISE_OFF
                        && mode <= AirPodsState.NOISE_ADAPTIVE
                        ? mode : AirPodsState.NOISE_UNKNOWN;
            }
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
    }

    private int[] updateBatteryState(int[][] records) {
        AirPodsState snapshot;
        int[] leftRightWear;
        synchronized (lock) {
            for (int[] record : records) {
                if (record[0] == BATTERY_COMPONENT_LEFT
                        || record[0] == BATTERY_COMPONENT_RIGHT) {
                    primaryPodComponent = record[0];
                    break;
                }
            }
            for (int[] record : records) {
                int component = record[0];
                int level = record[1] <= 100 ? record[1] : AirPodsState.BATTERY_UNKNOWN;
                if (component == BATTERY_COMPONENT_LEFT) {
                    state.leftBattery = level;
                    state.leftBatteryStatus = record[2];
                } else if (component == BATTERY_COMPONENT_RIGHT) {
                    state.rightBattery = level;
                    state.rightBatteryStatus = record[2];
                } else if (component == 8) {
                    state.caseBattery = level;
                    state.caseBatteryStatus = record[2];
                }
            }
            applyWearMappingLocked();
            leftRightWear = primaryWear == AirPodsState.WEAR_UNKNOWN
                    ? null : new int[] {state.leftWear, state.rightWear};
            touchStateLocked();
            snapshot = new AirPodsState(state);
        }
        dispatchState(snapshot);
        return leftRightWear;
    }

    private void touchStateLocked() {
        state.updatedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
    }

    private void dispatchState(AirPodsState snapshot) {
        if (stateListener != null) stateListener.onStateChanged(snapshot);
    }
}
