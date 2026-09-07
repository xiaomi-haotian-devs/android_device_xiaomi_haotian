# haotian standard vibrator HAL

This service implements `android.hardware.vibrator.IVibrator/default` over the
CS40L26 input force-feedback interface. Framework vibration policy, SystemUI,
Launcher and the existing keyboard overlay remain responsible for interaction
semantics.

## Gain and supported effects

The device's prebuilt `cs40l26-core.ko` implements `cs40l26_set_gain` with a
0..100 range check and queues `cs40l26_set_gain_worker`. The worker writes the
DSP attenuation register using `cs40l26_attn_q21_2_vals`. This driver does not
use the generic evdev 0..65535 gain range.

The HAL advertises amplitude control only when the opened input device reports
`FF_GAIN`. `setAmplitude` maps (0, 1] to integer percentage gains, matching
`on()` at full strength with the selected profile. NaN, infinities and out-of-range values
are rejected. Driver work is asynchronous: successful writes confirm enqueueing,
not physical settling or a measured acceleration.

Primitive scaling is monotonic, with zero producing silence and nonzero scales
quantized to the driver's integer gain steps. Full-scale LIGHT_TICK and LOW_TICK
retain gains of 80 and 75 in the balanced profile. By default, a square-root
response makes quiet requests more noticeable without flattening their scale
ordering. Disabling subtle-feedback enhancement restores a linear response.
Soft/balanced/crisp multiply gains by 0.8/1.0/1.1, capped at 92 percent. These
are strength profiles, not different waveform shapes or measured acceleration
curves. Quantization and the motor's perceptual threshold still require testing.

The platform-signed `HaotianHaptics` system_ext app adds a tile to Sound settings,
with English, Simplified Chinese and Traditional Chinese resources. Its dedicated
SELinux domain writes only the two `persist.sys.haotian.haptics.*` tuning
properties; the vendor HAL reads them. Changes apply to subsequent requests
(or amplitude updates), without restarting the HAL. Only the system user can
change these device-wide values. Previews use normal touch-vibration policy and
are cancelled when leaving the page. No system intensity/switch is overridden.

Framework `PRIMITIVE_TICK` and HAL `CompositePrimitive::LIGHT_TICK` are the same
primitive (ID 7). It was already supported; no additional TICK enum is required.
SPIN and SLOW_RISE remain unsupported until appropriate waveforms are verified.
Frequency/PWLE and external-control capabilities are also not advertised.
Predefined TICK and TEXTURE_TICK retain their existing waveform tuning; changing
their physical distinction requires listening/feel tests on the device.

## Sequence lifecycle

One joinable worker executes all delayed pulses and completions using a steady
clock. A new valid request or `off()` clears the previous pending sequence under
the device lock. Destruction cancels playback and joins the worker before closing
the input file descriptor. No detached threads retain the HAL object.

Multiple audible pulses first use the CS40L26 composite OWT format, uploading
the whole sequence once so the firmware owns inter-pulse timing. RAM IDs remain
the existing Xiaomi mappings. Initial silence, inter-pulse delays and trailing
silence are retained; zero-scale primitives contribute their duration as silence.
The encoding follows the public Google CS40L26 implementation, but compatibility
with this firmware still needs verification. If upload or playback submission
fails, a serialized userspace sequence is used; EINVAL/ENOTSUP disables further
OWT attempts until HAL restart. Silent-only and single-pulse requests do not use OWT.

The initial immediate software pulse is submitted synchronously so its errors
reach the caller. Subsequent pulse failures terminate the sequence, log the error
and release the framework completion wait. Software fallback waits for actual
completion before advancing and retains requested pauses; delayed pulses are no
longer skipped. It may stretch timing under load and is not hardware-atomic.

Completion tracks an idle-to-active-to-idle `vibe_state` transition. Initial idle
is not treated as completion: the driver may still be waking from hibernation.
Polling is every 2 ms while pending. A 100 ms startup allowance plus estimated
playback time bounds missing/unobserved state; active playback has an additional
80 ms watchdog allowance. OWT adds 50 ms per audible section to its watchdog.
Callbacks also respect the nominal sequence end. Cleanup precedes notification.
RAM duration estimates are provisional (30–40 ms), based on warm-device traces,
not a calibrated firmware duration table. An unobserved transition can still
delay completion until the watchdog, and explicit cancellation always stops it.
Cancellation suppresses completion for a pending sequence; an already completed
sequence may have a Binder notification in flight.

Short-waveform caching is limited to 16 entries. An inactive entry is erased
before a new entry is added at the limit. Cached effects are stopped between
plays; noncached effects are stopped and erased. Stop/erase errors are propagated
and retain the current effect ID for a subsequent cleanup attempt.

## Validation after the user builds and installs

Existing installed-HAL playback and kernel traces informed these changes. The
new source has only been statically reviewed: it has not been compiled, installed
or played on hardware. Validate the resulting binary with:

1. `adb shell dumpsys vibrator_manager`: verify amplitude control and primitive
   IDs, including ID 7. No SPIN or PWLE capability should appear.
2. Compare a long one-shot and a waveform with ascending/descending nonzero
   amplitudes. Timing must remain stable while intensity changes. Repeat with
   touch intensity settings at low, medium and high.
3. Exercise keyboard, back gestures, slider drags, long presses and double-click
   effects. Full-scale keyboard/back tuning should remain familiar; weak
   primitive scales should remain distinguishable with and without enhancement.
4. Cancel a composition during its initial delay, between pulses and while
   vibrating. Replace it with another request. Old pending pulses must not play
   or stop the replacement sequence.
5. Repeat varied primitive scales more than 16 times, including after idle.
   Check HAL logs for FF slot exhaustion, missed effects and cleanup errors.
6. With an AIDL test client, verify NaN/infinite/out-of-range amplitude and scale
   rejection, zero-scale silence with preserved timing, NOOP-only completion,
   and exactly one completion for each normally finished sequence.
7. Check completion and cancellation with `vibe_state` available and unavailable,
   and under scheduling load. Confirm delayed effects do not accumulate a burst.
8. Open Xiaomi haptic settings under Sound. Verify all profiles, enhancement,
   reset, previews, persistence across reboot, secondary-user restrictions and
   absence of SELinux denials. Preview must obey touch-vibration settings.
9. After several seconds idle, repeat isolated ticks and multi-pulse ramps.
   Compare trigger/completion mailbox events with OWT submission and check logs
   for fallback. Confirm no truncated first pulse, missing sections or timing
   regressions; do not treat a successful upload alone as validation.

Do not infer waveform quality, live amplitude response or scheduling precision
from static inspection alone.
