# Xiaomi Camera OZO compatibility

This module restores the `android.media.AudioParaManger` contract used by the
stock Xiaomi Camera without importing HyperOS' complete `miui-framework.jar`.
It is intentionally device-specific because the OZO microphone-array UUIDs
and camera-mode mapping belong to haotian.

The public class forwards to a private controller which:

- creates and configures sessions through
  `com.android.ozoaudio.notify.IOzoNotify/default`;
- binds the session to `MediaRecorder` and the vendor OZO audio effect;
- maps standard, audio zoom, spatial, front, rear and dual-direction recording
  to the stock haotian four-microphone UUID table;
- forwards live focus, zoom, AGC and wind-noise controls to the OZO codec;
- preserves Xiaomi's provider-3 wind-noise custom-scene path and attaches the
  matching MIWNS effect to the camera recording session.

The module is installed on the boot class path so Xiaomi Camera's direct DEX
references and reflection both resolve. The existing Xiaomi Camera APK does
not require a manifest or apktool patch for this API.

## Device validation

After building and flashing, begin with a standard video recording and then
exercise audio zoom, spatial sound, directional recording and wind-noise
reduction separately. Useful read-only checks are:

```sh
adb shell service check com.android.ozoaudio.notify.IOzoNotify/default
adb shell logcat -s HaotianOzoAudio
adb shell dumpsys media.audio_flinger
adb shell dumpsys media.audio_policy
```

Successful Nokia/OZO modes log session creation followed by
`initialized=true`; AudioFlinger should show either the OZO or MIWNS effect
UUID attached to the camera recording session. Provider-3 wind-noise mode uses
a local session with the vendor custom-scene parameter instead of OzoNotify.
