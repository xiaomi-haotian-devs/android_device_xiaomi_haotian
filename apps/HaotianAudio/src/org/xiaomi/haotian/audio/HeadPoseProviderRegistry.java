/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.audio;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Selects at most one pose provider for the current audio output. Called on the audio worker. */
final class HeadPoseProviderRegistry {
    private static final String TAG = "HaotianHeadPose";

    interface TrackingPolicyListener {
        void onEffectiveTrackingPolicyChanged();
    }

    private final List<NormalizedHeadPoseProvider> providers = new ArrayList<>();
    private final NormalizedHeadPoseProvider.Sink sink;
    private final TrackingPolicyListener policyListener;
    private NormalizedHeadPoseProvider activeProvider;
    private String activeDeviceKey = "";
    private int providerGeneration;
    private AudioOutputIdentity currentOutput;
    private boolean activeProviderRouted;
    private boolean trackingRequested;
    private boolean poseStreamingEnabled;
    private NormalizedHeadPoseProvider.WearState wearState =
            NormalizedHeadPoseProvider.WearState.UNKNOWN;

    private NormalizedHeadPoseProvider.Sink createProviderSink(
            NormalizedHeadPoseProvider provider, String deviceKey, int generation) {
        return new NormalizedHeadPoseProvider.Sink() {
                @Override
                public void onPose(HeadPoseFrame frame) {
                    synchronized (HeadPoseProviderRegistry.this) {
                        if (!isCurrentProviderCallbackLocked(provider, deviceKey, generation)
                                || !poseStreamingEnabled) {
                            return;
                        }
                    }
                    sink.onPose(frame);
                }

                @Override
                public void onProviderError(String providerId, String callbackDeviceKey,
                        String reason) {
                    synchronized (HeadPoseProviderRegistry.this) {
                        if (!isCurrentProviderCallbackLocked(
                                provider, callbackDeviceKey, generation)
                                || !trackingRequested) {
                            return;
                        }
                    }
                    sink.onProviderError(providerId, callbackDeviceKey, reason);
                }

                @Override
                public void onWearStateChanged(NormalizedHeadPoseProvider.WearState state) {
                    updateWearState(provider, deviceKey, generation, state);
                }
            };
    }

    HeadPoseProviderRegistry(NormalizedHeadPoseProvider.Sink sink,
            TrackingPolicyListener policyListener) {
        this.sink = sink;
        this.policyListener = policyListener;
    }

    void register(NormalizedHeadPoseProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        providers.add(provider);
    }

    synchronized void onProvidersChanged() {
        reconcileLocked();
    }

    synchronized void onActiveOutputChanged(OutputDeviceManager.ActiveOutput output) {
        AudioOutputIdentity identity = output == null ? null : new AudioOutputIdentity(output);
        currentOutput = identity;
        reconcileLocked();
    }

    synchronized void setTrackingRequested(boolean requested) {
        if (trackingRequested == requested) {
            reconcileLocked();
            return;
        }
        trackingRequested = requested;
        reconcileLocked();
    }

    synchronized boolean isWearGateOpen() {
        return wearState.permitsHeadTracking();
    }

    synchronized void stop() {
        trackingRequested = false;
        currentOutput = null;
        wearState = NormalizedHeadPoseProvider.WearState.UNKNOWN;
        stopActiveProviderLocked();
    }

    private void stopActiveProviderLocked() {
        providerGeneration++;
        if (activeProvider != null) {
            try {
                activeProvider.setPoseStreamingEnabled(false);
                activeProvider.stop();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not stop provider " + activeProvider.getProviderId(), exception);
            }
        }
        activeProvider = null;
        activeDeviceKey = "";
        activeProviderRouted = false;
        poseStreamingEnabled = false;
        sink.onOutputChanged(null);
    }

    void close() {
        stop();
        for (NormalizedHeadPoseProvider provider : providers) {
            try {
                provider.close();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not close provider " + provider.getProviderId(), exception);
            }
        }
        providers.clear();
    }

    synchronized boolean recenter() {
        NormalizedHeadPoseProvider provider = activeProvider;
        if (provider == null || !poseStreamingEnabled) return false;
        try {
            boolean accepted = provider.recenter();
            if (accepted) Log.i(TAG, "Recalibrating " + provider.getProviderId());
            return accepted;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not recenter " + provider.getProviderId(), exception);
            return false;
        }
    }

