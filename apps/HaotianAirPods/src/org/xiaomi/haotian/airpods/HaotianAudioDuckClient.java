/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.xiaomi.haotian.airpods;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.xiaomi.haotian.audio.IHaotianAudioService;
import org.xiaomi.haotian.audio.MiSoundCompatContract;

/** Lazy, process-wide client for HaotianAudio's transient playback-chain attenuation. */
final class HaotianAudioDuckClient {
    private static final String TAG = "HaotianAirPods";
    private static final long REBIND_DELAY_MS = 1_000;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final IBinder OWNER_TOKEN = new Binder();

    private static Context applicationContext;
    private static IHaotianAudioService service;
    private static boolean bound;
    private static boolean desired;
    private static boolean delivered;
    private static String deviceKey = "";
    private static float volume = 1.0f;

    private static final Runnable REBIND = () -> {
        synchronized (HaotianAudioDuckClient.class) {
            bindLocked();
        }
    };

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (HaotianAudioDuckClient.class) {
                IHaotianAudioService candidate = IHaotianAudioService.Stub.asInterface(binder);
                try {
                    if (candidate == null
                            || candidate.getApiVersion() < MiSoundCompatContract.API_VERSION) {
                        Log.w(TAG, "HaotianAudio does not support transient media ducking");
                        delivered = true;
                        return;
                    }
                } catch (RemoteException | RuntimeException exception) {
                    releaseBindingLocked();
                    scheduleRebindLocked();
                    return;
                }
                service = candidate;
                pushLocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (HaotianAudioDuckClient.class) {
                service = null;
                // A delivered request is intentionally not replayed after a service restart: the
                // user may already have cancelled it with Volume Up while this conversation is
                // still active. The next conversation event creates a fresh request.
                if (!delivered) scheduleRebindLocked();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (HaotianAudioDuckClient.class) {
                releaseBindingLocked();
                if (!delivered) scheduleRebindLocked();
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            synchronized (HaotianAudioDuckClient.class) {
                releaseBindingLocked();
                scheduleRebindLocked();
            }
        }
    };

    private HaotianAudioDuckClient() {
    }

    static synchronized void request(
            Context context, String requestedDeviceKey, float requestedVolume) {
        applicationContext = context.getApplicationContext();
        deviceKey = requestedDeviceKey;
        volume = requestedVolume;
        desired = true;
        delivered = false;
        HANDLER.removeCallbacks(REBIND);
        if (service != null) {
            pushLocked();
        } else {
            bindLocked();
        }
    }

    static synchronized void release(Context context) {
        applicationContext = context.getApplicationContext();
        desired = false;
        delivered = false;
        HANDLER.removeCallbacks(REBIND);
        IHaotianAudioService current = service;
        if (current != null) {
            try {
                current.releaseTransientMediaDuck(OWNER_TOKEN);
            } catch (RemoteException | RuntimeException exception) {
                Log.w(TAG, "Could not release transient media duck", exception);
            }
        }
        releaseBindingLocked();
    }

    static synchronized void shutdown(Context context) {
        release(context);
        applicationContext = null;
    }

    private static void pushLocked() {
        IHaotianAudioService current = service;
        if (!desired || delivered || current == null) return;
        try {
            current.requestTransientMediaDuck(OWNER_TOKEN, deviceKey, volume);
            delivered = true;
        } catch (RemoteException | RuntimeException exception) {
            Log.w(TAG, "Could not request transient media duck", exception);
            releaseBindingLocked();
            scheduleRebindLocked();
        }
    }

    private static void bindLocked() {
        Context context = applicationContext;
        if (!desired || delivered || bound || context == null) return;
        try {
            bound = context.bindService(
                    new Intent().setComponent(MiSoundCompatContract.SERVICE_COMPONENT),
                    CONNECTION, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not bind HaotianAudio for transient ducking", exception);
            bound = false;
        }
        if (!bound) scheduleRebindLocked();
    }

    private static void scheduleRebindLocked() {
        if (!desired || delivered) return;
        HANDLER.removeCallbacks(REBIND);
        HANDLER.postDelayed(REBIND, REBIND_DELAY_MS);
    }

    private static void releaseBindingLocked() {
        Context context = applicationContext;
        service = null;
        if (!bound || context == null) {
            bound = false;
            return;
        }
        try {
            context.unbindService(CONNECTION);
        } catch (IllegalArgumentException ignored) {
        }
        bound = false;
    }
}
