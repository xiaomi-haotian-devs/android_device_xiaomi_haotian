# Haotian Find Device compatibility service

The stock Xiaomi Account center binds the explicit HyperOS component
`com.xiaomi.finddevice/.v2.FindDeviceStatusManagerService` while constructing
its main settings page. When the full Find Device package is absent, Xiaomi's
client library throws from an `AsyncTask` and terminates the account process.

This product-privileged, platform-signed app implements only transaction 1 of
`miui.cloud.finddevice.IFindDeviceStatusManagerAsync`. It returns a non-null
`FindDeviceInfo` with `isOpen=false`, `isLocked=false`, and no identifiers.
Mutating or server-backed transactions report the service as unavailable.

The app requests no permissions and contains no network, location, cloud,
device-administrator, receiver, provider, job, or launcher components. It is
not an implementation of device finding; it only keeps the complete Xiaomi
Account settings UI from crashing when that optional HyperOS subsystem is not
shipped.
