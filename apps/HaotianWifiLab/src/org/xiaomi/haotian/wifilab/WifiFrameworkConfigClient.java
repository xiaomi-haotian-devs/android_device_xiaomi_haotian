/*
 * SPDX-FileCopyrightText: 2026 The Xiaomi haotian developers
 * SPDX-License-Identifier: Apache-2.0
 */

package org.xiaomi.haotian.wifilab;

import android.net.wifi.WifiManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Accessor for the device-local hidden WifiManager diagnostics API. */
final class WifiFrameworkConfigClient {
    static final int CONFIG_6GHZ_SUPPORT = 0;
    static final int CONFIG_SOFTAP_6GHZ_SUPPORT = 1;
    static final int CONFIG_SOFTAP_ACS_INCLUDE_DFS = 2;
    static final int CONFIG_SOFTAP_HE_SU_BEAMFORMEE = 3;
    static final int CONFIG_SOFTAP_HE_SU_BEAMFORMER = 4;
    static final int CONFIG_SOFTAP_IEEE80211AX = 5;
    static final int CONFIG_SOFTAP_OWE = 6;
    static final int CONFIG_SOFTAP_OWE_TRANSITION = 7;
    static final int CONFIG_DRIVER_REG_CHANGED_EVENT = 8;
    static final int CONFIG_SOFTAP_DYNAMIC_COUNTRY_CODE = 9;
    static final int CONFIG_STA_DYNAMIC_COUNTRY_CODE = 10;
    static final int CONFIG_FORCE_SOFTAP_RESTART_ON_COUNTRY_CODE = 11;
    static final int CONFIG_AUTO_UPGRADE_TO_BRIDGED_SOFTAP = 12;

    private final WifiManager wifiManager;
    private final Method getMethod;
    private final Method setMethod;
    private final Method clearMethod;

    WifiFrameworkConfigClient(WifiManager wifiManager) {
        this.wifiManager = wifiManager;

        Method get = null;
        Method set = null;
        Method clear = null;
        try {
            get = WifiManager.class.getMethod("getWifiFrameworkConfigForTest", int.class);
            set = WifiManager.class.getMethod(
                    "setWifiFrameworkConfigForTest", int.class, boolean.class);
            clear = WifiManager.class.getMethod("clearWifiFrameworkConfigForTest", int.class);
        } catch (NoSuchMethodException ignored) {
        }
        getMethod = get;
        setMethod = set;
        clearMethod = clear;
    }

    boolean isAvailable() {
        return getMethod != null && setMethod != null && clearMethod != null;
    }

    boolean get(int config) {
        requireAvailable();
        return (Boolean) invoke(getMethod, config);
    }

    void set(int config, boolean value) {
        requireAvailable();
        invoke(setMethod, config, value);
    }

    void clear(int config) {
        requireAvailable();
        invoke(clearMethod, config);
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new UnavailableException();
        }
    }

    private Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(wifiManager, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Wi-Fi diagnostics method is inaccessible", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Wi-Fi diagnostics call failed", cause);
        }
    }

    static final class UnavailableException extends RuntimeException {
        UnavailableException() {
            super("Matching Wi-Fi framework diagnostics API is unavailable");
        }
    }
}
