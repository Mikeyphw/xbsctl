package io.github.mikeceead.xbscompanion;

import org.json.JSONObject;

/** Immutable, bounded snapshot of root and Android runtime readiness. */
final class RootDiagnosticsReport {
    static final int FORMAT_VERSION = 1;
    static final long STALE_AFTER_MS = 5 * 60_000L;

    final long capturedAt;
    final boolean rootGranted;
    final String manager;
    final String managerVersion;
    final String suPath;
    final String xbsPath;
    final boolean xbsExecutable;
    final String xbsctlPath;
    final boolean xbsctlExecutable;
    final boolean configExists;
    final boolean configReadable;
    final boolean configWritable;
    final boolean listExists;
    final boolean listReadable;
    final boolean listWritable;
    final boolean backupExists;
    final boolean backupReadable;
    final boolean backupWritable;
    final boolean notificationPermissionGranted;
    final boolean notificationsEnabled;
    final boolean commandChannelReady;
    final boolean runtimeChannelReady;
    final boolean alertsChannelReady;
    final boolean foregroundServicePermission;
    final boolean specialUsePermission;
    final boolean commandServiceActive;
    final int activeCommandCount;
    final boolean monitorConfigured;
    final boolean monitorServiceActive;
    final String error;

    RootDiagnosticsReport(
        long capturedAt,
        boolean rootGranted,
        String manager,
        String managerVersion,
        String suPath,
        String xbsPath,
        boolean xbsExecutable,
        String xbsctlPath,
        boolean xbsctlExecutable,
        boolean configExists,
        boolean configReadable,
        boolean configWritable,
        boolean listExists,
        boolean listReadable,
        boolean listWritable,
        boolean backupExists,
        boolean backupReadable,
        boolean backupWritable,
        boolean notificationPermissionGranted,
        boolean notificationsEnabled,
        boolean commandChannelReady,
        boolean runtimeChannelReady,
        boolean alertsChannelReady,
        boolean foregroundServicePermission,
        boolean specialUsePermission,
        boolean commandServiceActive,
        int activeCommandCount,
        boolean monitorConfigured,
        boolean monitorServiceActive,
        String error
    ) {
        this.capturedAt = capturedAt;
        this.rootGranted = rootGranted;
        this.manager = clean(manager);
        this.managerVersion = clean(managerVersion);
        this.suPath = clean(suPath);
        this.xbsPath = clean(xbsPath);
        this.xbsExecutable = xbsExecutable;
        this.xbsctlPath = clean(xbsctlPath);
        this.xbsctlExecutable = xbsctlExecutable;
        this.configExists = configExists;
        this.configReadable = configReadable;
        this.configWritable = configWritable;
        this.listExists = listExists;
        this.listReadable = listReadable;
        this.listWritable = listWritable;
        this.backupExists = backupExists;
        this.backupReadable = backupReadable;
        this.backupWritable = backupWritable;
        this.notificationPermissionGranted = notificationPermissionGranted;
        this.notificationsEnabled = notificationsEnabled;
        this.commandChannelReady = commandChannelReady;
        this.runtimeChannelReady = runtimeChannelReady;
        this.alertsChannelReady = alertsChannelReady;
        this.foregroundServicePermission = foregroundServicePermission;
        this.specialUsePermission = specialUsePermission;
        this.commandServiceActive = commandServiceActive;
        this.activeCommandCount = Math.max(0, activeCommandCount);
        this.monitorConfigured = monitorConfigured;
        this.monitorServiceActive = monitorServiceActive;
        this.error = clean(error);
    }

    static RootDiagnosticsReport unchecked() {
        return new RootDiagnosticsReport(0L, false, "Unknown", "", "", "", false, "", false,
            false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, 0, false, false, "Not checked yet");
    }

    boolean binariesReady() { return xbsExecutable && xbsctlExecutable; }
    boolean storageReady() {
        return configExists && configReadable && configWritable
            && listExists && listReadable && listWritable
            && backupExists && backupReadable && backupWritable;
    }
    boolean notificationsReady() {
        return notificationPermissionGranted && notificationsEnabled
            && commandChannelReady && runtimeChannelReady && alertsChannelReady;
    }
    boolean servicesReady() {
        return foregroundServicePermission && specialUsePermission
            && (activeCommandCount == 0 || commandServiceActive);
    }
    boolean isFresh(long now) { return capturedAt > 0 && now - capturedAt <= STALE_AFTER_MS; }

    int failureCount() {
        int count = 0;
        if (!rootGranted) count++;
        if (rootGranted && !binariesReady()) count++;
        if (rootGranted && !storageReady()) count++;
        if (!foregroundServicePermission || !specialUsePermission) count++;
        return count;
    }

    int warningCount() {
        int count = 0;
        if (!notificationsReady()) count++;
        if (activeCommandCount > 0 && !commandServiceActive) count++;
        if (monitorConfigured && !monitorServiceActive) count++;
        if (!error.isEmpty() && failureCount() == 0) count++;
        return count;
    }

    String headline() {
        if (capturedAt == 0L) return "Diagnostics have not run";
        if (!rootGranted) return "Root access is required";
        if (!binariesReady()) return "XBS module binaries are incomplete";
        if (!storageReady()) return "XBS storage paths need attention";
        if (!foregroundServicePermission || !specialUsePermission) return "Foreground service setup is incomplete";
        if (warningCount() > 0) return "Root runtime ready with warnings";
        return "Root runtime ready";
    }

