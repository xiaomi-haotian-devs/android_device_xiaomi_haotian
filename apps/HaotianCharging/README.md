# MiCharge settings

This platform-signed app exposes Xiaomi 15 Pro charging features through the
stock MiCharge HAL, Lineage PowerShare, and the small local
`haotian-charging-service` controller.

The app has no Internet permission and no cloud configuration path. Battery
anti-aging and wired top-speed charging are persisted by the local controller;
the selected quiet/standard/top-speed wireless dock profile is reapplied
locally after boot and after transmitter authentication; bypass charging is
always cleared after a reboot or power disconnect. Xiaomi-specific limiting
features default to disabled and remain independently user-disableable.

Reverse charging follows Xiaomi's stock kernel timeout and is never kept alive
by userspace. The settings page periodically reads the resulting PowerShare
state back from the HAL. NFC coordination follows HyperOS' reverse-charge
uevent and ownership marker; ordinary wireless charging can opt into the same
disable-and-restore behavior.

The dock page reads Xiaomi MCA telemetry through the local vendor service:
transmitter type and UUID, receiver voltage/current/power, rectifier voltage,
wireless IC temperature, alignment, signal/CEP, requested fan level, fast
charge status, car-dock detection, and the phone wireless IC firmware version.
Only Xiaomi's stock fan requests are exposed (standard 4 and top-speed 7);
quiet mode remains driver-managed and all thermal protections stay active.

Wireless charging firmware updates are never automatic. The UI checks battery
level, wireless receiving state, reverse charging state, and updater state,
then requires explicit confirmation before sending Xiaomi's update command.
The updater flashes the firmware image embedded in the Nuvolta kernel module;
it never downloads a payload or updates the external charging dock firmware.
