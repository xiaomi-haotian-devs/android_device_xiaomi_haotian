# Haotian audio architecture

`HaotianAudioService` is the only process allowed to own the global output-mix handles for Dolby
DAP, MiSound and the framework Spatializer. Settings is only a client. A future restricted stock
MiSound workflow plugin is also only a client and must not instantiate `android.media.audiofx.MiSound`
or change Spatializer state itself.

## State and routing

- `AudioDeviceProfile` is the complete desired state for one stable media output.
- `OutputDeviceManager` follows the route selected by AudioPolicy rather than guessing from
  Bluetooth connection state.
- The built-in speaker, wired output, each USB output and each Bluetooth output have independent
  profiles in device-protected storage.
- All profile mutations, route changes, effect writes and audioserver recovery are serialized on
  `HaotianAudioWorker`. An active profile is applied first, committed only after success, and
  rolled back to the preceding applied state if either the DSP or persistence operation fails.
- The controller caches the last successfully applied DSP state. Opening Settings and no-op service
  starts perform no effect writes; profile and equalizer changes update only parameters whose
  effective value changed. It never requests the vendor `effect_switch_fade` transition, so an
  in-place preset or band adjustment does not deliberately mute the output.
- Dolby and MiSound remain mutually exclusive effect implementations. The service also owns the
  shared Spatializer switch so AOSP and Xiaomi controls cannot overwrite each other.

## Codec and spatializer output recovery

The software Bluetooth audio HAL exposes a separate `a2dp spatializer output` mix port with
`SPATIALIZER` flags and routes to its A2DP devices. Its PCM profile is dynamic: it uses the
encoder's negotiated rate, channel layout and bit depth. AudioFlinger performs spatialization
before sending the resulting PCM to the software encoder; a primary-HAL spatial output is not
a substitute for this route. Hearing-aid routing is unchanged.

Codec, route and spatializer lifecycle events schedule a worker-serialized recovery batch at
300/1000/2500/5000 ms. Recovery checks the actual route and current desired profile, restores the
per-device spatial-audio flag separately from global enable, waits for availability, and then
restores head tracking. Successful recovery skips remaining writes; callbacks cannot extend the
batch. Device changes and explicit profile edits cancel obsolete work. Recovery never saves an
unavailable runtime state over a user's stored profile and never toggles spatialization off/on
as a reset. Repeated failure is logged, not retried indefinitely.

Device validation after building: enable immersive audio on AirPods with software AAC; confirm a
Bluetooth `a2dp spatializer output` in audio-policy dumps and a non-null native spatializer effect.
Then check PCM 44.1/48 kHz, software/hardware codec transitions, head-pose updates during playback,
immersive off/on, disconnect/reconnect, and a user-off action during recovery. Concurrent
notification/media playback must also be checked for Bluetooth stream handoff problems. Source
and configuration checks do not establish vendor-effect or device playback compatibility.

## Transient media ducking

Signature-trusted companions can ask `IHaotianAudioService` for route-scoped transient media
attenuation. `TransientMediaDucker` applies a 500 ms `VolumeShaper` to each active media/game
player—the same native per-player mechanism used by framework audio-focus ducking—without writing
the music stream index. Playback that starts while the request is active enters at the requested
gain immediately; pause/resume, player release, output-route changes and audioserver restart are
reconciled on `HaotianAudioWorker`.

Requests carry an owner Binder token and disappear on owner death. A Volume Up key gesture clears
all outstanding transient requests even when the stream is already at its maximum index; an
increased media-volume broadcast also covers slider-driven changes. Restoration reverses the
shaper instead of reconstructing or writing a saved system volume.

## Personal audio compatibility (SoundID, hearing, ear scan and headset model)

`IHaotianAudioService` is protected by the platform-signature
`org.xiaomi.haotian.audio.permission.CONTROL_AUDIO` permission. It exposes semantic operations, not
raw effect parameter writes:

- `setSoundIdProfile`
- `setHearingProfile`
- `setEarScanProfile`
- `setHeadsetModel`

The data is validated, bounded and stored per output. This is the control plane for either an MD3
workflow or a narrowly patched stock scan/measurement Activity. The stock Activity may calculate a
curve, but the service remains the only owner that applies it.

The MD3 workflow applies all four datasets through the modern haotian MiSound parameter contract:

- headset model (`14`);
- six-band left/right hearing compensation (`9`-`13`, using Xiaomi's staged then commit protocol);
- 1024 ear-canal filter coefficients and enable state (`23`/`26`);
- six-band SoundID gains and enable state (`27`/`24`).

The mapping and payload types are corroborated by the stock MiSound reflection wrapper, the shipped
`libmisoundfx_aidl_ext.so` parameter table and `kvh2xml.xml`. Float vectors are passed in native
little-endian order. Invalid-length data is neither persisted as enabled nor sent to the effect.

Generating a *new* ear-canal measurement remains hardware-specific. Stock MiSound talks to a
supported Xiaomi/Redmi earphone through BluetoothExtension's private measurement transport. The
generic MD3 workflow therefore supports importing, storing, enabling and clearing the resulting
1024 coefficients, but does not pretend that an arbitrary Bluetooth route can perform the scan.

## External headphone head tracking

HaotianAudio does not own an accessory protocol or Bluetooth L2CAP channel. It discovers
platform-signed system services advertising
`org.xiaomi.haotian.audio.action.HEADPHONE_POSE_PROVIDER`, selects the implementation supporting the
active AudioPolicy route, and consumes only normalized pose and wear state. The provider contract is
Apache-2.0 and deliberately contains no AirPods/AACP types, so future headphone tools can implement
the same boundary independently.

High-rate data does not cross Binder. `HeadTrackerSharedMemorySink` creates the release/acquire pose
ring and gives a writable duplicate to the selected companion. The same region is sent to
`sensors.haotian_headtracker.so`; Binder carries only lifecycle commands, recenter requests, errors,
and low-rate left/right wear changes. The Sensors 2.1 multi-HAL sub-HAL validates every record,
converts the headset-to-world quaternion to Android's reference-to-head Euler vector, and posts
ordinary sensor events through the existing HAL FMQ. Route changes revoke the provider session,
close the shared region, and disconnect the dynamic sensor.

The accompanying `frameworks/av` patch treats every active track already admitted to the
Spatializer output as eligible for pose delivery. Upstream restricts this step to immersive channel
masks, which leaves stereo-to-binaural playback spatialized but forces its actual head-tracking mode
to `DISABLED`. Empty outputs, disabled spatialization, a disabled desired mode and an unavailable
sensor remain independently gated by the existing native state machine.

Provider state transitions are logged; per-frame poses are not. HaotianAudio never receives raw
accessory packets, battery data, device serials, listening-mode values, or application preferences.

## Later extensions

- GameDAP is a separate effect (`3783c334-d3a0-4d13-874f-0032e5fb80e2`), not a hidden Dolby profile.
  Per-game rules will therefore select and configure a dedicated service-owned GameDAP handle.
- Output-device preferences and game-package rules belong in the service store, not in the UI or a
  headphone provider.
- Headphone companions only normalize pose, wear and connection capability; they do not own sound
  effect state.
