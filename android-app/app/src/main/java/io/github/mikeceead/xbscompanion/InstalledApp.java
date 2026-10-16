package io.github.mikeceead.xbscompanion;

import org.json.JSONObject;

final class InstalledApp {
    final String packageName;
    final String label;
    final int uid;
    final boolean system;

    InstalledApp(String packageName, String label, int uid, boolean system) {
        this.packageName = packageName == null ? "" : packageName;
        this.label = label == null || label.isEmpty() ? this.packageName : label;
        this.uid = uid;
        this.system = system;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try { obj.put("package", packageName); obj.put("label", label); obj.put("uid", uid); obj.put("system", system); }
        catch (Exception ignored) {}
        return obj;
    }

    static InstalledApp fromJson(JSONObject obj) {
        return new InstalledApp(obj.optString("package"), obj.optString("label"), obj.optInt("uid", -1), obj.optBoolean("system"));
    }
}
