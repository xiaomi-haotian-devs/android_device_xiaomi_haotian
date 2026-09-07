#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2024 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

import os
import shutil
import sys
from functools import partial
from pathlib import Path

import extract_utils.tools
from extract_utils.elf import file_needs_lib
from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixup_remove,
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)
from extract_utils.utils import run_cmd


def _guarded_offset_patch_impl(
    offset: int,
    expected: bytes,
    replacement: bytes,
    _ctx,
    _file,
    file_path: str,
    *args,
    **kwargs,
):
    with open(file_path, 'rb+') as f:
        f.seek(offset)
        current = f.read(len(expected))
        if current == replacement:
            return
        if current != expected:
            raise ValueError(
                f'Unexpected bytes at {file_path}+0x{offset:x}: '
                f'{current.hex()} != {expected.hex()}'
            )
        f.seek(offset)
        f.write(replacement)


def guarded_offset_patch(
    offset: int,
    expected_hex: str,
    replacement_hex: str,
) -> blob_fixup:
    return blob_fixup().call(
        partial(
            _guarded_offset_patch_impl,
            offset,
            bytes.fromhex(expected_hex),
            bytes.fromhex(replacement_hex),
        ),
        need_tmp_dir=False,
    )


def _replace_miuicamera_launcher_icons(
    _ctx,
    _file,
    _file_path: str,
    *args,
    tmp_dir: str | None = None,
    **kwargs,
):
    """Install circular legacy icons after apktool has unpacked MiuiCamera."""
    if tmp_dir is None:
        raise ValueError('MiuiCamera icon replacement requires an apktool temp dir')

    icon_root = (
        Path(module.device_path) / 'patches' / 'MiuiCamera' / 'icons'
    )
    for icon_name in ('BNz.png', 'zCQ.png', '2Fn.png'):
        shutil.copyfile(
            icon_root / icon_name,
            Path(tmp_dir) / 'res' / icon_name,
        )


def _harden_miuicamera_privacy(
    _ctx,
    _file,
    file_path: str,
    *args,
    tmp_dir: str | None = None,
    **kwargs,
):
    """Disable MiuiCamera's OneTrack and MiPush entry points after apktool unpack."""
    if tmp_dir is None:
        raise ValueError('MiuiCamera privacy patch requires an apktool temp dir')

    run_cmd([
        sys.executable,
        str(Path(module.vendor_path) / 'patch-xiaomi-privacy.py'),
        'MiuiCamera',
        file_path,
        tmp_dir,
    ])


def lib_fixup_campostproc_system(
    lib: str,
    partition: str,
    *args,
    **kwargs,
):
    """Use the private system_ext copy instead of the source HIDL module."""
    return f'{lib}_system' if partition == 'system' else None


PRIVATE_CAMERA_NEEDED_REWRITES = {
    'android.hardware.camera.device-V2-ndk.so':
        'android.hardware.camera.device-V2-ndk-stock.so',
    'android.hardware.common-V2-ndk.so':
        'android.hardware.common-V2-ndk-stock.so',
    'android.hardware.common.fmq-V1-ndk.so':
        'android.hardware.common.fmq-V1-ndk-stock.so',
    'android.hardware.graphics.allocator-V2-ndk.so':
        'android.hardware.graphics.allocator-V2-ndk-stock.so',
    'android.hardware.graphics.common-V5-ndk.so':
        'android.hardware.graphics.common-V5-ndk-stock.so',
    'libcamera_metadata.so': 'libcam_metadata_xm.so',
    'libgralloctypes.so': 'libgralloctypes_xm.so',
    'libui.so': 'libui_xm.so',
}

lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    'vendor.xiaomi.hardware.campostproc@1.0':
        lib_fixup_campostproc_system,
}


def _rewrite_private_camera_abi(_ctx):
    """Keep haotian vendor/ODM camera blobs on the stock Xiaomi ABI closure."""
    proprietary = Path(module.vendor_path) / 'proprietary'
    patchelf = extract_utils.tools.patchelf_version_path_map[
        extract_utils.tools.DEFAULT_PATCHELF_VERSION
    ]

    for partition in ('odm', 'vendor'):
        partition_root = proprietary / partition
        if not partition_root.is_dir():
            continue

        for file_path in partition_root.rglob('*'):
            if not file_path.is_file() or file_path.is_symlink():
                continue
            with file_path.open('rb') as f:
                if f.read(4) != b'\x7fELF':
                    continue

            for old, new in PRIVATE_CAMERA_NEEDED_REWRITES.items():
                if file_needs_lib(str(file_path), old):
                    run_cmd([
                        patchelf,
                        '--replace-needed',
                        old,
                        new,
                        str(file_path),
                    ])


