/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.xiaomi.haotian.audio.provider.HeadphoneOutput;
import org.xiaomi.haotian.audio.provider.HeadphoneWearState;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProvider;
import org.xiaomi.haotian.audio.provider.IHeadphonePoseProviderCallback;

import java.io.IOException;

/** Adapts a separately packaged headphone companion to the in-process provider registry. */
final class RemoteHeadPoseProvider implements NormalizedHeadPoseProvider {
    interface AvailabilityListener {
        void onProviderAvailabilityChanged();
    }

    private static final String TAG = "HaotianHeadPose";
    private static final int API_VERSION = 1;

    private final Context context;
    private final ComponentName component;
    private final HeadTrackerSharedMemorySink poseSink;
    private final AvailabilityListener availabilityListener;
    private final Object lock = new Object();
    private IHeadphonePoseProvider remote;
    private Sink sink;
    private AudioOutputIdentity output;
    private boolean bound;
    private boolean started;

    private final IHeadphonePoseProviderCallback callback =
            new IHeadphonePoseProviderCallback.Stub() {
                @Override
                public void onWearStateChanged(String deviceKey, int leftState, int rightState,
                        long timestampNanos) {
                    Sink currentSink;
                    AudioOutputIdentity currentOutput;
                    synchronized (lock) {
                        currentSink = sink;
                        currentOutput = output;
                    }
                    if (currentSink == null || currentOutput == null
                            || !currentOutput.deviceKey.equals(deviceKey)) return;
                    boolean leftWorn = leftState == HeadphoneWearState.IN_EAR;
                    boolean rightWorn = rightState == HeadphoneWearState.IN_EAR;
                    boolean explicitRemoval = isExplicitlyNotWorn(leftState)
                            || isExplicitlyNotWorn(rightState);
                    boolean known = explicitRemoval || (leftWorn && rightWorn);
                    currentSink.onWearStateChanged(
                            new NormalizedHeadPoseProvider.WearState(
                                    known, leftWorn, rightWorn));
                }

                @Override
                public void onProviderError(String deviceKey, String reason) {
                    Sink currentSink;
                    synchronized (lock) {
                        currentSink = sink;
                    }
                    if (currentSink != null) {
                        currentSink.onProviderError(getProviderId(), deviceKey, reason);
                    }
                }
            };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IHeadphonePoseProvider candidate = IHeadphonePoseProvider.Stub.asInterface(binder);
            try {
                if (candidate == null || candidate.getApiVersion() != API_VERSION) {
                    Log.w(TAG, "Ignoring incompatible headphone provider " + component);
                    return;
                }
            } catch (RemoteException exception) {
                return;
            }
            synchronized (lock) {
                remote = candidate;
            }
            availabilityListener.onProviderAvailabilityChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handleRemoteDisconnected();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            handleRemoteDisconnected();
            synchronized (lock) {
                bound = false;
            }
            bind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            handleRemoteDisconnected();
        }
    };

    RemoteHeadPoseProvider(Context context, ComponentName component,
            HeadTrackerSharedMemorySink poseSink, AvailabilityListener availabilityListener) {
        this.context = context.getApplicationContext();
        this.component = component;
        this.poseSink = poseSink;
        this.availabilityListener = availabilityListener;
        bind();
    }

    @Override
    public String getProviderId() {
        IHeadphonePoseProvider current;
        synchronized (lock) {
            current = remote;
        }
        if (current != null) {
            try {
                String id = current.getProviderId();
                if (id != null && !id.isEmpty()) return id;
            } catch (RemoteException ignored) {
            }
        }
        return component.flattenToShortString();
    }

    @Override
    public boolean supports(AudioOutputIdentity requestedOutput) {
        IHeadphonePoseProvider current;
        synchronized (lock) {
            current = remote;
        }
        if (current == null || requestedOutput == null) return false;
        try {
            return current.supports(toParcelable(requestedOutput));
        } catch (RemoteException exception) {
            return false;
        }
    }

    @Override
    public void start(AudioOutputIdentity requestedOutput, Sink requestedSink) {
        IHeadphonePoseProvider current;
        synchronized (lock) {
            current = remote;
            output = requestedOutput;
            sink = requestedSink;
            started = false;
        }
        if (current == null) {
            clearStartedState();
            throw new IllegalStateException("Provider is not connected");
        }
        ParcelFileDescriptor descriptor = poseSink.openWritablePoseBuffer(
                requestedOutput.getBluetoothTransportAddress());
        if (descriptor == null) {
            clearStartedState();
            throw new IllegalStateException("Pose ring is unavailable");
        }
        try {
            poseSink.setExternalProvider(getProviderId());
            boolean accepted = current.start(toParcelable(requestedOutput), descriptor, callback);
            if (!accepted) {
                poseSink.setExternalProvider(null);
                throw new IllegalStateException("Provider rejected output");
            }
            synchronized (lock) {
                started = true;
            }
        } catch (RemoteException | RuntimeException exception) {
            poseSink.setExternalProvider(null);
            synchronized (lock) {
                started = false;
                sink = null;
                output = null;
            }
            try {
                current.stop();
            } catch (RemoteException ignored) {
            }
            throw new IllegalStateException("Could not start remote provider", exception);
        } finally {
            try {
                descriptor.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void stop() {
        IHeadphonePoseProvider current;
        boolean shouldStop;
        synchronized (lock) {
            current = remote;
            shouldStop = started;
            started = false;
            sink = null;
            output = null;
        }
        poseSink.setExternalProvider(null);
        if (current != null && shouldStop) {
            try {
                current.setPoseStreamingEnabled(false);
                current.stop();
            } catch (RemoteException exception) {
                Log.w(TAG, "Could not stop " + component, exception);
            }
        }
    }

    @Override
    public void setPoseStreamingEnabled(boolean enabled) {
        IHeadphonePoseProvider current;
        synchronized (lock) {
            current = remote;
            if (!started) return;
        }
        try {
            current.setPoseStreamingEnabled(enabled);
        } catch (RemoteException exception) {
            throw new IllegalStateException("Could not change remote pose stream", exception);
        }
    }

    @Override
    public boolean recenter() {
        IHeadphonePoseProvider current;
        synchronized (lock) {
            current = remote;
            if (!started) return false;
        }
        try {
            return current.recenter();
        } catch (RemoteException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        stop();
        synchronized (lock) {
            remote = null;
            if (!bound) return;
            bound = false;
        }
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void bind() {
        synchronized (lock) {
            if (bound) return;
            try {
                bound = context.bindService(new Intent().setComponent(component), connection,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not bind headphone provider " + component, exception);
                bound = false;
            }
        }
    }

    private void handleRemoteDisconnected() {
        Sink currentSink;
        String deviceKey;
        synchronized (lock) {
            remote = null;
            started = false;
            currentSink = sink;
            deviceKey = output == null ? "" : output.deviceKey;
            sink = null;
            output = null;
        }
        poseSink.setExternalProvider(null);
        if (currentSink != null) {
            currentSink.onWearStateChanged(NormalizedHeadPoseProvider.WearState.UNKNOWN);
            currentSink.onProviderError(getProviderId(), deviceKey,
                    "Headphone companion disconnected");
        }
        availabilityListener.onProviderAvailabilityChanged();
    }

    private void clearStartedState() {
        synchronized (lock) {
            started = false;
            sink = null;
            output = null;
        }
    }

    private static HeadphoneOutput toParcelable(AudioOutputIdentity output) {
        return new HeadphoneOutput(output.deviceKey, output.displayName,
                output.deviceType, output.address);
    }

    private static boolean isExplicitlyNotWorn(int state) {
        return state == HeadphoneWearState.OUT_OF_EAR
                || state == HeadphoneWearState.IN_CASE
                || state == HeadphoneWearState.DISCONNECTED;
    }
}
