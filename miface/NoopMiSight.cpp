/*
 * SPDX-FileCopyrightText: 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <string>

namespace android {

class MiSight {
  public:
    static int sendEvent(int eventId, const std::string& payload);
};

int MiSight::sendEvent(int /* eventId */, const std::string& /* payload */) {
    return 0;
}

}  // namespace android