def _harden_generated_private_libraries():
    blueprint = Path(module.vendor_path) / 'Android.bp'
    data = blueprint.read_text()

    for name in ('libcam_metadata_xm', 'libgralloctypes_xm', 'libui_xm'):
        marker = f'    name: "{name}",'
        name_offset = data.find(marker)
        if name_offset < 0:
            raise ValueError(f'Missing generated module {name}')

        block_start = data.rfind('cc_prebuilt_library_shared {', 0, name_offset)
        if block_start < 0:
            raise ValueError(f'Cannot locate generated block for {name}')

        depth = 0
        block_end = None
        for offset in range(block_start, len(data)):
            if data[offset] == '{':
                depth += 1
            elif data[offset] == '}':
                depth -= 1
                if depth == 0:
                    block_end = offset + 1
                    break
        if block_end is None:
            raise ValueError(f'Unterminated generated block for {name}')

        block = data[block_start:block_end]
        if '    double_loadable: true,' not in block:
            insertion = '    prefer: true,\n'
            if insertion not in block:
                raise ValueError(f'Missing prefer property for {name}')
            block = block.replace(
                insertion,
                insertion + '    double_loadable: true,\n',
                1,
            )
            data = data[:block_start] + block + data[block_end:]

    blueprint.write_text(data)


def _restore_generated_recovery_prebuilts():
    """Restore recovery-only vendor modules after extract-utils regenerates Android.bp."""
    blueprint = Path(module.vendor_path).parent / 'sm8750-common' / 'Android.bp'
    data = blueprint.read_text()
    marker = 'build = ["recovery-prebuilts.bp"]'
    if marker in data:
        return

    blueprint.write_text(data.rstrip() + '\n\n' + marker + '\n')

namespace_imports = [
    'device/xiaomi/haotian',
    'device/xiaomi/sm8750-common',
    'hardware/qcom-caf/sm8750',
    'hardware/xiaomi',
    'vendor/qcom/opensource/commonsys-intf/display',
    'vendor/xiaomi/sm8750-common',
]

