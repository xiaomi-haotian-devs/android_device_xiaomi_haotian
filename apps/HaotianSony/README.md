# HaotianSony

Sony MDR V2 companion integrated with the haotian ROM. This app owns headphone
controls only. HaotianAudio and Android retain ownership of spatial audio.

## Integration

- `HaotianSony` is a platform-signed privileged system_ext app with its own process,
  SELinux domain, default Bluetooth permission grant and signature-protected AIDL service.
- The system-user controller owns one Android `BluetoothSocket` RFCOMM connection to
  `956c7b26-d49a-4ba8-b03f-b17d393cb6e2`. Bonded, connected A2DP peers advertising this
  Sony service are eligible. A requested Bluetooth-details device takes precedence,
  then active A2DP, then the previous compatible connection.
- JNI duplicates the connected descriptor; nonblocking transport, socket establishment
  timeout, request deadlines, bounded polling and reconnect backoff keep work off the UI.
- Bundled libmdr is pinned to SonyHeadphonesClient commit
  `965c458116d40827494726447de5f07eb50efcb8`. See `third_party/UPSTREAM.md` and `LICENSE`.
  No BlueZ, DBus, desktop UI or code-generation build is imported.
- Bluetooth Settings adds a Sony-specific entry. The SettingsLib Expressive page,
  battery metadata and optional Sony noise-control QS tile use the same service.
  Renaming the Bluetooth device does not hide its control entry (SDP identity is used).
- Secondary-user UIs bind to the system-user service. Other apps need the platform
  signature to bind or open the settings activity.

## Control coverage

Controls depend on device-advertised capabilities and actual received values. A
feature exposed by one Sony firmware is not a promise of support on another.

- Model, firmware, main/left/right/case battery and charging status; playback metadata,
  play/pause/previous/next and headphone volume.
- ANC/ambient/off, ambient level, voice focus, adaptive ambient settings and NC/AMB cycle.
- Speak-to-Chat, detection sensitivity and resume timeout.
- Device-advertised EQ presets, 5/10-band editing, Clear Bass and DSEE; supported
  background/cinema listening modes and room size.
- Auto power-off, supported wearing-power mode, auto-pause, head gestures, guidance
  on/off/volume, Bluetooth quality/stability priority and explicit shutdown.
- Boolean general settings (including device-provided labels), supported V2 left/right
  assignments, pairing mode, paired-device connect/disconnect/source-switch/unpair and
  automatic source switching.
- Headphone-reported listening level and explicit start/stop preview commands.

UI preferences are not persisted locally. Setters preserve the complete reported
structure and validate feature, range and advertised choices. After commit, common
controls query only the affected group (usually one or two GET commands), retaining
identity and capability information. Completion waits for fresh property reports,
including when the ACK precedes the parameter reply. First connection, manual refresh
and uncommon device-management/actions keep the full initialization path.
The upstream ordinary
sync mostly covers battery/listening level and is not treated as control readback.
Missing reports remain unavailable; local ACKs do not establish state provenance.
Commands such as track changes/preview/shutdown are explicit actions, not inferred states.

This is MDR V2 integration, not a replacement for Sony cloud services or a firmware
updater. V1 transport fallback, unknown/new protocol controls, non-boolean general
settings, arbitrary/custom assignment layouts and application-side Sony cloud features
are not implemented. An official Sony control app may occupy the same control channel;
the service reports connection failure and retries rather than forcibly disconnecting it.

## Native head tracking and AirPods isolation

WH-1000XM5 uses its existing Android dynamic head-tracker sensor, not an app-produced
pose stream. There are no changes to HaotianAirPods, AACP or the shared-memory Sensors HAL.

`frameworks/av/media/libheadtracking/SensorPoseProvider.cpp` applies the model-specific
quirk before the spatializer consumes the pose. Matching requires a dynamic
`TYPE_HEAD_TRACKER`, Bluetooth UUID, exact sensor name `WH-1000XM5` or `Sony WH-1000XM5`,
and Sony/Bluetooth vendor text. Apple vendor IDs and the existing Haotian AirPods
sensor identity do not match. Other models and static/screen sensors are unchanged.

