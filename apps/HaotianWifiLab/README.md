# Haotian Wi-Fi laboratory

This is a platform-signed, separately identified diagnostics application for Xiaomi 15 Pro
(`haotian`). It deliberately does not use `android.uid.system`.

The UI reuses SettingsLib's expressive sub-settings theme, collapsing toolbar, preference rows,
switches, typography, spacing, and system dynamic colors. It is intended to look and behave like a
native Android Settings page.

## Installation model

`HaotianWifiLab` is a buildable module but is not included in `PRODUCT_PACKAGES`. Build it only when
needed, then install that APK manually. Its platform certificate must match the certificate of the
running ROM; otherwise Android will not grant `NETWORK_SETTINGS` or
`MANAGE_WIFI_COUNTRY_CODE`. The APK also requires the matching `packages/modules/Wifi` framework
patch from this source tree.

Runtime location and nearby-device permission is requested for connection details, the active
driver country callback, and regulatory channel queries.

## Scope and safety boundary

The application can:

- inspect connection, chip capability, active/resolved country, and allowed 6 GHz channels;
- temporarily override Android's resolved country code;
- temporarily override 13 explicitly allowlisted boolean resources in `WifiResourceCache`;
- apply the compatible Wi-Fi values extracted from HyperOS OS3.0.302.0;
- control framework verbose logging and application scan throttling.

The framework hook accepts enum IDs rather than arbitrary resource names. Every access enforces
`NETWORK_SETTINGS`. Overrides are in-memory and are removed by reboot, Wi-Fi service restart, or
the restore action.

This application cannot modify firmware calibration, SAR tables, antenna configuration, transmit
power, modem policy, or regulatory enforcement in the driver/firmware. A framework switch is only
permission to request a capability; lower layers remain authoritative.