    synchronized boolean hasProviderForCurrentOutput() {
        return findProvider(currentOutput) != null;
    }

    private NormalizedHeadPoseProvider findProvider(AudioOutputIdentity output) {
        if (output == null) return null;
        for (NormalizedHeadPoseProvider provider : providers) {
            if (provider.supports(output)) return provider;
        }
        return null;
    }

    private void reconcileLocked() {
        // Keep the selected companion attached for a supported active route even when motion is
        // disabled. The companion independently owns its accessory control transport.
        NormalizedHeadPoseProvider routedProvider = findProvider(currentOutput);
        AudioOutputIdentity requestedOutput = routedProvider == null ? null : currentOutput;
        NormalizedHeadPoseProvider requestedProvider = routedProvider;
        String requestedKey = requestedOutput == null ? "" : requestedOutput.deviceKey;
        boolean requestedProviderRouted = routedProvider != null;
        if (requestedProvider != activeProvider || !requestedKey.equals(activeDeviceKey)) {
            stopActiveProviderLocked();
            wearState = NormalizedHeadPoseProvider.WearState.UNKNOWN;
            if (requestedProvider == null || requestedOutput == null) {
                if (trackingRequested && currentOutput != null) {
                    Log.w(TAG, "No head-pose provider for output type="
                            + currentOutput.deviceType + ", name=" + currentOutput.displayName);
                }
                return;
            }
            activeProvider = requestedProvider;
            activeDeviceKey = requestedKey;
            activeProviderRouted = requestedProviderRouted;
            int callbackGeneration = providerGeneration;
            sink.onOutputChanged(activeProviderRouted ? requestedOutput : null);
            try {
                Log.i(TAG, "Starting " + activeProvider.getProviderId() + " for "
                        + requestedOutput.displayName);
                activeProvider.start(requestedOutput,
                        createProviderSink(activeProvider, activeDeviceKey, callbackGeneration));
            } catch (RuntimeException exception) {
                Log.e(TAG, "Could not start provider " + activeProvider.getProviderId(),
                        exception);
                providerGeneration++;
                activeProvider = null;
                activeDeviceKey = "";
                sink.onOutputChanged(null);
                return;
            }
        } else if (activeProviderRouted != requestedProviderRouted) {
            activeProviderRouted = requestedProviderRouted;
            sink.onOutputChanged(activeProviderRouted ? currentOutput : null);
        }
        setPoseStreamingEnabledLocked(trackingRequested && activeProviderRouted
                && wearState.permitsHeadTracking());
    }

    private void setPoseStreamingEnabledLocked(boolean enabled) {
        if (activeProvider == null) {
            poseStreamingEnabled = false;
            return;
        }
        if (poseStreamingEnabled == enabled) return;
        poseStreamingEnabled = enabled;
        try {
            activeProvider.setPoseStreamingEnabled(enabled);
        } catch (RuntimeException exception) {
            poseStreamingEnabled = false;
            Log.w(TAG, "Could not change pose stream for " + activeProvider.getProviderId(),
                    exception);
        }
    }

    private boolean isCurrentProviderCallbackLocked(NormalizedHeadPoseProvider provider,
            String deviceKey, int generation) {
        return activeProvider == provider && providerGeneration == generation
                && activeDeviceKey.equals(deviceKey);
    }

    private void updateWearState(NormalizedHeadPoseProvider provider, String deviceKey,
            int generation, NormalizedHeadPoseProvider.WearState requestedState) {
        boolean policyChanged;
        synchronized (this) {
            if (!isCurrentProviderCallbackLocked(provider, deviceKey, generation)) return;
            NormalizedHeadPoseProvider.WearState normalized = requestedState == null
                    ? NormalizedHeadPoseProvider.WearState.UNKNOWN : requestedState;
            boolean wasOpen = wearState.permitsHeadTracking();
            wearState = normalized;
            boolean isOpen = wearState.permitsHeadTracking();
            setPoseStreamingEnabledLocked(trackingRequested && activeProviderRouted && isOpen);
            policyChanged = wasOpen != isOpen;
        }
        if (policyChanged && policyListener != null) {
            policyListener.onEffectiveTrackingPolicyChanged();
        }
    }
}
