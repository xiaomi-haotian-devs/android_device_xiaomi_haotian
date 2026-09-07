#
# Copyright (C) 2024 The Android Open Source Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Bringup debug: keep adbd available before the framework is healthy.
WITH_ADB_INSECURE := true

# HaotianAudio owns the single AirPods AACP transport, including the head-pose
# stream.  This must be set before common_full_phone inherits evolution.mk.
TARGET_EXCLUDES_BTHELPER := true

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# Keep EvoX's daily-driver userdebug hardening disabled during bring-up. This
# must be empty: Make treats the literal value "false" as a set boolean.
PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG :=

# Inherit from haotian device.
$(call inherit-product, device/xiaomi/haotian/device.mk)

## Device identifier
PRODUCT_DEVICE := haotian
PRODUCT_NAME := lineage_haotian
PRODUCT_BRAND := Xiaomi
PRODUCT_MODEL := 2410DPN6CC
PRODUCT_MANUFACTURER := Xiaomi

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc=$(call normalize-path-list, "haotian haotian 16 BP2A.250605.031.A3 OS3.0.302.0.WOBCNXM:user release-keys")

BUILD_FINGERPRINT := Xiaomi/haotian/haotian:16/BP2A.250605.031.A3/OS3.0.302.0.WOBCNXM:user/release-keys

# GMS
PRODUCT_GMS_CLIENTID_BASE := android-xiaomi
