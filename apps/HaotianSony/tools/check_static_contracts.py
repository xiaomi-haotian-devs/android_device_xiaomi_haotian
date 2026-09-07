#!/usr/bin/env python3
"""Source/resource/wire-contract lint only. Never invokes a compiler or Android build tool."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[1]
DEVICE = APP.parents[1]
ANDROID = DEVICE.parents[2]
AUDIO = DEVICE / "apps/HaotianAudio"
JAVA = APP / "src/org/xiaomi/haotian/sony"
errors = []


def require(condition, message):
    if not condition:
        errors.append(message)


def resources(app, qualifier):
    result = {}
    for path in (app / "res" / qualifier).glob("*.xml"):
        for element in ET.parse(path).getroot():
            if "name" not in element.attrib:
                continue
            kind = "array" if element.tag.endswith("-array") else element.tag
            key = kind, element.attrib["name"]
            require(key not in result, f"Duplicate resource {key} in {path}")
            result[key] = element
    return result


def check_resources(app, qualifiers):
    base = resources(app, "values")
    for qualifier in qualifiers:
        translated = resources(app, qualifier)
        require(base.keys() == translated.keys(), f"Translation coverage differs: {app.name}/{qualifier}")
        for key in base.keys() & translated.keys():
            if key[0] == "array":
                require(len(base[key]) == len(translated[key]), f"Array length differs: {qualifier}/{key}")
    for path in (app / "src").rglob("*.java"):
        source = path.read_text()
        for kind, name in re.findall(r"(?<![\w.])R\.(string|array)\.(\w+)", source):
            require((kind, name) in base, f"Missing resource {kind}/{name} in {path.name}")
    return base


sony_resources = check_resources(APP, ["values-zh-rCN", "values-zh-rTW"])
# HaotianAudio has other pre-existing resource families; verify references without imposing
# translation policy on unrelated user work.
check_resources(AUDIO, [])
for app in (APP, AUDIO):
    for path in app.rglob("*.xml"):
        ET.parse(path)

native = (JAVA / "SonyNative.java").read_text()
bridge = (APP / "jni/SonyMdrBridge.cpp").read_text()
declared = set(re.findall(r"\bnative\s+\w+(?:\[\])?\s+(\w+)\(", native))
exported = set(re.findall(r"Java_org_xiaomi_haotian_sony_SonyNative_(\w+)\(", bridge))
require(declared == exported, f"JNI declarations/exports differ: {declared ^ exported}")
header = (APP / "third_party/libmdr/include/mdr-c/Headphones.h").read_text()
for function in set(re.findall(r"\b(mdrHeadphones\w+)\b", bridge)):
    require(function in header, f"Undeclared C API: {function}")
snapshot_header = (ANDROID / "frameworks/av/media/libheadtracking/include/media/HeadTrackerSnapshot.h").read_text()
reader = (AUDIO / "src/org/xiaomi/haotian/audio/NativeHeadTrackerReader.java").read_text()
for token in ("0x48545044", "0x48545031", "384"):
    require(token in snapshot_header and token in reader, f"Native/Java wire constant mismatch: {token}")
layout = {0: 4, 4: 4, 8: 4, 12: 4, 16: 4, 20: 4, 24: 8, 32: 8, 40: 8,
          48: 4, 72: 4, 76: 4, 80: 4, 84: 4, 88: 4, 92: 4,
          96: 4, 100: 4, 104: 4, 108: 4, 112: 4, 116: 4, 120: 4}
for kind, offset in re.findall(r"b\.get(Int|Long|Float)\((\d+)\)", reader):
    require(layout.get(int(offset)) == (8 if kind == "Long" else 4), f"Invalid wire read: {kind}@{offset}")
require("registerListener" not in reader and "SensorManager" not in reader,
        "Native diagnostic reader must not subscribe to sensors")
require("getBluetoothIdentityAddress()" in reader and "matchesUuid" in reader,
        "Native diagnostic reader must enforce active route identity")
view = (AUDIO / "src/org/xiaomi/haotian/audio/HeadTrackingDebugActivity.java").read_text()
require("new Result(pose, details, present && b.getInt(16) == 1)" in reader
        and "result.sonyYawCorrected" in reader,
        "Sony visual correction must follow the native quirk flag, including cached snapshots")
require("nativeResult != null && nativeResult.sonyYawCorrected" in view
        and "return sonyYawCorrected ? sensorZ : -sensorZ;" in view
        and "qy = nativeDisplayYaw(rawZ);" in view
        and "referenceY = nativeDisplayYaw(snapshot.qz);" in view,
        "Sony display yaw and local recenter must use the same display-only mapping")
require("sonyYawCorrected != newSonyYawCorrected" in view,
        "A display coordinate change must clear the previous recenter reference")
quirk = (ANDROID / "frameworks/av/media/libheadtracking/include/media/SonyHeadTrackerQuirk.h").read_text()
require('name != "WH-1000XM5"' in quirk and "isDynamicSensor()" in quirk,
        "Sony correction must remain model/dynamic-sensor scoped")
require("rotation.z() = -rotation.z()" in quirk and "velocity.z() = -velocity.z()" in quirk,
        "Rotation and prediction velocity must share the lateral correction")
require(not re.search(r"(?:rotation|velocity)\.[xy]\(\)\s*=", quirk),
        "Sony correction unexpectedly changes pitch/roll components")

controller = (JAVA / "SonyController.java").read_text()
boot_receiver = (JAVA / "SonyBootReceiver.java").read_text()
user_guard = "if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return;"
require(user_guard in boot_receiver
        and boot_receiver.index(user_guard) < boot_receiver.index(".controller()"),
        "Boot receiver must ignore secondary users before accessing the transport")
task_details = (APP / "third_party/libmdr/src/Details.hpp").read_text()
final_awaiter = task_details.split("struct final_awaiter", 1)[1].split(
    "static final_awaiter final_suspend", 1)[0]
require("std::noop_coroutine(" not in task_details,
        "MDR tasks must not use the Android toolchain's non-BTI noop coroutine stub")
require("void await_suspend(" in final_awaiter
        and "auto next = other.promise().next;" in final_awaiter
        and re.search(r"if\s*\(next\)\s*next\.resume\(\);", final_awaiter),
        "Final suspend must resume only an existing continuation, leaving roots suspended")
require("a2dp.getActiveDevice(" not in controller
        and "adapter.getActiveDevices(BluetoothProfile.A2DP)" in controller,
        "Active A2DP selection must use the Bluetooth module's exported SystemApi")
bluetooth_api = (ANDROID / "packages/modules/Bluetooth/framework/api/system-current.txt").read_text()
require("getActiveDevices(int)" in bluetooth_api, "Active-device SystemApi missing from module stubs")
commit = controller.split("else if (event == 18)", 1)[1].split("else if (event == 22)", 1)[0]
require('beginRequest(3, "applying")' in commit and "beginRequest(0," not in commit,
        "Commit must use scoped readback instead of unconditional full initialization")
require("case 3: return mdrHeadphonesRequestReadback" in bridge
        and "if (result == MDR_RESULT_OK) s->readbackKey = k;" in bridge,
        "Scoped readback must use the last successfully validated setter's key")
v2 = (APP / "third_party/libmdr/src/HeadphonesV2.cpp").read_text()
readback = v2.split("MDRTask MDRHeadphones::RequestReadbackV2", 1)[1].split(
    "MDRTask MDRHeadphones::RequestSyncV2", 1)[0]
require("property.reported = false;" in readback
        and "mReadbackFlags.push_back(&property.reported)" in readback
        and "Await(AWAIT_CONTROL_READBACK)" in readback
        and "Control readback timed out" in readback,
        "Scoped readback needs a fresh observation epoch and bounded parameter-response wait")
require("ConnectGet" not in readback and "GetCapability" not in readback
        and "co_return co_await RequestInitV2();" in readback,
        "Common readback must retain capabilities, with a fallback for uncommon actions")
headphones = (APP / "third_party/libmdr/src/Headphones.cpp").read_text()
require("std::ranges::all_of(mReadbackFlags" in headphones
        and "Awake(AWAIT_CONTROL_READBACK)" in headphones,
        "Parameter reports arriving after the final ACK must complete scoped readback")
for key in ("noise.", "speak.", "listening.", "eq.", "playback.volume", "power.pause",
            "power.timeout", "power.wearing", "power.gesture", "voice.enabled",
            "voice.volume", "connection.priority", "pairing.switch", "assign", "general"):
    require('"' + key + '"' in readback, f"Missing common control readback: {key}")
handlers = "\n".join((APP / "third_party/libmdr/src" / name).read_text()
                     for name in ("HeadphonesV2T1.cpp", "HeadphonesV2T2.cpp"))
for field in re.findall(r"watch\(s\.(\w+)\)", readback):
    require(field + ".overwrite(" in handlers,
            f"Readback waits on a property with no response writer: {field}")
require("coroutine = std::exchange(other.coroutine, nullptr);" in task_details
        and "if (this != &other)" in task_details,
        "Task replacement must transfer ownership without leaking completed frames")
require("generation != expectedGeneration" in controller, "Missing stale socket generation guard")
require("MSG_NOSIGNAL" in bridge and "F_DUPFD_CLOEXEC" in bridge,
        "JNI socket lifetime/SIGPIPE protection missing")
aidl = (JAVA / "ISonyControlService.aidl").read_text()
service = (JAVA / "SonyControlService.java").read_text()
for method in re.findall(r"\b(?:void|String)\s+(\w+)\(", aidl):
    require(method + "(" in service, f"Missing Binder method: {method}")

# Catch delimiter errors while ignoring comments and string/character contents. This is not
# Java/C++ semantic analysis and deliberately makes no compilation claim.
def check_delimiters(path):
    source = path.read_text()
    source = re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', "", source)
    stack = []
    for char in source:
        if char in "({[":
            stack.append(char)
        elif char in ")}]":
            if not stack or stack.pop() != {")": "(", "}": "{", "]": "["}[char]:
                errors.append(f"Delimiter mismatch in {path.name}")
                return
    require(not stack, f"Unclosed delimiter in {path.name}")

for path in JAVA.glob("*.java"):
    check_delimiters(path)
check_delimiters(APP / "jni/SonyMdrBridge.cpp")
check_delimiters(APP / "third_party/libmdr/src/Details.hpp")
for name in ("Headphones.cpp", "HeadphonesV2.cpp", "HeadphonesV2T1.cpp"):
    check_delimiters(APP / "third_party/libmdr/src" / name)
for name in ("NativeHeadTrackerReader.java", "HeadPoseDebugState.java", "HeadTrackingDebugActivity.java"):
    check_delimiters(AUDIO / "src/org/xiaomi/haotian/audio" / name)

if errors:
    raise SystemExit("\n".join(errors))
print(f"PASS: {len(sony_resources)} Sony resources, translations, XML, {len(declared)} JNI methods,")
print("C API references, Binder methods, wire offsets, scoped correction and readback guards.")
print("System-user boot guard and BTI-safe coroutine completion source contracts.")
print("Source-only lint. No compilation, unit-test binary or device validation was performed.")
