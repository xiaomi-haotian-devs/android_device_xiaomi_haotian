/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.media.audiozoomfx;

import android.media.AudioManager;
import android.media.AudioParaManger;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Private Nokia/OZO implementation behind the Xiaomi Camera compatibility API. */
public final class OzoController {
    private static final String TAG = "HaotianOzoAudio";

    private static final String OZO_SERVICE =
            "com.android.ozoaudio.notify.IOzoNotify/default";
    private static final String OZO_NOTIFY_DESCRIPTOR =
            "com.android.ozoaudio.notify.IOzoNotify";
    private static final String OZO_CALLBACK_DESCRIPTOR =
            "com.android.ozoaudio.notify.IOzoCb";
    private static final String OZO_CODEC_DESCRIPTOR =
            "com.android.ozoaudio.notify.IOzoCodec";
    private static final String OZO_TUNE_CALLBACK_DESCRIPTOR =
            "com.android.ozoaudio.notify.IOzoTuneCb";

    private static final int TRANSACTION_CREATE_ID = 1;
    private static final int TRANSACTION_GET_CODEC_PROXY = 4;
    private static final int TRANSACTION_RESET = 8;
    private static final int TRANSACTION_SET_CALLBACK = 9;
    private static final int TRANSACTION_SET_CONFIGURATION = 11;
    private static final int TRANSACTION_SET_TUNE_CALLBACK = 12;
    private static final int TRANSACTION_GET_INTERFACE_HASH = 0xfffffe;
    private static final int TRANSACTION_GET_INTERFACE_VERSION = 0xffffff;

    private static final int EVENT_AUDIO_LEVEL = 0x7f100005;
    private static final int EVENT_MIC_BLOCKING = 0x7f100006;

    private static final int PROVIDER_NOKIA = 2;
    private static final int PROVIDER_MI_WIND_NOISE = 3;
    private static final int MIWNS_SESSION_ID = 0;
    private static final int OZO_INPUT_CHANNELS = 4;
    private static final long OZO_CHANNEL_INDEX_MASK = -2147483636L;
    private static final int MEDIA_RECORDER_SESSION_FLAG = 0x400;

    private static final String[] HAOTIAN_UUIDS = {
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E0",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E1",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E2",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E3",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E4",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E5",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E6",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E7",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175F0",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175F1",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175F2",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175F3",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175F4",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175F5",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175F6",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175F7",
            "CC3BBB96-CE6D-415D-9FE4-9C3A987175E8",
    };

    private final AudioManager mAudioManager;
    private final WeakReference<MediaRecorder> mMediaRecorder;
    @SuppressWarnings("unused")
    private final WeakReference<AudioRecord> mAudioRecord;
    private final List<OzoAudioEffect> mEffects = new ArrayList<>();
    private final List<MiwnsAudioEffect> mMiwnsEffects = new ArrayList<>();

    private IBinder mService;
    private IBinder mCodec;
    private EventCallback mEventCallback;
    private TuneCallback mTuneCallback;
    private int mOzoSessionId = -1;
    private boolean mMiwnsSessionCreated;
    private boolean mInitialized;
    private int mProvider;
    private int mRecordType = 1;
    private int mShot = 1;
    private int mScene = 1;
    private int mMaxVisual = 6;
    private boolean mWindNoise;
    private double mLevel = 1.0;
    private double mAzimuth;
    private double mElevation;
    private double mWidth = 60.0;
    private double mHeight = 100.0;
    private StringBuilder mInitConfiguration = new StringBuilder();

    public OzoController(AudioManager audioManager,
            WeakReference<MediaRecorder> mediaRecorder,
            WeakReference<AudioRecord> audioRecord) {
        mAudioManager = audioManager;
        mMediaRecorder = mediaRecorder;
        mAudioRecord = audioRecord;
    }

    public void configure(int recordType, int shot, int scene, double level,
            double azimuth, double elevation, double width, double height,
            boolean windNoise, int maxVisual, int provider) {
        mRecordType = recordType;
        mShot = shot;
        mScene = scene;
        mLevel = level;
        mAzimuth = azimuth;
        mElevation = elevation;
        mWidth = validRange(width, 0.0, 360.0) ? width : 60.0;
        mHeight = validRange(height, 0.0, 180.0) ? height : 100.0;
        mWindNoise = windNoise;
        mMaxVisual = Math.max(1, Math.min(maxVisual, 6));
        mProvider = provider;
        mInitialized = false;
        mMiwnsSessionCreated = false;
        mInitConfiguration = new StringBuilder();
    }