blob_fixups: blob_fixups_user_type = {
    'odm/lib64/libmiface.so': blob_fixup()
        # MiSight is HyperOS telemetry. Keep the ABI call local and inert.
        .replace_needed('libmisight.so', 'libmiface_noop.so'),
    'odm/etc/init/miface.rc': blob_fixup()
        # haotian ships mifaced on ODM only; the vendor variant is for other SKUs.
        .regex_replace(
            r'(?ms)\nservice vendor\.miface .*?\n    disabled\n?',
            '',
        )
        # CameraService returns Android 16 metadata tags which Xiaomi's older
        # vendor metadata table rejects. Interpose the current AOSP table only
        # inside mifaced; the global camera stack keeps its existing ABI.
        .regex_replace(
            '    group system\n    disabled',
            '    group system\n'
            '    setenv LD_PRELOAD /odm/lib64/libcamera_metadata_miface.so\n'
            '    disabled',
        ),
    'odm/etc/init/miface_start_wait_mitee_boot_completed.rc': blob_fixup()
        .regex_replace(
            'on property:ro.vendor.miface.started=true && '
            'property:sys.boot_completed=1 && '
            'property:vendor.mitee_vm.boot_completed=1',
            'on property:ro.vendor.miface.started=true && '
            'property:persist.sys.face.backend=xiaomi && '
            'property:sys.boot_completed=1 && '
            'property:vendor.mitee_vm.boot_completed=1',
        )
        .regex_replace(r'(?m)^\s*start vendor\.miface\n?', '')
        .regex_replace(
            r'\n+\Z',
            '\n\non property:persist.sys.face.backend=sense\n'
            '    stop odm.miface\n',
        ),
    'odm/etc/init/tee-supplicant.rc': blob_fixup()
        # Never relax kernel log access.  Keep the UFS RPMB BSG node at 0660:
        # ueventd owns it as system:system and tee-supplicant is in that group.
        .regex_replace(r'(?m)^\s*chmod 0666 /dev/kmsg\n?', '')
        # Do not recursively relabel or change ownership of the whole persist
        # mount.  MiTEE only owns its dedicated subtree.
        .regex_replace(
            r'(?ms)\non fs\n.*?(?=\n# after firmware_mounts_complete)',
            '\non fs\n'
            '    chmod 0660 /dev/0:0:0:49476\n',
        )
        .regex_replace(
            'on firmware_mounts_complete',
            'on firmware_mounts_complete && '
            'property:persist.sys.face.backend=xiaomi',
        )
        .regex_replace(
            r'\n+\Z',
            '\n\non property:persist.sys.face.backend=xiaomi\n'
            '    mkdir /mnt/vendor/persist/mitee 0775 root system\n'
            '    mkdir /mnt/vendor/persist/mitee/data 0775 root system\n'
            '    restorecon_recursive /mnt/vendor/persist/mitee\n'
            '    mkdir /data/vendor/mitee 0755 system system\n'
            '    restorecon_recursive /data/vendor/mitee\n'
            '\n'
            'on property:persist.sys.face.backend=sense\n'
            '    stop tee-supplicant\n',
        ),
    'product/etc/init/vendor.qti.qvirt-service.rc': blob_fixup()
        .regex_replace(
            r'(?m)^  disabled$',
            '  disabled\n'
            '  setenv LD_LIBRARY_PATH '
            '/product/lib64/qvirt-1.83:/system/lib64:'
            '/product/lib64:/system_ext/lib64',
        )
        .regex_replace(
            r'\n+\Z',
            '\n\non property:persist.sys.face.backend=xiaomi\n'
            '  start vendor.qvirtservice\n'
            '\n'
            'on property:persist.sys.face.backend=sense\n'
            '  stop vendor.qvirtservice\n',
        ),
    'vendor/etc/init/vendor.xiaomi.hardware.batteryantiaging-service.rc':
        blob_fixup()
        # The user-facing charging page is the sole authority for enabling
        # this limiter. Never let the normal HAL/charger classes start it.
        .regex_replace(r'(?m)^(service batteryantiaging .*)$', r'\1\n    disabled')
        .regex_replace(r'(?m)^(service BAA-charger .*)$', r'\1\n    disabled'),
    'system/priv-app/MiuiCamera/MiuiCamera.apk': blob_fixup()
        .apktool_unpack('patches/MiuiCamera')
        .patch_dir('patches/MiuiCamera')
        .call(_harden_miuicamera_privacy)
        .call(_replace_miuicamera_launcher_icons)
        .apktool_pack()
        .stripzip(),
    'system/lib64/libcamera_algoup_jni.xiaomi.so': blob_fixup()
        .add_needed('libgui_shim_miuicamera.so')
        # Android 15 QPR1 moved the Surface field used by this stock JNI.
        .binary_regex_replace(b'\x08\xad\x40\xf9', b'\x08\xa9\x40\xf9'),
    'system/lib64/libcamera_mianode_jni.xiaomi.so': blob_fixup()
        .add_needed('libgui_shim_miuicamera.so'),
    (
        'odm/etc/camera/motiontuning.xml',
        'odm/etc/camera/snsc_bokeh_motiontuning.xml',
        'odm/etc/camera/snsc_enhance_motiontuning.xml',
        'odm/etc/camera/snsc_noface_motiontuning.xml',
        'odm/etc/camera/enhance_motiontuning.xml',
        'odm/etc/camera/snsc_motiontuning.xml'
    ): blob_fixup()
        .regex_replace('xml=version', 'xml version'),
    (
    'vendor/lib64/libcameraopt.so',
    ): blob_fixup().add_needed('libprocessgroup_shim.so'),
    (
        'odm/lib64/libanc_dc_plugin_xiaomi_v3.so',
    ): blob_fixup()
        .add_needed('libc++_shared.so'),
    (
        'odm/lib64/libMiEmojiEffect.so',
        'odm/lib64/libMiVideoFilter.so',
        'odm/lib64/libAncHumanPreviewBokeh.so',
        'odm/lib64/libTrueSight.so',
        'odm/lib64/libwa_widelens_undistort.so',
        'odm/lib64/libMiPhotoFilter.so'
    ): blob_fixup()
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_lockPlanes')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_isSupported'),
    (
       'odm/lib64/camera/components/com.qti.node.dewarp.so',
       'odm/lib64/hw/com.qti.chi.override.so',
       'odm/lib64/libcamximageformatutils.so',
       'odm/lib64/libchifeature2.so',
       'odm/lib64/vendor.qti.hardware.camera.offlinecamera-service-impl.so',
    ): blob_fixup()
        .remove_needed('android.hardware.graphics.allocator-V1-ndk.so'),
    (
       'vendor/lib64/vendor.xiaomi.hardware.camera.injection-V1-ndk.so',
       'vendor/lib64/vendor.xiaomi.hardware.camera.injection-client.so',
       'vendor/lib64/vendor.xiaomi.hardware.camera.injection-service.so',
    ): blob_fixup()
        .replace_needed(
            'android.hardware.camera.device-V1-ndk.so',
            'android.hardware.camera.device-V2-ndk.so'
        ),
    (
       'odm/lib64/hw/camera.qcom.so',
    ): blob_fixup()
        .replace_needed(
            'android.hardware.sensors-V2-ndk.so',
            'android.hardware.sensors-V3-ndk.so'
        ),
    (
        'odm/lib64/com.xiaomi.plugin.ecdengine.so',
        'odm/lib64/libcamxcoreutils.so',
        'odm/lib64/libcamxods.so',
        'odm/lib64/libmicamera_aidl_provider.so',
        'odm/lib64/libsimulation.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.anchor.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineawbideal.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineb2y.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineformatconvertor.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinehdrraw2y.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineheic.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinei2y.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinejpeg.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinemfnr.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinemlawb.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinetintless.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlinetintlesshdr.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineyuvreprocess.so',
        'odm/lib64/camera/plugins/com.xiaomi.plugin.offlineyuvsplit.so',
    ): blob_fixup()
        .binary_regex_replace(b'libtinyxml2.so\0', b'libtinyxmlQ.so\0'),
    'odm/bin/hw/vendor.qti.camera.provider-service_64': blob_fixup()
        .binary_regex_replace(b'libtinyxml2.so\0', b'libtinyxmlQ.so\0')
        .add_needed('libhaotian_camera_provider_cgroup.so'),
    'odm/lib64/libmicamera_hal_core.so': guarded_offset_patch(
        0x1096d0,
        '71010094',
        '1f2003d5',
    ).binary_regex_replace(b'libtinyxml2.so\0', b'libtinyxmlQ.so\0'),
    'odm/lib64/libmicamera_aidl_device.so': guarded_offset_patch(
        0x3f700,
        '3f2303d5',
        'c0035fd6',
    ),
    'vendor/lib64/android.hardware.camera.device-V2-ndk-stock.so':
        guarded_offset_patch(
            0x177e8,
            '3f2303d5',
            'c0035fd6',
        ).call(
            partial(
                _guarded_offset_patch_impl,
                0x23178,
                bytes.fromhex('46330094'),
                bytes.fromhex('1f2003d5'),
            ),
            need_tmp_dir=False,
        ),
    'odm/etc/init/vendor.qti.camera.provider-service_64.rc': blob_fixup()
        .regex_replace(
            r'(?m)^\s*interface vendor\.xiaomi\.hardware\.quickcamera@1\.0::IQuickCameraService default$',
            '    # quickcamera HIDL is absent from the public interface set'
        )
        .regex_replace(
            r'(?m)^\s*writepid /sys/kernel/reserve_pool/pid\n?',
            ''
        ),
    'vendor/lib64/libultrahdr_haotian.so': blob_fixup()
        .replace_needed(
            'libjpegencoder.so',
            'libjpegencoder_haotian.so'
        )
        .replace_needed(
            'libjpegdecoder.so',
            'libjpegdecoder_haotian.so'
        ),
    ('odm/lib64/camera/plugins/com.xiaomi.plugin.jpegrAggr.so', 'odm/lib64/camera/plugins/com.xiaomi.plugin.gainmap.so'): blob_fixup()
        .replace_needed(
            'libultrahdr.so',
            'libultrahdr_haotian.so'
        ),
    (
        'vendor/lib64/libcamera2ndk_vendor.so',
    ): blob_fixup()
        .replace_needed('android.frameworks.cameraservice.device-V2-ndk.so', 'android.frameworks.cameraservice.device-V3-ndk.so')
        .replace_needed('android.frameworks.cameraservice.service-V2-ndk.so', 'android.frameworks.cameraservice.service-V3-ndk.so'),
    'odm/etc/sensors/config/sm8750_tcs3720_fb.json': blob_fixup()
        .regex_replace(
            r'"near_threshold":\{ "type": "flt", "ver": "[0-9]+",\n          "data": "(?:140\.0|105\.0|105)"\n        \}',
            '"near_threshold":{ "type": "flt", "ver": "3",\n          "data": "90.0"\n        }',
        )
        .regex_replace(
            r'"far_threshold":\{ "type": "flt", "ver": "[0-9]+",\n          "data": "(?:80\.0|65\.0|65)"\n        \}',
            '"far_threshold":{ "type": "flt", "ver": "3",\n          "data": "55.0"\n        }',
        )
        .regex_replace(
            r'"parm0":\{ "type": "flt", "ver": "[0-9]+",\n          "data": "(?:130\.0|105\.0|105)"\n        \}',
            '"parm0":{ "type": "flt", "ver": "2",\n          "data": "90.0"\n        }',
        ),
}

module = ExtractUtilsModule(
    'haotian',
    'xiaomi',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    check_elf=True,
)
module.add_postprocess_fn(_rewrite_private_camera_abi)

if __name__ == '__main__':
    utils = ExtractUtils.device_with_common(
        module, 'sm8750-common', module.vendor
    )
    utils.run()
    _harden_generated_private_libraries()
    _restore_generated_recovery_prebuilts()
