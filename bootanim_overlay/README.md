# Haotian boot animation overlay

Integrated from `bootanim_overlay_complete.zip` into the device tree. The existing
EvoX bootanimation and its ZIP are unchanged. This is a separate transparent
Surface on layer stack 0 at Z `0x40000001`, above BootAnimation's `0x40000000`.

## Artwork and behavior

- `assets/` contains the user's original 12 RGBA PNGs, in 6 groups, all 400x400.
  The files are copied byte-for-byte, without resizing or re-encoding.
- Choose one group per process start. Groups 001/004/006 hold a single frame;
  groups 002/003/005 loop their three frames at one-second intervals.
- Frames are bottom-centered, unscaled, with premultiplied alpha. Only the
  selected group is decoded (about 1.83 MiB RGBA for a three-frame group).
- The init service starts with `init.svc.bootanim=running` and stops with
  `init.svc.bootanim=stopped`. A property watcher also requests process exit.
  `service.bootanim.exit=1` does NOT stop the overlay, preserving ending parts.
- `sys.boot_completed` is checked only at process entry to reject post-boot
  starts, not watched as a stop condition. Shutdown invocations are rejected.
- This device implementation targets the first physical display and its initial
  boot mode. Orientation and maximum graphics size follow this branch's
  BootAnimation rules. Live display-mode changes and multi-display rendering
  are not implemented.

## Review fixes

The ZIP's checked-in `embedded_assets.cpp` contained only 6 old placeholder PNGs
in 3 groups, not the artwork in `assets/`. It is deliberately not imported.
Instead, a Soong genrule supplies every `assets/*/*.png` as an explicit input to
`tools/embed_png.py`, generating the C++ byte arrays in the build output. No
manual regeneration is needed after artwork changes. The generator rejects
duplicate numeric group IDs and invalid/oversized PNG headers and uses stable
natural frame ordering.

The module uses the current branch's typed LayerStack API and C++20, declares
`jni_headers` for `android/bitmap.h`, and has a local license definition rather
than depending on the unrelated frameworks/base package license. The package's
original LICENSE notice is retained; artwork rights remain with its suppliers.

Runtime fixes include a mutex-protected stop notification, checks before showing
an already-ended boot session, transaction error handling, decode dimension
limits, and alignment with BootAnimation's maximum graphics size properties.
The one-frame-per-second overlay does not request the original package's
real-time I/O priority or MaxPerformance task profile.

## Integration and verification

`device.mk` installs `bootanim_overlay`. Installed files:

- `/system_ext/bin/bootanim_overlay`
- `/system_ext/etc/init/bootanim_overlay.rc`

The device's system_ext private `file_contexts` labels the binary `bootanim_exec`,
reusing the platform `bootanim` domain and its existing SurfaceFlinger/allocator
permissions. No permissive domain or new broad SELinux allow rules are added.

Only static/source and interpreted resource checks were performed during
integration; no compilation, flashing, or device boot verification was run.
After the user builds and flashes, check:

```sh
adb shell ls -Z /system_ext/bin/bootanim_overlay
adb shell getprop init.svc.bootanim_overlay
adb logcat -b all -d -s BootAnimOverlay
```

During boot, verify the process domain is `u:r:bootanim:s0`, the artwork appears
above the original animation, and it remains through any ending part. After
boot, both animation services should be stopped with no residual overlay.
Check AVC logs if the service cannot launch or draw. Random group selection
means observing all six groups may require multiple boots.