    public void setMaxVisual(int maxVisual) {
        mMaxVisual = Math.max(1, Math.min(maxVisual, 6));
    }

    public void createAudioSession(AudioParaManger.EventListener listener,
            AudioParaManger.TuneListener tuneListener) {
        if (mProvider == PROVIDER_MI_WIND_NOISE) {
            // HyperOS' MiwnsAudioImpl is a local session object whose session ID
            // remains zero; the actual processing is provided by the audio effect.
            mMiwnsSessionCreated = true;
            return;
        }
        createOzoSession(listener, tuneListener);
    }

    public void createOzoSession(AudioParaManger.EventListener listener,
            AudioParaManger.TuneListener tuneListener) {
        if (mProvider != PROVIDER_NOKIA || mService != null) {
            return;
        }
        try {
            mService = ServiceManager.waitForDeclaredService(OZO_SERVICE);
            if (mService == null) {
                throw new RemoteException("OZO notify service is unavailable");
            }
            mOzoSessionId = createId();
            mEventCallback = new EventCallback(listener);
            setCallback(mEventCallback);
            mCodec = getCodecProxy();
            if (tuneListener != null) {
                mTuneCallback = new TuneCallback(tuneListener);
                setTuneCallback(mTuneCallback);
            }
            Log.d(TAG, "Created OZO session " + mOzoSessionId);
        } catch (Exception e) {
            Log.e(TAG, "Unable to create OZO session", e);
            clearSessionState();
        }
    }

    public void prepare() {
        if (mProvider == PROVIDER_MI_WIND_NOISE) {
            prepareMiWindNoise();
            return;
        }
        if (mProvider != PROVIDER_NOKIA || mService == null || mOzoSessionId < 0
                || mInitialized) {
            return;
        }
        try {
            if (mAudioManager != null) {
                mAudioManager.setParameters("SetAudioCustomScene=OzoaudioZoom");
            }
            MediaRecorder recorder = mMediaRecorder == null ? null : mMediaRecorder.get();
            if (recorder != null) {
                recorder.setAudioChannels(mOzoSessionId | MEDIA_RECORDER_SESSION_FLAG);
            }

            mInitConfiguration = new StringBuilder()
                    .append("channelmask=").append(OZO_CHANNEL_INDEX_MASK).append(';')
                    .append("mode=ozoaudio;device=").append(selectDeviceUuid())
                    .append(";input-channels=").append(OZO_INPUT_CHANNELS).append(';');

            applyRecordType(mRecordType);
            setValue("ns", "smart");
            setValue("ns-gain", format(50.0));
            setValue("wnr", mWindNoise ? "on" : "off");
            mInitConfiguration.append("notification=audiolevels;");

            mInitialized = setConfiguration(mInitConfiguration.toString());
            Log.d(TAG, "Prepared OZO session " + mOzoSessionId
                    + ": initialized=" + mInitialized);
        } catch (Exception e) {
            Log.e(TAG, "Unable to prepare OZO recording", e);
            mInitialized = false;
        }
    }

    public boolean createAudioEffect(int audioSessionId) {
        if (mProvider == PROVIDER_MI_WIND_NOISE) {
            return createMiwnsEffect(audioSessionId);
        }
        return createOzoEffect(audioSessionId);
    }

    public boolean createOzoEffect(int audioSessionId) {
        if (mProvider != PROVIDER_NOKIA || mOzoSessionId < 0) {
            return false;
        }
        for (OzoAudioEffect effect : mEffects) {
            if (effect.getAudioSessionIdCompat() == audioSessionId) {
                return true;
            }
        }
        try {
            OzoAudioEffect effect = new OzoAudioEffect(audioSessionId);
            // HyperOS does not treat the vendor parameter's return code as fatal;
            // the vendor effect can accept it while returning a non-zero status.
            effect.setOzoSessionId(mOzoSessionId);
            if (effect.setEnabled(true) != AudioEffect.SUCCESS) {
                effect.release();
                return false;
            }
            mEffects.add(effect);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to attach OZO effect to session " + audioSessionId, e);
            return false;
        }
    }

    private boolean createMiwnsEffect(int audioSessionId) {
        if (!mMiwnsSessionCreated) {
            Log.w(TAG, "MIWNS effect requested before its audio session was created");
            return false;
        }
        for (MiwnsAudioEffect effect : mMiwnsEffects) {
            if (effect.getAudioSessionIdCompat() == audioSessionId) {
                return true;
            }
        }
        try {
            MiwnsAudioEffect effect = new MiwnsAudioEffect(audioSessionId);
            effect.setMiwnsSessionId(MIWNS_SESSION_ID);
            if (effect.setEnabled(true) != AudioEffect.SUCCESS) {
                effect.release();
                return false;
            }
            mMiwnsEffects.add(effect);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to attach MIWNS effect to session " + audioSessionId, e);
            return false;
        }
    }

