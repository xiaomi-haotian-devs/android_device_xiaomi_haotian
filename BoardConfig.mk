#
# Copyright (C) 2023 The Android Open Source Project
#
# SPDX-License-Identifier: Apache-2.0
#

DEVICE_PATH := device/xiaomi/haotian
KERNEL_PATH := $(DEVICE_PATH)-kernel

# Inherit from sm8650-common
include device/xiaomi/sm8750-common/BoardConfigCommon.mk

# Xiaomi MiFace depends on the device-side AEK service. Keep the declaration
# haotian-specific because the service is only shipped by this device tree.
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += \
    $(DEVICE_PATH)/configs/vintf/compatibility_matrix.miface.xml

# Keep the read-only dynamic partitions on ext4 for now, but share identical
# blocks within each image to fit the physical super partition. This is also
# understood by the A/B OTA releasetools.
BOARD_EXT4_SHARE_DUP_BLOCKS := true

# Sepolicy
BOARD_VENDOR_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/vendor
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(DEVICE_PATH)/sepolicy/system_ext/public
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
# The Synaptics panel driver normally lives in vendor_dlkm, which is not available
# when the recovery vendor ramdisk loads its module list. Keep the stock modules
# in vendor_dlkm for Android and additionally package them into the vendor ramdisk
# so recovery can create Xiaomi_Touch_Input_0 without mounting a dynamic partition.
BOARD_VENDOR_RAMDISK_KERNEL_MODULES += \
    $(KERNEL_PATH)/vendor_dlkm/xiaomi_touch.ko \
    $(KERNEL_PATH)/vendor_dlkm/synaptics_tcm2.ko
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
