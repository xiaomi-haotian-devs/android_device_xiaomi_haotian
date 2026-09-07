# Jieli authentication primitive

`arm64-v8a/libjl_bluetooth.so` is copied without modification from
`jl_bluetooth_rcsp_V4.2.0_40250-release.aar` in the Apache-2.0 licensed
[`Jieli-Tech/Android-JL_Bluetooth`](https://github.com/Jieli-Tech/Android-JL_Bluetooth)
repository at commit `6293873b641ffd8d461773873ecedef1a9d9038d`
(2026-07-01).

The upstream repository's exact license text is retained alongside this file
as `LICENSE`; the app's own Apache-2.0 text remains at the module root.

Upstream file SHA-256:
`7434a5f4e93664db98a8f01cd17ed236c15c82a372953e4e5fe7a18333c0ff57`.

Only the library's mutual-authentication JNI entry points are used. HaotianMiBuds
does not import Jieli's connection manager, RCSP implementation, firmware updater,
file browser or device-management APIs.
