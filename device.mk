
#
# Copyright (C) 2023 The Android Open Source Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Use a device-specific multi-HAL list which retains the common UDFPS sub-HAL and adds the
# AirPods head tracker sub-HAL.
SM8750_SENSORS_HAL_CONFIG := device/xiaomi/haotian/configs/sensors/hals.conf

# Inherit from sm8650-common
$(call inherit-product, device/xiaomi/sm8750-common/common.mk)

# Get non-open-source specific aspects
$(call inherit-product, vendor/xiaomi/haotian/haotian-vendor.mk)

# Keep Google's LUI and APDU LPA for a removable eUICC in physical slot 2.
# Telephony reuses the modem-owned ISD-R channel configured by the framework
# overlay. Keep slot 2 out of non_removable_euicc_slots so Telephony can
# continue to detect either a pSIM or an eUICC from the inserted card at runtime.
PRODUCT_PACKAGES += \
    EuiccGoogle \
    XiaomiEuicc

# BtHelper is excluded before the common Evolution product is inherited in
# lineage_haotian.mk. PRODUCT_PACKAGES -= cannot remove a package contributed
# by another inherited product because product variables are merged later.

ifeq ($(TARGET_INCLUDE_FIRMWARE),true)
$(call inherit-product, vendor/xiaomi/haotian/firmware.mk)
endif

# Boot-only transparent artwork above the existing EvoX bootanimation.
PRODUCT_PACKAGES += bootanim_overlay

# Display
PRODUCT_PACKAGES += \
    FrameworkResOverlayHaotian \
    LineageSDKOverlayHaotian \
    SystemUIOverlayHaotian

# Read-only display pipeline diagnostics for LTPO development. The tool creates no Surface and
# does not subscribe to VSYNC, so observing the counters does not keep the panel at a high rate.
PRODUCT_PACKAGES_DEBUG += \
    haotian-refresh-debug

# Minimal Gatekeeper/FBE support for the built-in recovery.  The daemon mounts metadata-encrypted
# /data and installs DE keys automatically; CE credentials are supplied interactively over ADB.
PRODUCT_PACKAGES += \
    haotian-recovery-decrypt \
    haotian-recovery-gatekeeper \
    haotian-recovery-keymint \
    haotian-recovery-libGPreqcancel \
    haotian-recovery-libGPreqcancel_svc \
    haotian-recovery-libQSEEComAPI \
    haotian-recovery-libdiag \
    haotian-recovery-libdrmtime \
    haotian-recovery-libdrmfs \
    haotian-recovery-libgpt \
    haotian-recovery-libkeymasterdeviceutils \
    haotian-recovery-libkeymasterutils \
    haotian-recovery-libminkdescriptor \
    haotian-recovery-libops \
    haotian-recovery-libqcbor \
    haotian-recovery-libqisl \
    haotian-recovery-libqtigatekeeper \
    haotian-recovery-libqtikeymint \
    haotian-recovery-librpmb \
    haotian-recovery-libseclog \
    haotian-recovery-libspl \
    haotian-recovery-libssd \
    haotian-recovery-libtime_genoff \
    haotian-recovery-libtouchreport \
    haotian-recovery-libtouchreport_alg \
    haotian-recovery-libtouchreport_hal \
    haotian-recovery-qseecomd \
    haotian-recovery-touch \
    haotian-recovery-firmware.fstab \
    haotian-recovery-security.xml

# Xiaomi DisplayFeature is the sole owner of panel color processing. Do not expose or start
# Lineage LiveDisplay, whose color-temperature matrices would otherwise stack with EyeCare and
# True Tone when enabled.

# Xiaomi's face HAL stores templates and performs matching inside the MiTEE
# trusted VM. Sense remains installed as the alternate software backend.
PRODUCT_PACKAGES += \
    libcamera_metadata_miface \
    libmiface_noop

PRODUCT_SYSTEM_PROPERTIES += \
    persist.sys.face.backend=sense \
    persist.sys.face.miface.strength=weak \
    ro.face.miface.available=true

