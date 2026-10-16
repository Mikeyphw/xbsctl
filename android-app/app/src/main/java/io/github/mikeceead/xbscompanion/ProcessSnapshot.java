package io.github.mikeceead.xbscompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ProcessSnapshot {
    final boolean rootAvailable;
    final long totalTicks;
    final long capturedAt;
    final List<XbsProcess> processes;
    final String error;
    final ProcessScanDiagnostics diagnostics;

    ProcessSnapshot(boolean rootAvailable, long totalTicks, long capturedAt, List<XbsProcess> processes,
                    String error, ProcessScanDiagnostics diagnostics) {
        this.rootAvailable = rootAvailable;
        this.totalTicks = totalTicks;
        this.capturedAt = capturedAt;
        this.processes = Collections.unmodifiableList(new ArrayList<>(processes));
        this.error = error == null ? "" : error;
        this.diagnostics = diagnostics == null ? ProcessScanDiagnostics.empty() : diagnostics;
    }

    static ProcessSnapshot error(String message) {
        return error(message, ProcessScanDiagnostics.empty());
    }

    static ProcessSnapshot error(String message, ProcessScanDiagnostics diagnostics) {
        return new ProcessSnapshot(false, 0L, System.currentTimeMillis(), Collections.emptyList(),
            message, diagnostics);
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        JSONArray rows = new JSONArray();
        for (XbsProcess process : processes) rows.put(process.toJson());
        try {
            obj.put("root", rootAvailable);
            obj.put("total_ticks", totalTicks);
            obj.put("captured_at", capturedAt);
            obj.put("processes", rows);
            obj.put("error", error);
            obj.put("diagnostics", diagnostics.toJson());
        } catch (Exception ignored) {}
        return obj;
    }

    static ProcessSnapshot fromJson(JSONObject obj) {
        List<XbsProcess> rows = new ArrayList<>();
        JSONArray array = obj.optJSONArray("processes");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            if (row != null) rows.add(XbsProcess.fromJson(row));
        }
        return new ProcessSnapshot(
            obj.optBoolean("root"),
            obj.optLong("total_ticks"),
            obj.optLong("captured_at"),
            rows,
            obj.optString("error"),
            ProcessScanDiagnostics.fromJson(obj.optJSONObject("diagnostics")));
    }
}