    public void release() {
        for (OzoAudioEffect effect : mEffects) {
            try {
                effect.setEnabled(false);
                effect.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Error releasing OZO effect", e);
            }
        }
        mEffects.clear();

        for (MiwnsAudioEffect effect : mMiwnsEffects) {
            try {
                effect.setEnabled(false);
                effect.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Error releasing MIWNS effect", e);
            }
        }
        mMiwnsEffects.clear();

        if (mService != null && mOzoSessionId >= 0) {
            try {
                reset();
            } catch (RemoteException e) {
                Log.w(TAG, "Error releasing OZO session", e);
            }
        }
        clearSessionState();
    }

    public void setRecordType(int recordType) {
        mRecordType = recordType;
        if (mService != null) {
            applyRecordType(recordType);
        }
    }

    public void setWindNoise(boolean enabled) {
        mWindNoise = enabled;
        if (mProvider == PROVIDER_MI_WIND_NOISE && mInitialized) {
            prepareMiWindNoise();
            return;
        }
        setValue("wnr", enabled ? "on" : "off");
    }

    public void setUserMode(boolean enabled) {
        setValue("agc", enabled ? "user" : "auto");
    }

    public void setUserGain(double level) {
        setValue("agc-usergain", format(toAgcGain(level)));
    }

    public void setZoomEnabled(int beamIndex, boolean enabled) {
        if (beamIndex < 0 || beamIndex > 1) {
            return;
        }
        setValue("focus", beamIndex + "," + (enabled ? "on" : "off"));
    }

    public void setZoomLevel(double level) {
        double gain = gainForVisualLevel(level);
        setZoomParameter("zoom", 0, gain);
        setZoomParameter("focus-azimuth", 0, 0.0);
        setZoomParameter("focus-elevation", 0, 0.0);
    }

    public void setFocusAzimuth(double azimuth, int zoomType) {
        if (validRange(azimuth, -180.0, 180.0)) {
            mAzimuth = azimuth;
            setZoomParameter("focus-azimuth", 0, azimuth);
        }
    }

    public void setFocusElevation(double elevation, int zoomType) {
        if (validRange(elevation, -90.0, 90.0)) {
            mElevation = elevation;
            setZoomParameter("focus-elevation", 0, elevation);
        }
    }

    public void setFocusWidth(double width) {
        if (validRange(width, 0.0, 360.0)) {
            mWidth = width;
            setZoomParameter("focus-width", 0, width);
        }
    }

    public void setFocusHeight(double height) {
        if (validRange(height, 0.0, 180.0)) {
            mHeight = height;
            setZoomParameter("focus-height", 0, height);
        }
    }

    private void applyRecordType(int recordType) {
        switch (recordType) {
            case 2: // Audio zoom
                setBeam(0, gainForVisualLevel(mLevel), mAzimuth, mElevation, 60.0, 100.0);
                setZoomEnabled(1, false);
                break;
            case 3: // Spatial sound, no focused beam
                setZoomEnabled(0, false);
                setZoomEnabled(1, false);
                break;
            case 4: // Front
                setBeam(0, 5.0, 0.0, 0.0, 60.0, 100.0);
                setZoomEnabled(1, false);
                break;
            case 5: // Back
                setBeam(0, 4.0, 180.0, 0.0, 60.0, 100.0);
                setZoomEnabled(1, false);
                break;
            case 6: // Front and back
                setBeam(0, 5.0, 0.0, 0.0, 60.0, 100.0);
                setBeam(1, 4.0, 180.0, 0.0, 60.0, 100.0);
                break;
            case 0: // Front-facing video
                setBeam(0, 4.0, 0.0, 0.0, 60.0, 100.0);
                setZoomEnabled(1, false);
                break;
            case 1: // Standard recording
            default:
                break;
        }
    }

    private void setBeam(int beam, double gain, double azimuth, double elevation,
            double width, double height) {
        setZoomEnabled(beam, true);
        setZoomParameter("zoom", beam, gain);
        setZoomParameter("focus-azimuth", beam, azimuth);
        setZoomParameter("focus-elevation", beam, elevation);
        setZoomParameter("focus-width", beam, width);
        setZoomParameter("focus-height", beam, height);
    }

    private void setZoomParameter(String key, int beam, double value) {
        setValue(key, beam + "," + format(value));
    }

