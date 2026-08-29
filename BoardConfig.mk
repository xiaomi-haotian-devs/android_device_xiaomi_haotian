#
# Copyright (C) 2023 The Android Open Source Project
#
# SPDX-License-Identifier: Apache-2.0
#

DEVICE_PATH := device/xiaomi/haotian
KERNEL_PATH := $(DEVICE_PATH)-kernel

# Inherit from sm8650-common
include device/xiaomi/sm8750-common/BoardConfigCommon.mk

# Keep the read-only dynamic partitions on ext4 for now, but share identical
# blocks within each image to fit the physical super partition. This is also
# understood by the A/B OTA releasetools.
BOARD_EXT4_SHARE_DUP_BLOCKS := true

# Sepolicy
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/system_ext/private

# Display
TARGET_SCREEN_DENSITY := 600

# Dtb/o
BOARD_PREBUILT_DTBOIMAGE := $(KERNEL_PATH)/dtbo.img
# Soong's prebuilt DTB packaging only globs *.dtb from this directory.
BOARD_PREBUILT_DTBIMAGE_DIR := $(KERNEL_PATH)/dtb

TARGET_NO_KERNEL_OVERRIDE := true
TARGET_KERNEL_SOURCE := $(KERNEL_PATH)/kernel-headers
TARGET_PREBUILT_KERNEL_HEADERS := $(KERNEL_PATH)/prebuilt_kernel_headers.tar.gz
PRODUCT_COPY_FILES += \
	$(KERNEL_PATH)/kernel:kernel

# Kernel modules
BOARD_VENDOR_RAMDISK_KERNEL_MODULES := $(wildcard $(KERNEL_PATH)/vendor_ramdisk/*.ko)
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_PATH)/vendor_ramdisk/modules.load))
BOARD_VENDOR_RAMDISK_RECOVERY_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_PATH)/vendor_ramdisk/modules.load.recovery))
BOARD_VENDOR_RAMDISK_KERNEL_MODULES_BLOCKLIST_FILE := $(KERNEL_PATH)/vendor_ramdisk/modules.blocklist
BOARD_DO_NOT_STRIP_VENDOR_RAMDISK_MODULES := true
# Declare vendor_dlkm modules so the build system generates depmod metadata.
BOARD_VENDOR_KERNEL_MODULES := $(wildcard $(KERNEL_PATH)/vendor_dlkm/*.ko)
BOARD_VENDOR_KERNEL_MODULES_LOAD := $(strip $(shell cat $(KERNEL_PATH)/vendor_dlkm/modules.load))
BOARD_VENDOR_KERNEL_MODULES_BLOCKLIST_FILE := $(KERNEL_PATH)/vendor_dlkm/modules.blocklist
BOARD_DO_NOT_STRIP_VENDOR_MODULES := true

PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,$(KERNEL_PATH)/system_dlkm/6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k/,$(TARGET_COPY_OUT_SYSTEM_DLKM)/lib/modules/6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k)

# Properties
TARGET_ODM_PROP += $(DEVICE_PATH)/configs/properties/odm.prop
TARGET_SYSTEM_PROP += $(DEVICE_PATH)/configs/properties/system.prop

# Inherit from the proprietary version
include vendor/xiaomi/haotian/BoardConfigVendor.mk

ifeq ($(TARGET_INCLUDE_FIRMWARE),true)
include vendor/xiaomi/haotian/BoardConfigFirmware.mk
endif
