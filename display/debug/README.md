# Haotian refresh-rate diagnostics

`haotian-refresh-debug` is a line-oriented device-side monitor for LTPO bring-up. It does not
create a Surface, turn on SurfaceFlinger's refresh-rate overlay, or register a VSYNC receiver.
It only samples cached SurfaceFlinger state and read-only Xiaomi display sysfs nodes.
The package, shell policy, SF shell access, and present counter are enabled only in `userdebug` and
`eng`; production `user` builds do not include or activate this diagnostic path.
This is selected from Soong's compile-time `Debuggable` product variable, not `ro.debuggable` or
`ro.build.type`: Lineage-based daily userdebug builds intentionally publish both properties as
production values while retaining the userdebug build graph.

Run it from an authorized adb shell after flashing the matching framework and system image:

```sh
adb shell haotian-refresh-debug
adb shell haotian-refresh-debug --interval-ms 250 --count 100
adb shell 'haotian-refresh-debug --csv > /data/local/tmp/refresh.csv'
adb pull /data/local/tmp/refresh.csv
```

Fields:

- `sf_target`: frame cadence selected by SurfaceFlinger's refresh-rate policy.
- `sf_present`: completed SurfaceFlinger presents per second over the sampling interval. A static
  screen can correctly report zero even while the panel continues self-refreshing.
- `mode` / `hwc` / `group` / `nominal`: selected Android and vendor HWC mode identifiers and the
  mode's nominal TE rate.
- `hw_te`: the kernel's `hw_vsync_info` measurement.
- `driver`: `dynamic_fps`; this is only a nominal value for Xiaomi idle groups.
- `ddic_req`: the request encoded by the current Xiaomi vendor mode group. `idle/10Hz` and
  `idle/1Hz` are explicit idle minimums; `auto` leaves the DDIC choice to Xiaomi's automatic mode.
- `ddic_closed`: the FPS bucket with the largest newly settled `disp_count` delta. Xiaomi's kernel
  does not update these counters continuously: it settles a bucket after leaving that panel state.
  `none` therefore means "no state was closed since the previous sample", not an unknown or invalid
  refresh rate. The raw non-zero deltas are printed in `residence=[...]`; their magnitudes are
  kernel-private counter units and are deliberately not converted into frames per second. To prove
  1 Hz, leave the screen static in `idle1`, then touch it and look for a subsequent
  `ddic_closed=1Hz` event.

If the matching SurfaceFlinger transaction is missing or denied, the monitor reports the error
once and continues with `sf=unavailable`, `hw_te`, `driver`, and DDIC settlement output. This makes
framework/image version mismatches visible without losing the kernel-side capture.

The SurfaceFlinger snapshot uses private read-only transaction 1048. The tool and framework patch
must therefore come from the same build. SurfaceFlinger grants only this transaction to the shell
UID, and device policy grants shell read-only access to the displayfeature sysfs nodes. No daemon,
root transition, writable sysfs permission, or new SELinux domain is required.