    String compactSummary() {
        return "failures=" + failureCount() + " warnings=" + warningCount()
            + " root=" + rootGranted + " binaries=" + binariesReady()
            + " storage=" + storageReady() + " notifications=" + notificationsReady()
            + " services=" + servicesReady();
    }

    String supportText() {
        StringBuilder out = new StringBuilder(1024);
        out.append("format=").append(FORMAT_VERSION).append(" captured_at=").append(capturedAt).append('\n');
        out.append("summary=").append(compactSummary()).append('\n');
        out.append("manager=").append(manager).append(" version=").append(managerVersion).append(" su=").append(suPath).append('\n');
        out.append("xbs_path=").append(xbsPath).append(" executable=").append(xbsExecutable).append('\n');
        out.append("xbsctl_path=").append(xbsctlPath).append(" executable=").append(xbsctlExecutable).append('\n');
        out.append("config exists=").append(configExists).append(" readable=").append(configReadable).append(" writable=").append(configWritable).append('\n');
        out.append("lists exists=").append(listExists).append(" readable=").append(listReadable).append(" writable=").append(listWritable).append('\n');
        out.append("backups exists=").append(backupExists).append(" readable=").append(backupReadable).append(" writable=").append(backupWritable).append('\n');
        out.append("notifications permission=").append(notificationPermissionGranted).append(" enabled=").append(notificationsEnabled)
            .append(" command_channel=").append(commandChannelReady).append(" runtime_channel=").append(runtimeChannelReady)
            .append(" alerts_channel=").append(alertsChannelReady).append('\n');
        out.append("services fgs_permission=").append(foregroundServicePermission).append(" special_use_permission=").append(specialUsePermission)
            .append(" command_active=").append(commandServiceActive).append(" active_runs=").append(activeCommandCount)
            .append(" monitor_configured=").append(monitorConfigured).append(" monitor_active=").append(monitorServiceActive).append('\n');
        if (!error.isEmpty()) out.append("error=").append(error).append('\n');
        return out.toString();
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("format", FORMAT_VERSION);
            json.put("capturedAt", capturedAt);
            json.put("rootGranted", rootGranted);
            json.put("manager", manager);
            json.put("managerVersion", managerVersion);
            json.put("suPath", suPath);
            json.put("xbsPath", xbsPath);
            json.put("xbsExecutable", xbsExecutable);
            json.put("xbsctlPath", xbsctlPath);
            json.put("xbsctlExecutable", xbsctlExecutable);
            json.put("configExists", configExists);
            json.put("configReadable", configReadable);
            json.put("configWritable", configWritable);
            json.put("listExists", listExists);
            json.put("listReadable", listReadable);
            json.put("listWritable", listWritable);
            json.put("backupExists", backupExists);
            json.put("backupReadable", backupReadable);
            json.put("backupWritable", backupWritable);
            json.put("notificationPermissionGranted", notificationPermissionGranted);
            json.put("notificationsEnabled", notificationsEnabled);
            json.put("commandChannelReady", commandChannelReady);
            json.put("runtimeChannelReady", runtimeChannelReady);
            json.put("alertsChannelReady", alertsChannelReady);
            json.put("foregroundServicePermission", foregroundServicePermission);
            json.put("specialUsePermission", specialUsePermission);
            json.put("commandServiceActive", commandServiceActive);
            json.put("activeCommandCount", activeCommandCount);
            json.put("monitorConfigured", monitorConfigured);
            json.put("monitorServiceActive", monitorServiceActive);
            json.put("error", error);
        } catch (Exception ignored) {}
        return json;
    }

    static RootDiagnosticsReport fromJson(JSONObject json) {
        if (json == null) return unchecked();
        return new RootDiagnosticsReport(
            json.optLong("capturedAt", 0L),
            json.optBoolean("rootGranted", false),
            json.optString("manager", "Unknown"),
            json.optString("managerVersion", ""),
            json.optString("suPath", ""),
            json.optString("xbsPath", ""),
            json.optBoolean("xbsExecutable", false),
            json.optString("xbsctlPath", ""),
            json.optBoolean("xbsctlExecutable", false),
            json.optBoolean("configExists", false),
            json.optBoolean("configReadable", false),
            json.optBoolean("configWritable", false),
            json.optBoolean("listExists", false),
            json.optBoolean("listReadable", false),
            json.optBoolean("listWritable", false),
            json.optBoolean("backupExists", false),
            json.optBoolean("backupReadable", false),
            json.optBoolean("backupWritable", false),
            json.optBoolean("notificationPermissionGranted", false),
            json.optBoolean("notificationsEnabled", false),
            json.optBoolean("commandChannelReady", false),
            json.optBoolean("runtimeChannelReady", false),
            json.optBoolean("alertsChannelReady", false),
            json.optBoolean("foregroundServicePermission", false),
            json.optBoolean("specialUsePermission", false),
            json.optBoolean("commandServiceActive", false),
            json.optInt("activeCommandCount", 0),
            json.optBoolean("monitorConfigured", false),
            json.optBoolean("monitorServiceActive", false),
            json.optString("error", "")
        );
    }

    private static String clean(String value) {
        String cleaned = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim().replaceAll(" +", " ");
        return cleaned.length() <= 240 ? cleaned : cleaned.substring(0, 239) + "…";
    }
}
