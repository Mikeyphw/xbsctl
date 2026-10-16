package io.github.mikeceead.xbscompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class XbsProcess {
    final int pid;
    final int parentPid;
    final int uid;
    final String processName;
    final String command;
    final String executable;
    final String selinuxContext;
    final long rssKb;
    final long ticks;
    final long startTicks;
    final long sampledAt;
    final int niceValue;
    final double cpuPercent;
    final String packageName;
    final String appLabel;
    final boolean commandLineObserved;
    final boolean executableObserved;
    final boolean contextObserved;
    final List<String> ruleEvents;

    XbsProcess(int pid, int parentPid, int uid, String processName, String command,
               String executable, String selinuxContext, long rssKb, long ticks,
               long startTicks, long sampledAt, int niceValue, double cpuPercent,
               String packageName, String appLabel, boolean commandLineObserved,
               boolean executableObserved, boolean contextObserved, List<String> ruleEvents) {
        this.pid = pid;
        this.parentPid = Math.max(0, parentPid);
        this.uid = uid;
        this.processName = processName == null ? "" : processName;
        this.command = command == null ? "" : command;
        this.executable = executable == null ? "" : executable;
        this.selinuxContext = selinuxContext == null ? "" : selinuxContext;
        this.rssKb = Math.max(0L, rssKb);
        this.ticks = Math.max(0L, ticks);
        this.startTicks = Math.max(0L, startTicks);
        this.sampledAt = Math.max(0L, sampledAt);
        this.niceValue = niceValue;
        this.cpuPercent = Math.max(0.0, cpuPercent);
        this.packageName = packageName == null ? "" : packageName;
        this.appLabel = appLabel == null || appLabel.isEmpty()
            ? (this.packageName.isEmpty() ? "System process" : this.packageName)
            : appLabel;
        this.commandLineObserved = commandLineObserved;
        this.executableObserved = executableObserved;
        this.contextObserved = contextObserved;
        this.ruleEvents = Collections.unmodifiableList(new ArrayList<>(
            ruleEvents == null ? Collections.emptyList() : ruleEvents));
    }

    String identityKey() { return pid + ":" + startTicks; }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("pid", pid);
            obj.put("ppid", parentPid);
            obj.put("uid", uid);
            obj.put("process", processName);
            obj.put("command", command);
            obj.put("executable", executable);
            obj.put("selinux_context", selinuxContext);
            obj.put("rss_kb", rssKb);
            obj.put("ticks", ticks);
            obj.put("start_ticks", startTicks);
            obj.put("sampled_at", sampledAt);
            obj.put("nice", niceValue);
            obj.put("cpu", cpuPercent);
            obj.put("package", packageName);
            obj.put("app", appLabel);
            obj.put("cmdline_observed", commandLineObserved);
            obj.put("executable_observed", executableObserved);
            obj.put("context_observed", contextObserved);
            obj.put("rule_events", new JSONArray(ruleEvents));
        } catch (Exception ignored) {}
        return obj;
    }

    static XbsProcess fromJson(JSONObject obj) {
        List<String> events = new ArrayList<>();
        JSONArray array = obj.optJSONArray("rule_events");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i);
            if (!value.isEmpty()) events.add(value);
        }
        return new XbsProcess(
            obj.optInt("pid"),
            obj.optInt("ppid"),
            obj.optInt("uid"),
            obj.optString("process"),
            obj.optString("command"),
            obj.optString("executable"),
            obj.optString("selinux_context"),
            obj.optLong("rss_kb"),
            obj.optLong("ticks"),
            obj.optLong("start_ticks"),
            obj.optLong("sampled_at"),
            obj.optInt("nice"),
            obj.optDouble("cpu"),
            obj.optString("package"),
            obj.optString("app"),
            obj.optBoolean("cmdline_observed"),
            obj.optBoolean("executable_observed"),
            obj.optBoolean("context_observed"),
            events);
    }

    String ramLabel() {
        if (rssKb >= 1024L * 1024L) return String.format(java.util.Locale.US, "%.2f GB", rssKb / 1048576.0);
        if (rssKb >= 1024L) return String.format(java.util.Locale.US, "%.1f MB", rssKb / 1024.0);
        return rssKb + " KB";
    }
}
