# HaotianMiBuds

HaotianMiBuds is the Xiaomi Buds RCSP and head-pose companion for the haotian
device tree. The initial supported and hardware-captured target is Xiaomi Buds
4 Pro firmware 2.5.7.4.

## Boundaries

- The platform-signed, persistent system-user app owns at most one RFCOMM
  connection to the MIUI SPP UUID `0000fd2d-0000-1000-8000-00805f9b34fb`.
- The local transport implements only the bounded RCSP envelope, Xiaomi's
  mutual `0x50`/`0x51` authentication, device-config read/write framing and the
  head-pose stream. It does not expose arbitrary packets and contains no OTA,
  firmware, file-transfer, account, cloud or voice-assistant support.
- Only Jieli's 12 KiB Apache-2.0 authentication primitive is retained from the
  upstream SDK. See `third_party/jieli/UPSTREAM.md` for provenance and checksum.
  Authentication challenges/proofs and raw packets are never logged.
- The component never starts a control session for an unbonded peer. A default
  Xiaomi Buds 4 Pro name is sufficient for the captured target; other Xiaomi or
  Redmi Buds must also advertise the MIUI SPP UUID.

## Spatial modes

The earbuds and phone expose two independent paths. The implementation keeps
their controls separate and warns before entering the phone path so users do
not accidentally enable double rendering.

1. Earbud-local immersive sound is the one-byte spatial preference written by
   F2 config type `0x1d` and read back from F3 type `0x1e`. Its user-facing
   switches preserve all unedited bits, stay disabled without verified
   readback, and never treat an F2 ACK as the resulting state. Nothing changes
   this preference automatically.
2. HaotianAudio phone rendering binds the generic pose provider. When—and only
   when—the active audio profile requests tracking, the provider sends stock's
   F3 type `0x1d` stream request `FF01020103020501FF`. Stop changes only the
   final byte to `00`. This request never overwrites the one-byte earbud-local
   spatial preference. Because the stop response is asynchronous, leaving an
   active stream also closes that RFCOMM generation after the best-effort stop;
   the control owner reconnects when it is still needed.

Incoming F4 CommonConfig type `0x21` contains little-endian float degrees in
`yaw, pitch, roll` order. Stock HyperOS maps these to Android Z, X and Y axes.
The provider composes a normalized headset-to-world quaternion, writes it to
HaotianAudio's v2 shared-memory ring and keeps high-rate pose samples off Binder.
Angular velocity is zero because this firmware path does not report it.
Recenter stores the current orientation as the local forward reference.

Wear telemetry is deliberately reported as unknown until its exact multi-bud
payload is hardware-validated. Unknown permits tracking in HaotianAudio; an
RFCOMM loss is reported as disconnected and authentication recovery changes it
back to unknown.

Bluetooth device details exposes the companion's SettingsLib page for the
captured model or peers advertising the MIUI SPP UUID. It labels earbud-local
and HaotianAudio phone rendering as separate sections instead of presenting
them as one ambiguous spatial switch.

## Source-only verification

The following checker parses sources/XML and inspects the prebuilt checksum and
ELF alignment. It does not compile or invoke any build system:

```sh
python3 device/xiaomi/haotian/apps/HaotianMiBuds/tools/check_static_contracts.py
```

No Android build or physical-device execution was performed while creating this
module. After compilation is explicitly authorized and performed by the user:

1. Keep the Xiaomi Earphone app closed so it does not own the same SPP channel.
   Connect Xiaomi Buds 4 Pro over A2DP and confirm `HaotianMiBuds` reaches mutual
   authentication without printing any raw authentication material.
2. With HaotianAudio spatial rendering and tracking disabled, confirm there is
   no F3 stream request and earbud-local immersive sound remains unchanged.
3. Enable HaotianAudio tracking. Confirm one start request, F4/`0x21` samples at
   the expected roughly 30 ms cadence, and a stop request when tracking or the
   route is disabled.
4. Calibrate left/right yaw, up/down pitch, tilt/roll and mixed rotations. The
   Z/X/Y mapping is statically confirmed from stock, but signs and composition
   still require an audible physical test before treating them as final.
5. Test recenter, A2DP route changes, Bluetooth off/on, bud power cycle, service
   restart, official-app channel contention and long idle reconnect. No stale
   pose may survive a route or generation change.
6. Check user-build SELinux denials and verify that AirPods and Sony provider
   selection remains isolated.
