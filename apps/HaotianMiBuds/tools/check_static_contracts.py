#!/usr/bin/env python3
"""Source-only HaotianMiBuds contract checks. This never invokes a compiler."""

from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEVICE = ROOT.parents[1]
SETTINGS = DEVICE.parents[2] / "packages/apps/Settings"
EXPECTED_LIBRARY_SHA256 = (
    "7434a5f4e93664db98a8f01cd17ed236c15c82a372953e4e5fe7a18333c0ff57"
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def check_xml() -> None:
    for path in sorted(ROOT.rglob("*.xml")):
        ET.parse(path)

    manifest = text("AndroidManifest.xml")
    require("org.xiaomi.haotian.mibuds" in manifest, "manifest package missing")
    require("BIND_HEADPHONE_PROVIDER" in manifest, "provider permission missing")
    require("HEADPHONE_POSE_PROVIDER" in manifest, "provider action missing")

    base_strings = ET.parse(ROOT / "res/values/strings.xml").getroot()
    expected_names = {element.attrib["name"] for element in base_strings.findall("string")}
    for locale in ("values-zh-rCN", "values-zh-rTW"):
        localized = ET.parse(ROOT / f"res/{locale}/strings.xml").getroot()
        names = {element.attrib["name"] for element in localized.findall("string")}
        require(names == expected_names, f"translation key mismatch in {locale}")


def check_protocol_source() -> None:
    transport = text("src/org/xiaomi/haotian/mibuds/MiBudsTransport.java")
    codec = text("src/org/xiaomi/haotian/mibuds/RcspCodec.java")
    controller = text("src/org/xiaomi/haotian/mibuds/MiBudsController.java")
    expected = {
        "OPCODE_AUTH_CHECK": "0x50",
        "OPCODE_AUTH_RESULT": "0x51",
        "OPCODE_SET_CONFIG": "0xf2",
        "OPCODE_GET_CONFIG": "0xf3",
        "OPCODE_NOTIFY_CONFIG": "0xf4",
        "CONFIG_SPATIAL_WRITE": "0x1d",
        "CONFIG_SPATIAL_READ": "0x1e",
        "CONFIG_HEAD_POSE": "0x21",
    }
    for name, value in expected.items():
        require(re.search(rf"\b{name}\s*=\s*{value}\s*;", transport) is not None,
                f"wrong or missing {name}")

    request = re.search(r"POSE_STREAM_REQUEST\s*=\s*new byte\[\]\s*\{([^}]*)\}",
                        transport, re.DOTALL)
    require(request is not None, "pose stream request missing")
    tokens = re.findall(r"(?:\(byte\)\s*)?0x[0-9a-fA-F]+", request.group(1))
    values = bytes(int(token.split("x")[-1], 16) & 0xFF for token in tokens)
    require(values.hex().upper() == "FF01020103020501FF", "pose stream request changed")

    require("return encode(0xc4" in codec, "command flag changed")
    require("return encode(0x04" in codec, "response flag changed")
    require("packet[0] = (byte) 0xfe" in codec, "RCSP magic missing")
    require("qYaw, qPitch" in controller and "qRoll" in controller,
            "quaternion axis composition missing")
    require("!hostResultAccepted" in transport,
            "mutual authentication no longer waits for the result response")
    require("frame.parameters[0] == 0x01" in transport,
            "peer authentication challenge version is not checked")
    require("spatialState | mask" in controller and "spatialState & ~mask" in controller,
            "spatial bit updates no longer preserve the remaining readback bits")
    require("Never substitute another connected Xiaomi peer" in controller,
            "provider route can fall through to a different connected earbud")
    require("stopPoseStreamingOrCloseOnWorker" in controller,
            "provider replacement lacks a failed-stop fallback")
    require("opcode == MiBudsTransport.OPCODE_SET_CONFIG" in controller,
            "a rejected F3 query can trigger an unbounded requery loop")

    # Reproduce two nonsensitive, captured envelopes independently of Java.
    def command(opcode: int, sequence: int, params: bytes) -> bytes:
        body = bytes([sequence]) + params
        return b"\xfe\xdc\xba\xc4" + bytes([opcode]) + len(body).to_bytes(2, "big") \
            + body + b"\xef"

    require(command(0xF3, 8, bytes.fromhex("000C")).hex().upper()
            == "FEDCBAC4F3000308000CEF", "F3 envelope vector failed")
    require(command(0xF2, 13, bytes.fromhex("03001D1B")).hex().upper()
            == "FEDCBAC4F200050D03001D1BEF", "F2 envelope vector failed")


def check_native_library() -> None:
    library = ROOT / "third_party/jieli/arm64-v8a/libjl_bluetooth.so"
    digest = hashlib.sha256(library.read_bytes()).hexdigest()
    require(digest == EXPECTED_LIBRARY_SHA256, "authentication library checksum changed")
    output = subprocess.run(
        ["readelf", "-lW", str(library)],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout
    load_lines = [line for line in output.splitlines() if line.lstrip().startswith("LOAD")]
    require(load_lines, "authentication library has no PT_LOAD")
    for line in load_lines:
        alignment = int(line.split()[-1], 0)
        require(alignment >= 0x4000, f"PT_LOAD alignment is only {alignment:#x}")

    # JNI_OnLoad registers both classes unconditionally. Missing either class,
    # or allowing R8 to rename one of these methods, makes the entire library
    # load fail before authentication can begin.
    native_strings = subprocess.run(
        ["strings", "-a", "-n", "3", str(library)],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout
    expected_jni_strings = (
        "com/jieli/bluetooth/impl/RcspAuth",
        "nativeInit",
        "()Z",
        "getRandomAuthData",
        "()[B",
        "setLinkKey",
        "([B)I",
        "getEncryptedAuthData",
        "([B)[B",
        "com/jieli/bluetooth/utils/CryptoUtil",
        "crc16",
        "([BS)S",
    )
    for value in expected_jni_strings:
        require(value in native_strings, f"native JNI contract missing {value}")

    rcsp_auth = text("src/com/jieli/bluetooth/impl/RcspAuth.java")
    crypto_util = text("src/com/jieli/bluetooth/utils/CryptoUtil.java")
    expected_java_declarations = (
        (rcsp_auth, r"native\s+boolean\s+nativeInit\s*\(\s*\)"),
        (rcsp_auth, r"native\s+byte\[\]\s+getRandomAuthData\s*\(\s*\)"),
        (rcsp_auth, r"native\s+int\s+setLinkKey\s*\(\s*byte\[\]"),
        (rcsp_auth, r"native\s+byte\[\]\s+getEncryptedAuthData\s*\(\s*byte\[\]"),
        (crypto_util, r"static\s+native\s+short\s+crc16\s*\(\s*byte\[\]\s+\w+\s*,\s*short"),
    )
    for source, declaration in expected_java_declarations:
        require(re.search(declaration, source) is not None,
                f"Java JNI declaration missing: {declaration}")

    keep_rules = text("proguard.flags")
    require("-keep class com.jieli.bluetooth.impl.RcspAuth { *; }" in keep_rules,
            "RcspAuth keep rule missing")
    require("-keep class com.jieli.bluetooth.utils.CryptoUtil { *; }" in keep_rules,
            "CryptoUtil keep rule missing")


def check_integration() -> None:
    device_mk = (DEVICE / "device.mk").read_text(encoding="utf-8")
    require(re.search(r"\bHaotianMiBuds\b", device_mk) is not None,
            "product package missing")
    seapp = (DEVICE / "sepolicy/system_ext/private/seapp_contexts").read_text(
        encoding="utf-8")
    require("org.xiaomi.haotian.mibuds domain=haotian_mibuds_app" in seapp,
            "seapp mapping missing")
    policy = (DEVICE / "sepolicy/system_ext/private/haotian_mibuds_app.te").read_text(
        encoding="utf-8")
    require("bluetooth_domain(haotian_mibuds_app)" in policy,
            "Bluetooth SELinux domain missing")

    if SETTINGS.is_dir():
        settings_manifest = (SETTINGS / "AndroidManifest.xml").read_text(encoding="utf-8")
        details = (SETTINGS / "src/com/android/settings/bluetooth/"
                   "BluetoothDeviceDetailsFragment.java").read_text(encoding="utf-8")
        controller = SETTINGS / "src/com/android/settings/bluetooth/" \
            "BluetoothDetailsMiBudsController.java"
        require("org.xiaomi.haotian.mibuds.permission.CONTROL" in settings_manifest,
                "Settings control permission missing")
        require("new BluetoothDetailsMiBudsController" in details,
                "Bluetooth details controller is not registered")
        require(controller.is_file(), "Bluetooth details controller source missing")

    provider = text("src/org/xiaomi/haotian/mibuds/MiBudsHeadphoneProviderService.java")
    require("boolean onUnbind" in provider and "detachProvider()" in provider,
            "provider unbind does not stop the pose stream")
    strings = text("res/values/strings.xml")
    require("avoid double processing" in strings,
            "phone/earbud rendering separation warning missing")


def check_balanced_java_delimiters() -> None:
    for path in sorted((ROOT / "src").rglob("*.java")):
        source = path.read_text(encoding="utf-8")
        # Remove comments and strings before doing a deliberately simple source sanity check.
        stripped = re.sub(r"/\*.*?\*/|//[^\n]*|\"(?:\\.|[^\"\\])*\"", "", source,
                          flags=re.DOTALL)
        require(stripped.count("{") == stripped.count("}"),
                f"unbalanced braces in {path.relative_to(ROOT)}")
        require(stripped.count("(") == stripped.count(")"),
                f"unbalanced parentheses in {path.relative_to(ROOT)}")


def main() -> int:
    checks = (
        check_xml,
        check_protocol_source,
        check_native_library,
        check_integration,
        check_balanced_java_delimiters,
    )
    try:
        for check in checks:
            check()
    except (AssertionError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"OK: {len(checks)} source-only HaotianMiBuds contract groups")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
