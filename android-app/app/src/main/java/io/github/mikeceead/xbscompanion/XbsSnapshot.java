package io.github.mikeceead.xbscompanion;

import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class XbsSnapshot {
    final boolean available;
    final boolean safeMode;
    final boolean paused;
    final String mode;
    final List<String> active;
    final List<String> inactive;
    final XbsEvent lastChange;
    final String rawJson;
    final String error;
    final long capturedAt;

    XbsSnapshot(boolean available, boolean safeMode, boolean paused, String mode,
                List<String> active, List<String> inactive, XbsEvent lastChange,
                String rawJson, String error, long capturedAt) {
        this.available = available;
        this.safeMode = safeMode;
        this.paused = paused;
        this.mode = mode == null || mode.isEmpty() ? "unknown" : mode;
        LinkedHashSet<String> activeSet = runtimeEvents(active);
        LinkedHashSet<String> inactiveSet = runtimeEvents(inactive);
        inactiveSet.removeAll(activeSet);
        this.active = Collections.unmodifiableList(new ArrayList<>(activeSet));
        this.inactive = Collections.unmodifiableList(new ArrayList<>(inactiveSet));
        this.lastChange = lastChange;
        this.rawJson = rawJson == null ? "{}" : rawJson;
        this.error = error == null ? "" : error;
        this.capturedAt = capturedAt;
    }

    static XbsSnapshot unavailable(String error) {
        return new XbsSnapshot(false, false, false, "unavailable", Collections.emptyList(),
            Collections.emptyList(), null, "{}", error, System.currentTimeMillis());
    }

    static XbsSnapshot parse(String raw) {
        try {
            JSONObject root = new JSONObject(raw == null ? "{}" : raw.trim());
            JSONObject event = root.optJSONObject("event");
            JSONObject status = root.optJSONObject("status");
            if (event == null) event = new JSONObject();
            if (status == null) status = new JSONObject();
            LinkedHashSet<String> active = new LinkedHashSet<>();
            LinkedHashSet<String> inactive = new LinkedHashSet<>();
            active.addAll(strings(event.optJSONArray("active")));
            inactive.addAll(strings(event.optJSONArray("inactive")));
            active.addAll(strings(status.optJSONArray("active_profiles")));
            JSONObject profiles = event.optJSONObject("profiles");
            if (profiles != null) {
                Iterator<String> names = profiles.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    if ("template".equals(name)) continue;
                    String state = profiles.optString(name, "");
                    if ("active".equalsIgnoreCase(state) || "true".equalsIgnoreCase(state)) active.add(name);
                    else if ("inactive".equalsIgnoreCase(state) || "false".equalsIgnoreCase(state)) inactive.add(name);
                }
            }
            inactive.removeAll(active);
            boolean safe = event.optBoolean("safe_mode", "safe".equals(status.optString("mode")));
            String mode = status.optString("mode", safe ? "safe" : "normal");
            boolean paused = "paused".equalsIgnoreCase(mode) || status.optBoolean("paused", false);
            XbsEvent change = XbsEvent.fromChange(event.optJSONObject("last_change"), false);
            boolean available = root.optBoolean("available", true);
            String error = root.optString("error", "");
            return new XbsSnapshot(available, safe, paused, mode, new ArrayList<>(active), new ArrayList<>(inactive), change,
                root.toString(), error, System.currentTimeMillis());
        } catch (JSONException error) {
            return unavailable("Invalid XBS state: " + error.getMessage());
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("available", available);
        obj.put("safe_mode", safeMode);
        obj.put("paused", paused);
        obj.put("mode", mode);
        obj.put("active", new JSONArray(active));
        obj.put("inactive", new JSONArray(inactive));
        obj.put("last_change", lastChange == null ? JSONObject.NULL : lastChange.toJson());
        obj.put("raw", rawJson);
        obj.put("error", error);
        obj.put("captured_at", capturedAt);
        return obj;
    }

    static XbsSnapshot fromJson(JSONObject obj) {
        XbsEvent change = null;
        JSONObject changeObj = obj.optJSONObject("last_change");
        if (changeObj != null) change = XbsEvent.fromJson(changeObj);
        return new XbsSnapshot(obj.optBoolean("available"), obj.optBoolean("safe_mode"),
            obj.optBoolean("paused"), obj.optString("mode"), strings(obj.optJSONArray("active")),
            strings(obj.optJSONArray("inactive")), change, obj.optString("raw", "{}"),
            obj.optString("error"), obj.optLong("captured_at", System.currentTimeMillis()));
    }

    String headline() {
        if (!available) return "XBS unavailable";
        if (safeMode) return "Safe mode active";
        if (paused) return "Runtime paused";
        if (!active.isEmpty()) return active.size() == 1 ? pretty(active.get(0)) + " active" : active.size() + " events active";
        return "XBS running";
    }

    String summary() {
        if (!available) return error.isEmpty() ? "The XBS runtime could not be read." : error;
        if (safeMode) return active.isEmpty() ? "Automatic event handling is suspended." : "Automatic handling is suspended · active before safe mode: " + join(active);
        if (paused) return active.size() + " active event(s) are paused: " + join(active);
        if (!active.isEmpty()) return "Active simultaneously: " + join(active);
        if (!inactive.isEmpty()) return "No active events · " + inactive.size() + " available";
        return "Runtime state is available.";
    }

    void addTaskerVariables(Bundle vars) {
        vars.putString("%xbs_runtime_state", mode);
        vars.putString("%xbs_running", String.valueOf(available && !paused));
        vars.putString("%xbs_paused", String.valueOf(paused));
        vars.putString("%xbs_safe_mode", String.valueOf(safeMode));
        vars.putString("%xbs_active_profiles", join(active));
        vars.putString("%xbs_active_count", String.valueOf(active.size()));
        vars.putString("%xbs_inactive_profiles", join(inactive));
        vars.putString("%xbs_available", String.valueOf(available));
    }

    boolean equivalentState(XbsSnapshot other) {
        if (other == null) return false;
        return available == other.available && safeMode == other.safeMode && paused == other.paused
            && mode.equals(other.mode) && active.equals(other.active) && inactive.equals(other.inactive);
    }

    private static List<String> strings(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static LinkedHashSet<String> runtimeEvents(List<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null) return out;
        for (String value : values) {
            String name = value == null ? "" : value.trim();
            if (name.isEmpty() || "template".equals(name)) continue;
            out.add(name);
        }
        return out;
    }

    static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }

    static String pretty(String value) { return value == null ? "" : value.replace('_', ' '); }
}
