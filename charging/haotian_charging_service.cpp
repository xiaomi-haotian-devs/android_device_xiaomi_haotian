/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "HaotianChargingService"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>
#include <android/binder_parcel_utils.h>
#include <android/binder_process.h>

#include <string>

namespace {

using android::base::GetBoolProperty;
using android::base::ReadFileToString;
using android::base::SetProperty;
using android::base::Trim;
using android::base::WriteStringToFile;

constexpr char kServiceName[] = "haotian_charging";
constexpr char kServiceDescriptor[] =
        "org.xiaomi.haotian.charging.IChargingControl";
constexpr char kAntiAgingProperty[] = "persist.vendor.batteryantiaging";
constexpr char kAntiAgingUserProperty[] = "persist.vendor.haotian.batteryantiaging";
constexpr char kFastChargeProperty[] = "persist.vendor.haotian.fast_charge";
constexpr char kFastChargeNode[] = "/sys/class/thermal/thermal_message/charger_temp";
constexpr char kSmartFvNode[] = "/sys/class/xm_power/charger/smart_charge/smart_fv";
constexpr char kWirelessRoot[] =
        "/sys/devices/virtual/xm_power/wireless_master/";
constexpr char kWirelessBasicRoot[] =
        "/sys/devices/platform/soc/soc:mca_strategy_basic_wireless_class/";

enum Transaction : uint32_t {
    GET_ANTI_AGING = FIRST_CALL_TRANSACTION,
    SET_ANTI_AGING,
    GET_FAST_CHARGE,
    SET_FAST_CHARGE,
    GET_WIRELESS_DETAILS,
};

std::string readNode(const std::string& path) {
    std::string value;
    if (!ReadFileToString(path, &value, true)) {
        return "";
    }
    return Trim(value);
}

std::string wirelessDetails() {
    // Stable, read-only fields exposed by Xiaomi's MCA wireless stack. Keep
    // this order in sync with ChargingControlClient.WirelessDetails.
    const char* const wirelessNodes[] = {
            "fw_version", "tx_uuid", "tx_adapter", "rx_vout", "rx_iout",
            "rx_vrect", "wls_die_temp", "rx_offset", "rx_cep", "rx_ss",
            "wls_tx_speed",
    };
    std::string result;
    bool first = true;
    for (const char* node : wirelessNodes) {
        if (!first) result += '|';
        first = false;
        result += readNode(std::string(kWirelessRoot) + node);
    }
    result += '|';
    result += readNode(std::string(kWirelessBasicRoot) + "wls_fc_flag");
    result += '|';
    result += readNode(std::string(kWirelessBasicRoot) + "wls_car_adapter");
    return result;
}

bool writeNode(const char* path, const char* value) {
    if (!WriteStringToFile(value, path, true)) {
        PLOG(ERROR) << "Failed to write " << path;
        return false;
    }
    return true;
}

bool setAntiAging(bool enabled) {
    if (enabled) {
        if (!SetProperty(kAntiAgingUserProperty, "1")) {
            return false;
        }
        if (!SetProperty(kAntiAgingProperty, "1") ||
            !SetProperty("ctl.start", "batteryantiaging")) {
            SetProperty(kAntiAgingProperty, "0");
            SetProperty(kAntiAgingUserProperty, "0");
            writeNode(kSmartFvNode, "0");
            return false;
        }
        return true;
    }

    // Stop first, then clear the limiter it may have programmed. The disabled
    // state must always restore unrestricted hardware behaviour.
    SetProperty("ctl.stop", "batteryantiaging");
    const bool reset = writeNode(kSmartFvNode, "0");
    const bool persisted = SetProperty(kAntiAgingProperty, "0");
    const bool userPersisted = SetProperty(kAntiAgingUserProperty, "0");
    return reset && persisted && userPersisted;
}

bool setFastCharge(bool enabled) {
    // HyperOS uses 8 for its user-selected top-speed profile and 0 to remove
    // that override. No cloud or thermal policy is permitted to set this flag.
    const bool previous = GetBoolProperty(kFastChargeProperty, false);
    if (!SetProperty(kFastChargeProperty, enabled ? "1" : "0")) {
        return false;
    }
    if (!writeNode(kFastChargeNode, enabled ? "8" : "0")) {
        SetProperty(kFastChargeProperty, previous ? "1" : "0");
        return false;
    }
    return true;
}

void applyPersistedState() {
    // Deliberately ignore a stale HyperOS/cloud value. Only the dedicated
    // local user-choice property is authoritative.
    const bool antiAging = GetBoolProperty(kAntiAgingUserProperty, false);
    if (antiAging) {
        SetProperty(kAntiAgingProperty, "1");
        SetProperty("ctl.start", "batteryantiaging");
    } else {
        SetProperty("ctl.stop", "batteryantiaging");
        SetProperty(kAntiAgingProperty, "0");
        writeNode(kSmartFvNode, "0");
    }
    setFastCharge(GetBoolProperty(kFastChargeProperty, false));
}

void* onCreate(void*) {
    return nullptr;
}

void onDestroy(void*) {}

binder_status_t onTransact(AIBinder*, transaction_code_t code,
                            const AParcel* data, AParcel* reply) {
    int32_t enabled = 0;
    switch (code) {
        case GET_ANTI_AGING:
            return AParcel_writeInt32(
                    reply, GetBoolProperty(kAntiAgingUserProperty, false));
        case SET_ANTI_AGING:
            if (AParcel_readInt32(data, &enabled) != STATUS_OK) {
                return STATUS_BAD_VALUE;
            }
            return AParcel_writeInt32(
                    reply, setAntiAging(enabled != 0) ? 0 : -1);
        case GET_FAST_CHARGE:
            return AParcel_writeInt32(
                    reply, GetBoolProperty(kFastChargeProperty, false));
        case SET_FAST_CHARGE:
            if (AParcel_readInt32(data, &enabled) != STATUS_OK) {
                return STATUS_BAD_VALUE;
            }
            return AParcel_writeInt32(
                    reply, setFastCharge(enabled != 0) ? 0 : -1);
        case GET_WIRELESS_DETAILS:
            return ndk::AParcel_writeString(reply, wirelessDetails());
        default:
            return STATUS_UNKNOWN_TRANSACTION;
    }
}

AIBinder_Class* getServiceClass() {
    static AIBinder_Class* clazz = AIBinder_Class_define(
            kServiceDescriptor, onCreate, onDestroy, onTransact);
    return clazz;
}

}  // namespace

int main() {
    // Vendor libbinder uses the VNDR parcel header even if pointed at
    // /dev/binder, which cannot be consumed by Java's system ServiceManager.
    // NDK Binder is the supported transport used by vendor AIDL HALs and keeps
    // both the driver and parcel stability header consistent.
    ABinderProcess_setThreadPoolMaxThreadCount(0);
    AIBinder* service = AIBinder_new(getServiceClass(), nullptr);
    if (service == nullptr ||
        AServiceManager_addService(service, kServiceName) != STATUS_OK) {
        LOG(FATAL) << "Unable to register " << kServiceName;
    }

    applyPersistedState();
    ABinderProcess_joinThreadPool();
    return 0;
}
