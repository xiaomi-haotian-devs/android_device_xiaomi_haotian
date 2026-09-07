# Qualcomm modem LPA userspace

These Android 16 product-side blobs complement haotian's existing
`vendor.qti.hardware.radio.lpa.IUimLpa/UimLpa{0,1}` stable AIDL services.

Source: `ThankYouMario/proprietary_vendor_qcom_common`, branch `beryl`, commit
`846afd36fe782e8a93c4e9ea8425a0c0866e33a3` (`LA.QISI.16.0.r2-00900-qssi.0`).

The APK's embedded AIDL interface hash is
`97897c77c52681cded4336885835d7739b3f3f5b`, matching the haotian vendor HAL.
The APK is re-signed with the ROM platform certificate by Soong.

Imported-file SHA-256 values:

* `uimlpaservice.apk`: `f2f0f0a45614142bea4870b715a5b75ba339bfb43f29ec0a98892f29b2c98db9`
* `uimlpalibrary.jar`: `22fc8491a2e0717838d4ffde805304529df161da153eaaa071858ed040c1e6e2`
* `libjni_aidl_service.so`: `f84567e0a272116b7ab04f10282422261aa063927fc210ffe8422a9e9857859a`

`HaotianQtiEuicc` supplies the missing Android `EuiccService` download adapter.
The prebuilt APK's own Android-facing download method is only a stub; its lower
`UimLpaService` Binder, stable-AIDL modem client, and HTTPS relay are complete.
The accompanying sysconfig disables only that stub `QtiEuiccServiceImpl`
component, preventing a priority tie with Google's service while leaving the
lower Binder backend enabled.
QCOM's existing product `seapp_contexts` runs this package in the
`vendor_qtelephony` app domain, which is already a client of the vendor
telephony HAL. A normal `platform_app` domain cannot discover the
`vendor_hal_telephony_service2` stable-AIDL service on this vendor image.

This is an experimental device integration. The active haotian CN modem
configuration does not advertise Qualcomm's optional LPA terminal-capability
items, so the vendor HAL may still reject the requests. Validate EID and profile
listing before attempting a download. The Qualcomm network security policy also
trusts user-installed CAs and its debug logging includes HTTP URLs and headers;
do not collect or publish verbose LPA logs containing provisioning credentials.
