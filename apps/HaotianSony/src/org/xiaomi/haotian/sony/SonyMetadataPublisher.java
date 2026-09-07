// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;
import android.bluetooth.BluetoothDevice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Publishes only MDR-confirmed identity/battery values for this session's bonded peer. */
final class SonyMetadataPublisher {
    static void publish(BluetoothDevice device, JSONObject state) {
        if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED) return;
        String model = state.optString("model");
        if (!model.isEmpty()) {
            put(device, BluetoothDevice.METADATA_MANUFACTURER_NAME, "Sony");
            put(device, BluetoothDevice.METADATA_MODEL_NAME, model);
        }
        JSONArray batteries = state.optJSONArray("batteries");
        if (batteries == null) return;
        int main = -1;
        for (int i = 0; i < batteries.length(); i++) {
            JSONObject battery = batteries.optJSONObject(i);
            if (battery == null) continue;
            int part = battery.optInt("part", -1), level = battery.optInt("level", -1);
            if (level < 0 || level > 100) continue;
            int charging = battery.optInt("charging");
            switch (part) {
                case 0:
                    put(device, BluetoothDevice.METADATA_MAIN_BATTERY, Integer.toString(level));
                    if (charging == 1 || charging == 2 || charging == 3)
                        put(device, BluetoothDevice.METADATA_MAIN_CHARGING, Boolean.toString(charging == 2));
                    break;
                case 1:
                    put(device, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, "true");
                    put(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY, Integer.toString(level));
                    charging(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING, charging);
                    main = main < 0 ? level : Math.min(main, level);
                    break;
                case 2:
                    put(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY, Integer.toString(level));
                    charging(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING, charging);
                    main = main < 0 ? level : Math.min(main, level);
                    break;
                case 3:
                    put(device, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY, Integer.toString(level));
                    charging(device, BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING, charging);
                    break;
                default: break;
            }
        }
        if (main >= 0) put(device, BluetoothDevice.METADATA_MAIN_BATTERY, Integer.toString(main));
    }
    private static void put(BluetoothDevice device, int key, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(device.getMetadata(key), data)) device.setMetadata(key, data);
    }
    private static void charging(BluetoothDevice device, int key, int value) {
        if (value >= 1 && value <= 3) put(device, key, Boolean.toString(value == 2));
    }
    private SonyMetadataPublisher() {}
}
