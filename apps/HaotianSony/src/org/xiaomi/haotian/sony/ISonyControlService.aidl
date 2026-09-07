// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;
import org.xiaomi.haotian.sony.ISonyStateCallback;

/** Signature-protected low-rate facade. The requested address must match the live session. */
interface ISonyControlService {
    String getState();
    void registerCallback(ISonyStateCallback callback);
    void unregisterCallback(ISonyStateCallback callback);
    oneway void selectDevice(String address);
    oneway void refresh(String address);
    oneway void setValue(String address, String key, int value, String target);
}
