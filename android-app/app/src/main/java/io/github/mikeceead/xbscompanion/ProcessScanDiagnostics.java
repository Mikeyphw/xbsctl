package io.github.mikeceead.xbscompanion;

import org.json.JSONObject;

/** Bounded evidence describing how complete a /proc sample was. */
final class ProcessScanDiagnostics {
    final int cpuCount;
    final int scanned;
    final int readable;
    final int restricted;
    final int malformed;
    final int cmdlineUnavailable;
    final int executableUnavailable;
    final int contextUnavailable;

    ProcessScanDiagnostics(int cpuCount, int scanned, int readable, int restricted, int malformed,
                           int cmdlineUnavailable, int executableUnavailable, int contextUnavailable) {
        this.cpuCount = Math.max(1, cpuCount);
        this.scanned = Math.max(0, scanned);
        this.readable = Math.max(0, readable);
        this.restricted = Math.max(0, restricted);
        this.malformed = Math.max(0, malformed);
        this.cmdlineUnavailable = Math.max(0, cmdlineUnavailable);
        this.executableUnavailable = Math.max(0, executableUnavailable);
        this.contextUnavailable = Math.max(0, contextUnavailable);
    }

    static ProcessScanDiagnostics empty() { return new ProcessScanDiagnostics(1, 0, 0, 0, 0, 0, 0, 0); }

    boolean partial() { return restricted > 0 || malformed > 0; }

    String summary() {
        StringBuilder text = new StringBuilder();
        text.append(readable).append(" readable of ").append(scanned > 0 ? scanned : readable).append(" visible process entries");
        if (restricted > 0) text.append(" · ").append(restricted).append(" restricted");
        if (malformed > 0) text.append(" · ").append(malformed).append(" malformed");
        if (cmdlineUnavailable > 0) text.append(" · cmdline unavailable for ").append(cmdlineUnavailable);
        if (executableUnavailable > 0) text.append(" · executable unavailable for ").append(executableUnavailable);
        if (contextUnavailable > 0) text.append(" · SELinux context unavailable for ").append(contextUnavailable);
        return text.toString();
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("cpu_count", cpuCount);
            obj.put("scanned", scanned);
            obj.put("readable", readable);
            obj.put("restricted", restricted);
            obj.put("malformed", malformed);
            obj.put("cmdline_unavailable", cmdlineUnavailable);
            obj.put("executable_unavailable", executableUnavailable);
            obj.put("context_unavailable", contextUnavailable);
        } catch (Exception ignored) {}
        return obj;
    }

    static ProcessScanDiagnostics fromJson(JSONObject obj) {
        if (obj == null) return empty();
        return new ProcessScanDiagnostics(
            obj.optInt("cpu_count", 1),
            obj.optInt("scanned"),
            obj.optInt("readable"),
            obj.optInt("restricted"),
            obj.optInt("malformed"),
            obj.optInt("cmdline_unavailable"),
            obj.optInt("executable_unavailable"),
            obj.optInt("context_unavailable"));
    }
}