The initial correction negates native Z rotation-vector and Z angular-velocity
components together (lateral/yaw reversal); X/Y are retained. This implements the
reported left/right symptom. It is **not yet hardware-validated**, especially for
combined rotations. It must not be generalized into an all-axis inverse or other Sony
models without measurement. If a Bluetooth alias changes the sensor's model name,
the match deliberately fails closed; inspect the displayed sensor identity first.

The reserved read-only spatializer parameter `0x48545044` exposes a versioned 384-byte
little-endian snapshot of the sensor already selected by the audio service. Its setter
is rejected. Existing `MODIFY_DEFAULT_AUDIO_EFFECTS` checks protect access. No extra
sensor subscription is created and SensorService's head-tracker restrictions remain intact.

HaotianAudio's activity-scoped reader polls at 10 Hz off the UI thread, verifies the
snapshot UUID against the active Bluetooth identity address, and displays a head model,
raw rotation/velocity, corrected quaternion/velocity, sensor identity/correction, real
sensor sample rate, sample age and sensor-to-audioserver delivery time. Age continues
advancing if polling stops delivering frames. The native Z-up pose has its own display
basis conversion; the existing AirPods visualization mapping remains unchanged.

Recenter resets the native view's local reference and requests Android spatializer
recentering. Raw diagnostic quaternion values are not rewritten by UI recentering.
The native route has no app/shared-memory stage timings; they are explicitly unavailable,
not shown as zero or presented as end-to-end audio latency. Spatialized playback and
enabled head tracking are needed for native samples; opening the page alone does not
activate tracking.

## Verification

Source-only lint (does not invoke a compiler/build):

```sh
python3 device/xiaomi/haotian/apps/HaotianSony/tools/check_static_contracts.py
```

It checks XML/resources/translations, JNI/C/AIDL references, diagnostic wire constants
and offsets, selected source guards and delimiters. It is not Java/C++ semantic checking.

`frameworks/av/media/libheadtracking/SonyHeadTrackerQuirk-test.cpp` adds the device test
module `libheadtracking-sony-test`: scoped identity (including AirPods/Apple exclusions),
Z-only rotation/velocity correction and snapshot serialization. The test binary has
**not** been compiled or run. No Android build or physical-device test was run during
implementation; compilation requires the user's explicit authorization.

After the user compiles/installs, validate on hardware:

1. Connect WH-1000XM5; open Bluetooth details → Sony headphones. Confirm model,
   firmware, battery and only supported settings; refresh and reconnect should not
   fabricate zero-valued controls. Verify settings on the headphones/official app.
2. Change ANC, ambient level, EQ, DSEE and supported behavior settings individually.
   Check write readback, external button/app changes and whether other fields are preserved.
3. Test the QS tile, Bluetooth off/on, headset power cycle, service restart, official-app
   channel contention and switching between two compatible headphones. A stale dialog
   must not send an operation to another device.
4. Play spatialized audio with tracking enabled. In HaotianAudio verify the displayed
   sensor name/vendor and `Sony XM5: Z reversed`; test left/right, up/down, tilt and mixed
   rotations independently. Verify both audible rendering and the displayed motion.
   Recenter, stop playback and resume; no-data/stale states must be honest.
5. Switch to AirPods. Confirm its existing control service, AACP stream, display mapping,
   spatial rendering, recenter and latency diagnostics still behave as before. Native
   Sony data must not remain selected after the route switch.
6. Check SELinux denials and Bluetooth permissions on user/userdebug images and with a
   secondary user. Useful read-only diagnostics: `dumpsys sensorservice`,
   `dumpsys media.audio_policy` (look for `Correction: sony_xm5_yaw_z`) and
   `logcat -s HaotianSony`.
