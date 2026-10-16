package io.github.mikeceead.xbscompanion;

import android.os.Bundle;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

final class XbsEvent {
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final String CONTRACT_VERSION = "1.0.0";
    private static final String APP = "xbs";
    final String id;
    final String type;
    final String name;
    final String state;
    final long timestamp;
    final String severity;
    final String message;
    final boolean replayed;
    final String payloadJson;

    XbsEvent(String id, String type, String name, String state, long timestamp,
             String severity, String message, boolean replayed, String payloadJson) {
        this.id = id == null ? "" : id;
        this.type = type == null ? "" : type;
        this.name = name == null ? "" : name;
        this.state = state == null ? "" : state;
        this.timestamp = timestamp;
        this.severity = severity == null ? "info" : severity;
        this.message = message == null ? "" : message;
        this.replayed = replayed;
        this.payloadJson = payloadJson == null ? "{}" : payloadJson;
    }

    static XbsEvent fromChange(JSONObject change, boolean replayed) {
        if (change == null) return null;
        String type = change.optString("type", "profile");
        String name = change.optString("name", type);
        String state = change.optString("state", "changed");
        long timestamp = change.optLong("timestamp", System.currentTimeMillis() / 1000L);
        String id = type + ":" + name + ":" + state + ":" + timestamp;
        String severity = "safe_mode".equals(type) && "active".equals(state) ? "warning" : "info";
        String message = pretty(name) + " " + prettyState(state);
        return new XbsEvent(id, type, name, state, timestamp, severity, message, replayed, change.toString());
    }

    static XbsEvent profile(String name, boolean active) {
        long now = System.currentTimeMillis() / 1000L;
        String state = active ? "active" : "inactive";
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "profile");
            payload.put("name", name);
            payload.put("state", state);
            payload.put("timestamp", now);
        } catch (JSONException ignored) {}
        return new XbsEvent("profile:" + name + ":" + state + ":" + now,
            "profile", name, state, now, "info",
            pretty(name) + (active ? " started" : " stopped"), false, payload.toString());
    }

    static XbsEvent runtime(String type, String state, String message, String severity) {
        long now = System.currentTimeMillis() / 1000L;
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", type);
            payload.put("name", "runtime");
            payload.put("state", state);
            payload.put("timestamp", now);
            payload.put("message", message);
        } catch (JSONException ignored) {}
        return new XbsEvent(type + ":runtime:" + state + ":" + now, type, "runtime", state,
            now, severity, message, false, payload.toString());
    }

    JSONObject toJson() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("name", name);
        data.put("state", state);
        data.put("payload", payloadJson);
        JSONObject obj = new JSONObject();
        obj.put("schema_version", SCHEMA_VERSION);
        obj.put("contract_version", CONTRACT_VERSION);
        obj.put("kind", "event");
        obj.put("event_id", id);
        obj.put("timestamp", Math.max(0L, timestamp) * 1000L);
        obj.put("app", APP);
        obj.put("type", type);
        obj.put("severity", severity);
        obj.put("message", message);
        obj.put("replayed", replayed);
        obj.put("data", data);
        return obj;
    }

    static XbsEvent fromJson(JSONObject obj) {
        if (obj == null) return null;
        boolean versioned = obj.has("schema_version") || obj.has("contract_version") || obj.has("kind");
        if (versioned) {
            if (!canonical(obj)) return null;
            JSONObject data = obj.optJSONObject("data");
            return new XbsEvent(obj.optString("event_id"), obj.optString("type"), data.optString("name"),
                data.optString("state"), obj.optLong("timestamp") / 1000L, obj.optString("severity", "info"),
                obj.optString("message"), obj.optBoolean("replayed", false), data.optString("payload", "{}"));
        }
        return new XbsEvent(obj.optString("id"), obj.optString("type"), obj.optString("name"),
            obj.optString("state"), obj.optLong("timestamp"), obj.optString("severity", "info"),
            obj.optString("message"), obj.optBoolean("replayed", false), obj.optString("payload", "{}"));
    }

    private static boolean canonical(JSONObject obj) {
        if (!(obj.opt("schema_version") instanceof String) || !(obj.opt("contract_version") instanceof String)
                || !SCHEMA_VERSION.equals(obj.optString("schema_version")) || !CONTRACT_VERSION.equals(obj.optString("contract_version"))
                || !"event".equals(obj.optString("kind")) || !APP.equals(obj.optString("app"))) return false;
        if (!(obj.opt("event_id") instanceof String) || obj.optString("event_id").isEmpty()
                || !(obj.opt("type") instanceof String) || obj.optString("type").isEmpty()
                || !(obj.opt("message") instanceof String) || !(obj.opt("replayed") instanceof Boolean)
                || !(obj.opt("data") instanceof JSONObject)) return false;
        Object at = obj.opt("timestamp");
        return at instanceof Number && ((Number) at).longValue() >= 0 && validSeverity(obj.optString("severity", ""));
    }

    private static boolean validSeverity(String value) {
        return "debug".equals(value) || "info".equals(value) || "warning".equals(value)
            || "error".equals(value) || "critical".equals(value);
    }


    Bundle variables(XbsSnapshot snapshot) {
        Bundle vars = new Bundle();
        vars.putString("%xbs_event", type + "_" + state);
        vars.putString("%xbs_event_id", id);
        vars.putString("%xbs_timestamp", String.valueOf(timestamp));
        vars.putString("%xbs_severity", severity);
        vars.putString("%xbs_message", message);
        vars.putString("%xbs_event_name", name);
        vars.putString("%xbs_event_action", state);
        vars.putString("%xbs_event_active", String.valueOf("active".equals(state)));
        vars.putString("%xbs_replayed", String.valueOf(replayed));
        vars.putString("%xbs_payload_json", payloadJson);
        if (snapshot != null) snapshot.addTaskerVariables(vars);
        return vars;
    }

    boolean isTransition() {
        return "active".equals(state) || "inactive".equals(state)
            || "started".equals(state) || "stopped".equals(state);
    }

    boolean isImportant() {
        return "safe_mode".equals(type) || "error".equals(severity) || "warning".equals(severity)
            || type.contains("failed") || state.contains("failed");
    }

    private static String pretty(String value) {
        if (value == null || value.isEmpty()) return "XBS";
        String[] parts = value.replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String prettyState(String state) {
        if ("active".equals(state)) return "started";
        if ("inactive".equals(state)) return "stopped";
        return pretty(state).toLowerCase(Locale.ROOT);
    }
}
