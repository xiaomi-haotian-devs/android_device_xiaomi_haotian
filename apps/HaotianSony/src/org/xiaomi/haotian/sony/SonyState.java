// SPDX-License-Identifier: Apache-2.0
package org.xiaomi.haotian.sony;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable wire snapshot; preferences never write into the controller's reported state. */
final class SonyState {
    final JSONObject json;
    final JSONObject values;
    final String address;
    final String name;
    final String status;
    final String error;
    final boolean ready;
    final boolean busy;
    SonyState(String encoded) {
        JSONObject parsed;
        try { parsed = new JSONObject(encoded); } catch (Exception e) { parsed = new JSONObject(); }
        json = parsed;
        JSONObject fields = json.optJSONObject("values");
        values = fields == null ? new JSONObject() : fields;
        address = json.optString("address"); name = json.optString("name");
        status = json.optString("status", "disconnected"); error = json.optString("error");
        ready = json.optBoolean("ready"); busy = json.optBoolean("busy");
    }
    boolean canEdit(String requestedAddress) {
        return ready && !busy && !address.isEmpty()
                && (requestedAddress == null || requestedAddress.isEmpty()
                    || address.equalsIgnoreCase(requestedAddress));
    }
    boolean has(int feature) {
        JSONArray features = json.optJSONArray("features");
        if (features != null) for (int i = 0; i < features.length(); i++)
            if (features.optInt(i) == feature) return true;
        return false;
    }
    JSONArray array(String key) {
        JSONArray value = json.optJSONArray(key);
        return value == null ? new JSONArray() : value;
    }
    int value(String key, int fallback) { return values.optInt(key, fallback); }
}
