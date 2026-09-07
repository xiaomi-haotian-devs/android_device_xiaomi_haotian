// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;

/** Worker-confined C ABI adapter. No Bluetooth, Binder or UI state lives in native code. */
final class SonyNative {
    static { System.loadLibrary("haotian_sony_jni"); }
    static native long create(int connectedFd);
    static native void destroy(long handle);
    static native int poll(long handle);
    // 0: initialize, 1: telemetry sync, 2: commit, 3: last successful set's group readback.
    static native int request(long handle, int operation);
    static native boolean ready(long handle);
    static native byte[] snapshot(long handle);
    static native byte[] error(long handle);
    static native int set(long handle, String key, int value, String target);
    private SonyNative() {}
}