    private void prepareMiWindNoise() {
        if (mAudioManager == null) {
            return;
        }
        mAudioManager.setParameters("SetAudioCustomScene=audioMiwns@shot@/recType@"
                + mRecordType + "/wnd_ns@" + (mWindNoise ? 1 : 0) + "/");
        mInitialized = true;
    }

    private void setValue(String key, String value) {
        if (mProvider != PROVIDER_NOKIA || mService == null) {
            return;
        }
        if (!mInitialized) {
            mInitConfiguration.append("control=").append(key).append('=')
                    .append(value).append(';');
            return;
        }
        if (mCodec == null) {
            Log.w(TAG, "No OZO codec proxy for runtime parameter " + key);
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(OZO_CODEC_DESCRIPTOR);
            data.writeString(key + "=" + value);
            if (!mCodec.transact(1, data, null, IBinder.FLAG_ONEWAY)) {
                Log.w(TAG, "OZO codec rejected runtime parameter " + key);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to set OZO runtime parameter " + key, e);
        } finally {
            data.recycle();
        }
    }

    private String selectDeviceUuid() {
        int index;
        if (mShot == 1) {
            if (mScene == 1) {
                index = 1;
            } else if (mScene == 2) {
                index = 0;
            } else if (mScene == 4) {
                index = 2;
            } else {
                index = 6;
            }
        } else if (mScene == 1) {
            index = 4;
        } else if (mScene == 2) {
            index = 5;
        } else if (mScene == 4) {
            index = 7;
        } else {
            index = 3;
        }

        if (mRecordType == 0 || mRecordType == 2 || mRecordType == 4
                || mRecordType == 5 || mRecordType == 6) {
            index += 8;
        }
        return HAOTIAN_UUIDS[index];
    }

    private double gainForVisualLevel(double level) {
        if (level <= 0.0 || mMaxVisual <= 1) {
            return 2.0;
        }
        return Math.max(0.0, Math.min(5.0,
                5.0 * Math.log10(level) / Math.log10(mMaxVisual)));
    }

    private static double toAgcGain(double level) {
        if (level < 0.0 || level > 100.0 || level == 50.0) {
            return 0.0;
        }
        if (level > 50.0) {
            return (level - 50.0) * 0.1946;
        }
        if (level >= 5.0) {
            return (level - 5.0) * (2.0 / 3.0) - 30.0;
        }
        return level * 9.7 - 78.5;
    }

    private static boolean validRange(double value, double min, double max) {
        return !Double.isNaN(value) && value >= min && value <= max;
    }

    private static String format(double value) {
        return String.format(Locale.US, "%f", value);
    }

    private int createId() throws RemoteException {
        Parcel data = obtainServiceData();
        Parcel reply = Parcel.obtain();
        try {
            transactOrThrow(TRANSACTION_CREATE_ID, data, reply);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void setCallback(IBinder callback) throws RemoteException {
        Parcel data = obtainServiceData();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(mOzoSessionId);
            data.writeStrongBinder(callback);
            transactOrThrow(TRANSACTION_SET_CALLBACK, data, reply);
            reply.readException();
            int result = reply.readInt();
            if (result != 0) {
                throw new RemoteException("setCallback failed: " + result);
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private IBinder getCodecProxy() throws RemoteException {
        Parcel data = obtainServiceData();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(mOzoSessionId);
            transactOrThrow(TRANSACTION_GET_CODEC_PROXY, data, reply);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void setTuneCallback(IBinder callback) throws RemoteException {
        Parcel data = obtainServiceData();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(mOzoSessionId);
            data.writeStrongBinder(callback);
            transactOrThrow(TRANSACTION_SET_TUNE_CALLBACK, data, reply);
            reply.readException();
            int result = reply.readInt();
            if (result != 0) {
                throw new RemoteException("setTuneCallback failed: " + result);
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean setConfiguration(String configuration) throws RemoteException {
        Parcel data = obtainServiceData();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(mOzoSessionId);
            data.writeString(configuration);
            transactOrThrow(TRANSACTION_SET_CONFIGURATION, data, reply);
            reply.readException();
            return reply.readBoolean();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void reset() throws RemoteException {
        Parcel data = obtainServiceData();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInt(mOzoSessionId);
            transactOrThrow(TRANSACTION_RESET, data, reply);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private Parcel obtainServiceData() {
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken(OZO_NOTIFY_DESCRIPTOR);
        return data;
    }

    private void transactOrThrow(int transaction, Parcel data, Parcel reply)
            throws RemoteException {
        if (!mService.transact(transaction, data, reply, 0)) {
            throw new RemoteException("OZO transaction " + transaction + " is unimplemented");
        }
    }

    private void clearSessionState() {
        mInitialized = false;
        mOzoSessionId = -1;
        mMiwnsSessionCreated = false;
        mCodec = null;
        mService = null;
        mEventCallback = null;
        mTuneCallback = null;
    }

    private static final class EventCallback extends Binder implements IInterface {
        private final AudioParaManger.EventListener mListener;

        EventCallback(AudioParaManger.EventListener listener) {
            mListener = listener;
            markVintfStability();
            attachInterface(this, OZO_CALLBACK_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(OZO_CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code >= 1 && code <= TRANSACTION_GET_INTERFACE_VERSION) {
                data.enforceInterface(OZO_CALLBACK_DESCRIPTOR);
            }
            if (code == TRANSACTION_GET_INTERFACE_VERSION) {
                reply.writeNoException();
                reply.writeInt(1);
                return true;
            }
            if (code == TRANSACTION_GET_INTERFACE_HASH) {
                reply.writeNoException();
                reply.writeString("hash");
                return true;
            }
            if (code == 1) {
                int event = data.readInt();
                String value = data.readString();
                data.enforceNoDataAvail();
                dispatch(event, value);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private void dispatch(int event, String value) {
            if (mListener == null || value == null) {
                return;
            }
            try {
                String[] values = value.trim().split("\\s+");
                if (event == EVENT_AUDIO_LEVEL && values.length >= 2) {
                    mListener.onAudioLevel(
                            Integer.parseInt(values[0]), Integer.parseInt(values[1]));
                } else if (event == EVENT_MIC_BLOCKING && values.length >= 2) {
                    mListener.onMicBlocking(
                            Integer.parseInt(values[0]), Integer.parseInt(values[1]));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Invalid OZO callback " + event + ": " + value, e);
            }
        }
    }

    private static final class TuneCallback extends Binder implements IInterface {
        private final AudioParaManger.TuneListener mListener;

        TuneCallback(AudioParaManger.TuneListener listener) {
            mListener = listener;
            markVintfStability();
            attachInterface(this, OZO_TUNE_CALLBACK_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(OZO_TUNE_CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code >= 1 && code <= TRANSACTION_GET_INTERFACE_VERSION) {
                data.enforceInterface(OZO_TUNE_CALLBACK_DESCRIPTOR);
            }
            if (code == TRANSACTION_GET_INTERFACE_VERSION) {
                reply.writeNoException();
                reply.writeInt(1);
                return true;
            }
            if (code == TRANSACTION_GET_INTERFACE_HASH) {
                reply.writeNoException();
                reply.writeString("tune-hash");
                return true;
            }
            if (code == 1 || code == 2) {
                byte[] value = data.createByteArray();
                data.enforceNoDataAvail();
                if (code == 1) {
                    mListener.onTuneCtrlData(value);
                } else {
                    mListener.onTuneAudioData(value);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static final class OzoAudioEffect extends AudioEffect {
        private static final UUID EFFECT_TYPE =
                UUID.fromString("56d6b082-1a83-455a-84a8-9db3a35cf532");
        private static final UUID EFFECT_UUID =
                UUID.fromString("7e384a3b-7850-4a64-a097-884250d8a737");
        private static final int PARAM_SESSION_ID = 0x1b207;

        private final int mAudioSessionId;

        OzoAudioEffect(int audioSessionId) {
            super(EFFECT_TYPE, EFFECT_UUID, 0, audioSessionId);
            mAudioSessionId = audioSessionId;
        }

        int getAudioSessionIdCompat() {
            return mAudioSessionId;
        }

        int setOzoSessionId(int sessionId) {
            return setParameter(PARAM_SESSION_ID,
                    Integer.toString(sessionId).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class MiwnsAudioEffect extends AudioEffect {
        private static final UUID EFFECT_TYPE =
                UUID.fromString("56d6b082-1a83-455a-84a8-9db3a35cf533");
        private static final UUID EFFECT_UUID =
                UUID.fromString("7e384a3b-7850-4a64-a097-884250d8a837");
        private static final int PARAM_SESSION_ID = 0x1b208;

        private final int mAudioSessionId;

        MiwnsAudioEffect(int audioSessionId) {
            super(EFFECT_TYPE, EFFECT_UUID, 0, audioSessionId);
            mAudioSessionId = audioSessionId;
        }

        int getAudioSessionIdCompat() {
            return mAudioSessionId;
        }

        int setMiwnsSessionId(int sessionId) {
            return setParameter(PARAM_SESSION_ID,
                    Integer.toString(sessionId).getBytes(StandardCharsets.UTF_8));
        }
    }
}