# Keep the stock camera provider in its dedicated cgroup after init starts it.
PRODUCT_PACKAGES += \
    libhaotian_camera_provider_cgroup

# Local-only charging controls. Limiting features default to off and can only
# be enabled by the user from the haotian charging settings page.
PRODUCT_PACKAGES += \
    HaotianCharging \
    HaotianAudio \
    HaotianAirPods \
    HaotianMiBuds \
    HaotianSony \
    haotian-charging-service \
    sensors.haotian_headtracker

PRODUCT_VENDOR_PROPERTIES += \
    ro.vendor.all_modes.colorpick_adjust=true \
    persist.vendor.batteryantiaging=0 \
    persist.vendor.haotian.batteryantiaging=0 \
    persist.vendor.haotian.fast_charge=0 \
    persist.vendor.mifaced.fastlaunch=false \
    ro.vendor.miface.started=true

# Xiaomi's OZO recording controller reads these from mi_ext on stock. system_ext is the
# equivalent partition in this tree, so keep the original names and haotian device UUID.
PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.audio.audiozoom=true \
    ro.audio.ozo.channelmask.in=true \
    ro.ozo.uuid.parm=CC3BBB96-CE6D-415D-9FE4-9C3A987175F2

# AirPods expose their secondary Classic ATT bearer and Apple-style Smart Routing behavior only
# when the phone's SDP Device ID identifies an Apple-vendor peer. Set the same public Device ID
# fields used by LibrePods' runtime hook at ROM build time, avoiding an injected hook in the
# Bluetooth process.
PRODUCT_SYSTEM_PROPERTIES += \
    bluetooth.device_id.vendor_id=76 \
    bluetooth.device_id.vendor_id_source=1

PRODUCT_COPY_FILES += \
    device/xiaomi/haotian/configs/displayconfig/display_id_4630946654109872275.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/displayconfig/display_id_4630946654109872275.xml \
    device/xiaomi/sm8750-common/rootdir/etc/ueventd.qcom.rc:$(TARGET_COPY_OUT_RECOVERY)/root/vendor/etc/ueventd.rc \
    vendor/xiaomi/haotian/proprietary/odm/firmware/haotian_syna_thp_config.ini:$(TARGET_COPY_OUT_RECOVERY)/root/lib/firmware/haotian_syna_thp_config.ini \
    vendor/xiaomi/haotian/proprietary/odm/firmware/synaptics_spi_haotian.img:$(TARGET_COPY_OUT_RECOVERY)/root/lib/firmware/synaptics_spi_haotian.img \
    $(LOCAL_PATH)/configs/permissions/evolution.software.compact_window.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/evolution.software.compact_window.xml \
    $(LOCAL_PATH)/configs/permissions/privapp-permissions-haotian-audio.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-haotian-audio.xml \
    $(LOCAL_PATH)/configs/permissions/unavailable-feature-livedisplay.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/unavailable-feature-livedisplay.xml

# Xiaomi Camera directly references android.media.AudioParaManger. Expose only that small
# compatibility surface on the boot class path instead of importing HyperOS' miui-framework.jar.
PRODUCT_PACKAGES += \
    haotian-ozo-camera-compat

PRODUCT_BOOT_JARS += \
    haotian-ozo-camera-compat

# MiuiCamera permissions
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/permissions/default-permissions-miuicamera.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/default-permissions/default-permissions-miuicamera.xml \
    $(LOCAL_PATH)/configs/permissions/miuicamera-hiddenapi-package-whitelist.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/miuicamera-hiddenapi-package-whitelist.xml \
    $(LOCAL_PATH)/configs/permissions/privapp-permissions-miuicamera.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-miuicamera.xml

# Xiaomi Gallery is kept as a secondary gallery for MiuiCamera review and the
# stock editing suite. MediaViewer supplies its private video playback, frame
# extraction and video-edit handoff without overriding Google Photos.
PRODUCT_PACKAGES += \
    MediaViewer \
    MIUIGallery \
    MiMediaEditor \
    MiuiExtraPhoto

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/permissions/privapp-permissions-miui-gallery.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-miui-gallery.xml

