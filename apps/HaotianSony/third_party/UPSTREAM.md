# libmdr snapshot

Source: https://github.com/mos9527/SonyHeadphonesClient
Revision: 965c458116d40827494726447de5f07eb50efcb8 (v1-compat)
License: MIT, retained in LICENSE.

Only libmdr sources, public headers and committed generated sources are imported.
The desktop UI, BlueZ/DBus transport and code-generation build are not used.
Android supplies the connected RFCOMM descriptor through BluetoothSocket.
The native integration uses MDR_ABI_VERSION=1 with three private, statically linked
C extensions (`mdrHeadphonesHasReportedControl`, `mdrHeadphonesGetEqualizerPresets`,
`mdrHeadphonesRequestReadback`).
This copy is not installed as a general-purpose public libmdr ABI.
Soong obtains fmt through libbase, which owns and exports fmt in this tree.

Local changes:
- Common control writes use scoped GET readback, retaining capabilities and waiting
  for fresh property reports independently of transport ACK order. Uncommon actions
  retain full initialization. Empty EQ responses clear stale band data.
- Task move assignment releases the previous completed frame instead of leaking
  it on every operation, including owned scoped-readback keys.
- Task completion resumes an existing continuation directly and otherwise remains
  suspended for collection. This avoids the Android toolchain's noop-coroutine
  stub, which lacks a BTI landing pad and triggers SIGILL on BTI-enabled ARM64.
  BTI/PAC remain enabled; task nesting is limited to protocol dispatch wrappers.
- Coroutine deadlines use std::chrono::steady_clock instead of CPU time.
- Receive buffering is bounded; EOF and malformed sessions are disconnected by
  the Android transport, and diagnostic packet contents are not logged.
- Properties distinguish a device response from a locally committed cache entry;
  full initialization clears the V2 observation epoch before readback.
- Unknown batteries/sound-pressure values use a sentinel instead of reporting 0.
- V2 EQ preset capability query/handler and C accessor; UI sends only advertised presets.
- V2 assignable-control capability query/handler and C get/actions/set support for
  the [left, right] layout supported by the upstream V2 commit path. Unrecognized
  layouts/actions are not offered; unchanged protocol variants are preserved.
