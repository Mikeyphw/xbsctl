package io.github.mikeceead.xbscompanion;

import org.json.JSONObject;

final class RootStatus {
    final boolean checked;
    final boolean granted;
    final boolean xbsInstalled;
    final boolean xbsctlInstalled;
    final String manager;
    final String uid;
    final String message;
    final long checkedAt;

    RootStatus(boolean checked, boolean granted, boolean xbsInstalled, boolean xbsctlInstalled,
               String manager, String uid, String message, long checkedAt) {
        this.checked = checked;
        this.granted = granted;
        this.xbsInstalled = xbsInstalled;
        this.xbsctlInstalled = xbsctlInstalled;
        this.manager = manager == null || manager.isEmpty() ? "Unknown" : manager;
        this.uid = uid == null ? "" : uid;
        this.message = message == null ? "" : message;
        this.checkedAt = checkedAt;
    }

    static RootStatus unchecked() {
        return new RootStatus(false, false, false, false, "Not checked", "", "Root access has not been checked yet", 0L);
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("checked", checked); obj.put("granted", granted); obj.put("xbs_installed", xbsInstalled);
            obj.put("xbsctl_installed", xbsctlInstalled); obj.put("manager", manager); obj.put("uid", uid);
            obj.put("message", message); obj.put("checked_at", checkedAt);
        } catch (Exception ignored) {}
        return obj;
    }

    static RootStatus fromJson(JSONObject obj) {
        if (obj == null) return unchecked();
        return new RootStatus(obj.optBoolean("checked"), obj.optBoolean("granted"),
            obj.optBoolean("xbs_installed"), obj.optBoolean("xbsctl_installed"),
            obj.optString("manager", "Unknown"), obj.optString("uid", ""),
            obj.optString("message", ""), obj.optLong("checked_at", 0L));
    }

    String summary() {
        if (!checked) return "Not checked";
        if (!granted) return message.isEmpty() ? "Root denied" : message;
        if (!xbsInstalled) return manager + " · root granted · XBS missing";
        return manager + " · root granted";
    }
}
