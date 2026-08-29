
#
# Copyright (C) 2023 The Android Open Source Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from sm8650-common
$(call inherit-product, device/xiaomi/sm8750-common/common.mk)

# Get non-open-source specific aspects
$(call inherit-product, vendor/xiaomi/haotian/haotian-vendor.mk)

ifeq ($(TARGET_INCLUDE_FIRMWARE),true)
$(call inherit-product, vendor/xiaomi/haotian/firmware.mk)
endif

# Display
PRODUCT_PACKAGES += \
    FrameworkResOverlayHaotian \
    SystemUIOverlayHaotian

# Keep the stock camera provider in its dedicated cgroup after init starts it.
PRODUCT_PACKAGES += \
    libhaotian_camera_provider_cgroup

PRODUCT_COPY_FILES += \
    device/xiaomi/haotian/configs/displayconfig/display_id_4630946654109872275.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/displayconfig/display_id_4630946654109872275.xml

# MiuiCamera permissions
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/permissions/default-permissions-miuicamera.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/default-permissions/default-permissions-miuicamera.xml \
    $(LOCAL_PATH)/configs/permissions/miuicamera-hiddenapi-package-whitelist.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/miuicamera-hiddenapi-package-whitelist.xml \
    $(LOCAL_PATH)/configs/permissions/privapp-permissions-miuicamera.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-miuicamera.xml

# Xiaomi Gallery is kept as a secondary gallery for MiuiCamera review and the
# stock editing suite. It intentionally does not override Google Photos.
PRODUCT_PACKAGES += \
    MIUIGallery \
    MiMediaEditor \
    MiuiExtraPhoto

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/permissions/privapp-permissions-miui-gallery.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-miui-gallery.xml

# HyperOS stock NFC/eSE userspace. This is deliberately gated by the release
# config so the APEX and stock com.android.nfc packages can never coexist.
ifeq ($(RELEASE_PACKAGE_NFC_STACK),NfcNci)
PRODUCT_PACKAGES += \
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
    XiaomiCtaBroker \
    com.st.android.nfc_extensions \
    com.st.android.nfc_extensions_16 \
    com.xiaomi.nfc

# XiaomiAccount calls this small HyperOS framework API when opening its
# account settings. Keep the stock account APK untouched and expose only the
# Android AccountManager-backed method it actually uses.
PRODUCT_BOOT_JARS += \
    haotian-miui-account-compat

# Select the ST proprietary command bridge bundled in Nfc_st.apk. Its legacy
# property name is labelled nfc_prop below so the nfc domain can read it with
# SELinux enforcing.
PRODUCT_SYSTEM_PROPERTIES += \
    persist.nfc_vendor_extn.lib_file_name=libnfc_vendor_extn_st.so

PRODUCT_COPY_FILES += \
    packages/modules/Nfc/NfcNci/com.android.nfc.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-haotian-nfc.xml \
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
    android.hardware.vibrator-service.xiaomi-sm8750

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)
