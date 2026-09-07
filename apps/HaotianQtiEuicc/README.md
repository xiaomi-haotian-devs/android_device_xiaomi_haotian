# Haotian Qualcomm eUICC bridge (experimental, not installed)

This adapter is retained only as research/reference code. `device.mk` does not
include `HaotianQtiEuicc`, so neither this app nor its required Qualcomm
product-side LPA components are installed in the Haotian product image.

Runtime testing showed that the active CN modem configuration accepts the
Qualcomm ADD_PROFILE request but rejects it at progress zero before any HTTP
transaction. Haotian therefore uses `EuiccGoogle` and the framework APDU LPA,
with the device-specific modem-owned ISD-R channel fallback instead.

This privileged `EuiccService` keeps the existing Android/Google eSIM UI while
routing card-management operations to Qualcomm's modem-side LPA userspace. Its
intent-filter priority is 101, above the priority-100 Google and Qualcomm
services, so `EuiccConnector` selects this adapter deterministically.

The adapter uses a local, wire-compatible definition of Qualcomm's protected
`IUimLpaService` Binder API. This avoids compiling against the proprietary dex
library while leaving the original service, stable vendor AIDL client, and HTTP
relay in the companion `uimlpaservice` prebuilt.

Downloads are serialized. Confirmation-code and profile-policy prompts are
translated to Android resolvable errors; a resolved attempt starts a fresh
Qualcomm ADD_PROFILE transaction. Request acceptance is not treated as download
success: the adapter waits for Qualcomm's separate installation-complete status.
A requested post-download switch is performed only when a reliable before/after
profile comparison identifies exactly one new ICCID, preventing an older
disabled profile from being enabled accidentally.

Initial runtime validation should be read-only:

1. Confirm this component is the selected `EuiccService`.
2. Confirm the Qualcomm backend binds and registers its callback.
3. Query EID, then profile list.
4. Attempt a real download only after those operations succeed.

No modem configuration, NV item, channel ownership, or card-reset behavior is
changed by this app. If Qualcomm's vendor HAL rejects GET_EID/GET_PROFILES, the
missing modem-product LPA enablement must be solved separately.
