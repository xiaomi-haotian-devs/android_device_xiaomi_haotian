# Haotian charging settings

This platform-signed app exposes Xiaomi 15 Pro charging features through the
stock MiCharge HAL, Lineage PowerShare, and the small local
`haotian-charging-service` controller.

The app has no Internet permission and no cloud configuration path. Battery
anti-aging and wired top-speed charging are persisted by the local controller;
quiet wireless charging is reapplied locally after boot; bypass charging is
always cleared after a reboot or power disconnect. Xiaomi-specific limiting
features default to disabled and remain independently user-disableable.

Wireless charging firmware updates are never automatic. The UI checks battery
level, wireless receiving state, reverse charging state, and updater state,
then requires explicit confirmation before sending Xiaomi's update command.
