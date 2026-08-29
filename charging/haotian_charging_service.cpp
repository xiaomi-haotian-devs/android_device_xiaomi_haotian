/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "HaotianChargingService"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <binder/Binder.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <binder/ProcessState.h>
#include <utils/String16.h>

namespace {

using android::BBinder;
using android::IBinder;
using android::Parcel;
using android::status_t;
using android::base::GetBoolProperty;
using android::base::SetProperty;
using android::base::WriteStringToFile;

constexpr char kServiceName[] = "haotian_charging";
constexpr char kAntiAgingProperty[] = "persist.vendor.batteryantiaging";
constexpr char kAntiAgingUserProperty[] = "persist.vendor.haotian.batteryantiaging";
constexpr char kFastChargeProperty[] = "persist.vendor.haotian.fast_charge";
constexpr char kFastChargeNode[] = "/sys/class/thermal/thermal_message/charger_temp";
constexpr char kSmartFvNode[] = "/sys/class/xm_power/charger/smart_charge/smart_fv";

enum Transaction : uint32_t {
    GET_ANTI_AGING = IBinder::FIRST_CALL_TRANSACTION,
    SET_ANTI_AGING,
    GET_FAST_CHARGE,
    SET_FAST_CHARGE,
};

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

class ChargingService final : public BBinder {
  protected:
    status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply,
                        uint32_t flags = 0) override {
        (void)flags;
        switch (code) {
            case GET_ANTI_AGING:
                reply->writeInt32(GetBoolProperty(kAntiAgingUserProperty, false));
                return android::OK;
            case SET_ANTI_AGING:
                reply->writeInt32(setAntiAging(data.readInt32() != 0) ? 0 : -1);
                return android::OK;
            case GET_FAST_CHARGE:
                reply->writeInt32(GetBoolProperty(kFastChargeProperty, false));
                return android::OK;
            case SET_FAST_CHARGE:
                reply->writeInt32(setFastCharge(data.readInt32() != 0) ? 0 : -1);
                return android::OK;
            default:
                return BBinder::onTransact(code, data, reply, flags);
        }
    }
};

}  // namespace

int main() {
    android::sp<android::IServiceManager> serviceManager =
            android::defaultServiceManager();
    if (serviceManager->addService(android::String16(kServiceName),
                                   new ChargingService()) != android::OK) {
        LOG(FATAL) << "Unable to register " << kServiceName;
    }

    applyPersistedState();
    android::ProcessState::self()->startThreadPool();
    android::IPCThreadState::self()->joinThreadPool();
    return 0;
}