# HyperOS stock NFC/eSE userspace. This is deliberately gated by the release
# config so the APEX and stock com.android.nfc packages can never coexist.
ifeq ($(RELEASE_PACKAGE_NFC_STACK),NfcNci)
PRODUCT_PACKAGES += \
    HaotianFindDeviceCompat \
    haotian-miui-account-compat \
    android.hardware.se.omapi.ese.prebuilt.xml \
    android.hardware.se.omapi.uicc.prebuilt.xml \
    HaotianMiPayWallet \
    HaotianMiSmartCards \
    HaotianMiSmartCardsWebExtension \
    HaotianNfcResourcesOverlay \
    HaotianRtMiCloudSDK \
    HaotianStockNfc \
    HaotianStockSecureElement \
    HaotianStNfcExtensionService \
    HaotianUpTsmService \
    HaotianXiaomiAccount \
    HaotianXiaomiAccountBridge \
    XiaomiCtaBroker \
    com.st.android.nfc_extensions \
    com.st.android.nfc_extensions_16 \
    com.xiaomi.nfc

# XiaomiAccount calls these small HyperOS compatibility surfaces when opening
# its full account settings. Keep the stock account APK untouched: the boot jar
# exposes the AccountManager-backed API, while HaotianFindDeviceCompat reports
# the intentionally absent Xiaomi Find Device stack as disabled.
PRODUCT_BOOT_JARS += \
    haotian-miui-account-compat

# Select the ST proprietary command bridge bundled in Nfc_st.apk. Its legacy
# property name is labelled nfc_prop below so the nfc domain can read it with
# SELinux enforcing.
PRODUCT_SYSTEM_PROPERTIES += \
    persist.nfc_vendor_extn.lib_file_name=libnfc_vendor_extn_st.so

PRODUCT_COPY_FILES += \
    packages/modules/Nfc/NfcNci/com.android.nfc.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-haotian-nfc.xml \
    $(LOCAL_PATH)/configs/sysconfig/haotian-wallet-app-links.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/haotian-wallet-app-links.xml \
    $(LOCAL_PATH)/configs/nfc/hal_uuid_map_haotian.xml:$(TARGET_COPY_OUT_VENDOR)/etc/hal_uuid_map_haotian.xml \
    vendor/xiaomi/haotian/stock-nfc/permissions/hiddenapi-package-whitelist-haotian-nfc.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/hiddenapi-package-whitelist-haotian-nfc.xml \
    vendor/xiaomi/haotian/stock-nfc/permissions/com.st.android.nfc_extensions.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/com.st.android.nfc_extensions.xml \
    vendor/xiaomi/haotian/stock-nfc/permissions/com.st.android.nfc_extensions_16.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/com.st.android.nfc_extensions_16.xml \
    vendor/xiaomi/haotian/stock-nfc/permissions/com.xiaomi.nfc.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/com.xiaomi.nfc.xml \
    vendor/xiaomi/haotian/stock-nfc/permissions/privapp-permissions-st-nfc-extension.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-st-nfc-extension.xml \
    vendor/xiaomi/haotian/stock-nfc/permissions/privapp-permissions-mi-smart-cards.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-mi-smart-cards.xml \
    vendor/xiaomi/haotian/stock-nfc/permissions/privapp-permissions-mi-smart-cards-web.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-mi-smart-cards-web.xml \
    $(LOCAL_PATH)/configs/permissions/privapp-permissions-xiaomi-cta-broker.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-xiaomi-cta-broker.xml \
    vendor/xiaomi/haotian/stock-nfc/permissions/privapp-permissions-xiaomi-account.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-xiaomi-account.xml
endif

# Touch
PRODUCT_PACKAGES += \
    HaotianTouch

# Vibrator
PRODUCT_PACKAGES += \
    HaotianHaptics \
    android.hardware.vibrator-service.xiaomi-sm8750

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)
