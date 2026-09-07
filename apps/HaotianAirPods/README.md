# HaotianAirPods

HaotianAirPods is the AirPods transport, feature and settings service for the Xiaomi haotian device
tree. It is the sole owner of the AirPods Classic L2CAP AACP channel on PSM `0x1001` and the
secondary ATT bearer on PSM 31.

The platform-signed system app is persistent so transport, wear, Conversation Awareness and Smart
Routing state survives between UI components. Settings, metadata, the Quick Settings tile and local
policies use a same-package Binder facade over one process-wide `AirPodsController`. The controller
follows A2DP connection broadcasts itself; HaotianAudio is a consumer and is not required to start
or retain the accessory control channel.

This component is licensed under the GNU General Public License, version 3 or (at your option)
any later version (`GPL-3.0-or-later`). Protocol behavior and feature organization are derived
from LibrePods. Portions of the metadata integration were also informed by BtHelper. See each
source file's SPDX header for attribution.

The GNU GPL version 3 license text is available at <https://www.gnu.org/licenses/gpl-3.0.txt>.

## Current integration phase

The settings hierarchy follows LibrePods and uses the platform SettingsLib Material 3 Expressive
theme. Stable AACP controls cover name, listening mode, left/right press-and-hold configuration,
call-control ordering, personalized volume, conversation awareness, adaptive strength, automatic
ear detection/connection, microphone mode, sleep detection, optimized charging and accessibility
controls.

The second phase adds LibrePods-compatible custom EQ (`0x63`), hearing-protection PPE control,
loud-sound reduction over ATT handle `0x1b`, and lossless transparency customization over handle
`0x18`. Transparency edits include enable state, amplification, balance, tone, ambient noise
reduction, conversation boost and all eight EQ bands. Partial edits preserve the complete value
reported by the accessory, including the other channel and optional trailing fields, and controls
are disabled until their required transport/value is available.

Hearing Aid follows LibrePods' own hierarchy and wire model: the combined `0x2c`/`0x33` enable
state, stem gain control (`0x2f`), adjustments, and per-ear eight-band hearing-test values are
available only after a structurally valid ATT `0x2a` block is reported. Edits preserve every
unexposed field and trailing byte. Enabling it requires confirmation and disables custom
Transparency mode, matching the mutually exclusive accessory behavior.

Automatic ear detection also drives a local media policy derived from LibrePods: media is paused
when the last bud is removed or either bud is removed from a two-bud session, and is resumed only
when this policy was the component that paused it. The first state after process/session startup is
used as a baseline, so a stale persisted wear state cannot unexpectedly start playback.

Conversation Awareness notifications (`0x4b`) are interpreted locally. The LibrePods start/end
states lower media to a configurable 10–85 percent target, either relative to the current volume or
to the stream maximum, and can optionally pause playback. The target stream position is converted
through Android's Bluetooth volume curve, then HaotianAudio applies the resulting gain with a
route-scoped `VolumeShaper`; the stored music volume and visible slider never move. Conversation
end, disconnect and client death reverse the shaper, while Volume Up cancels it immediately—even at
the maximum volume index.

An optional AOSP Quick Settings tile follows LibrePods' cycle behavior. It binds to the local
control service only while SystemUI is listening, reflects the reported mode and device
name, and cycles Off (when allowed), Transparency, Adaptive, and Noise Cancellation without owning
another Bluetooth connection.

Optional head gestures run only while an incoming call is ringing. The AACP decoder fans each pose
out locally to the gesture detector and, when requested, to HaotianAudio's shared-memory writer.
Spatial audio and gestures therefore share one Bluetooth session; a consumer reference count stops
high-rate motion only after both have released it.

HaotianAudio binds the exported, signature-protected generic pose provider for the active audio
route. Only normalized quaternion/angular-velocity records and low-rate left/right wear state cross
that boundary. Complete `AirPodsState`, raw AACP messages and all control writes remain here under
the GPL-3.0-or-later component.

The remaining LibrePods local-device and multi-device behaviors are later phases; their menu
positions remain reserved where applicable.
