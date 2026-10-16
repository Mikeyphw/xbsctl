package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Performs one bounded root probe and merges it with Android permission/service state. */
final class RootDiagnosticsProbe {
    private static final String FOREGROUND_SERVICE_PERMISSION = "android.permission.FOREGROUND_SERVICE";
    private static final String FOREGROUND_SERVICE_SPECIAL_USE_PERMISSION = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE";

    private RootDiagnosticsProbe() {}

    static RootDiagnosticsReport collect(Context context, boolean forceRoot) {
        Context app = context.getApplicationContext();
        RootStatus root = XbsRootApi.checkRoot(app, forceRoot);
        Map<String, String> fields = new LinkedHashMap<>();
        String error = "";
        if (root.granted) {
            XbsRootApi.CommandOutput output = XbsRootApi.runRoot(buildShell());
            if (output.exitCode == 0) fields.putAll(parse(output.stdout));
            else error = clean(output.error.isEmpty() ? output.stdout : output.error);
        } else {
            error = clean(root.message);
        }

        new NotificationController(app);
        NotificationController.Readiness notifications = NotificationController.readiness(app);
        boolean foregroundPermission = permissionGranted(app, FOREGROUND_SERVICE_PERMISSION);
        boolean specialUsePermission = Build.VERSION.SDK_INT < 34
            || permissionGranted(app, FOREGROUND_SERVICE_SPECIAL_USE_PERMISSION);
        List<RunRecord> active = RunStore.active(app);

        RootDiagnosticsReport report = new RootDiagnosticsReport(
            System.currentTimeMillis(),
            root.granted,
            value(fields, "manager", root.manager),
            value(fields, "manager_version", "unknown"),
            value(fields, "su_path", "unknown"),
            value(fields, "xbs_path", "not found"),
            bool(fields, "xbs_executable"),
            value(fields, "xbsctl_path", "not found"),
            bool(fields, "xbsctl_executable"),
            bool(fields, "config_exists"),
            bool(fields, "config_readable"),
            bool(fields, "config_writable"),
            bool(fields, "list_exists"),
            bool(fields, "list_readable"),
            bool(fields, "list_writable"),
            bool(fields, "backup_exists"),
            bool(fields, "backup_readable"),
            bool(fields, "backup_writable"),
            notifications.permissionGranted,
            notifications.appEnabled,
            notifications.commandChannelReady,
            notifications.runtimeChannelReady,
            notifications.alertsChannelReady,
            foregroundPermission,
            specialUsePermission,
            RootCommandService.isServiceActive(),
            active.size(),
            AppSettings.monitorEnabled(app),
            EventMonitorService.isServiceActive(),
            error
        );
        RootDiagnosticsStore.save(app, report);
        XbsCompatibilityProbe.collect(app, forceRoot);
        return report;
    }

    static XbsRootApi.CommandOutput repairStoragePaths(Context context) {
        String configDir = parent(XbsConfigApi.CONFIG);
        String shell = "set -e; "
            + storageRepairSafetyShell()
            + ensureDir("config", configDir)
            + ensureDir("list", XbsConfigApi.LIST_DIR)
            + ensureDir("backup", XbsConfigApi.BACKUP_DIR)
            + canonicalConfigEnvironmentShell(configDir)
            + XbsRuntimeResolver.resolveXbs("XBS_INIT")
            + "if [ -z \"$XBS_INIT\" ]; then printf 'XBS binary not found' >&2; exit 127; fi; "
            + "CREATED=false; if [ ! -s " + XbsConfigApi.q(XbsConfigApi.CONFIG) + " ]; then "
            + "\"$XBS_INIT\" --json init >/dev/null; CREATED=true; fi; "
            + "test -s " + XbsConfigApi.q(XbsConfigApi.CONFIG) + "; "
            + "\"$XBS_INIT\" --json validate >/dev/null; "
            + markerTest(XbsConfigApi.LIST_DIR) + markerTest(XbsConfigApi.BACKUP_DIR)
            + "RELOAD=not-requested; if [ \"$CREATED\" = true ]; then "
            + "if \"$XBS_INIT\" --json runtime reload >/dev/null 2>&1; then RELOAD=requested; else RELOAD=deferred; fi; fi; "
            + "sync >/dev/null 2>&1 || true; "
            + "if [ \"$CREATED\" = true ]; then printf 'XBS configuration initialized, validated, and runtime reload %s' \"$RELOAD\"; "
            + "else printf 'XBS configuration and managed storage validated'; fi";
        XbsRootApi.CommandOutput output = XbsRootApi.runRoot(shell);
        if (output.exitCode == 0) {
            XbsCache.clear(context);
            RootDiagnosticsStore.clear(context);
            XbsCompatibilityStore.clear(context);
            XbsRuleIndex.invalidate(context);
            ProcessRepository.invalidate(context);
            XbsSnapshot refreshed = XbsRootApi.readSnapshot(context);
            XbsStateProcessor.process(context, refreshed, false);
        }
        return output;
    }

    /** Pins CLI path resolution to the exact canonical paths the Android UI probes. */
    static String canonicalConfigEnvironmentShell(String configDir) {
        return "export XBS_EXTERNAL_ROOT=" + XbsConfigApi.q(configDir) + "; "
            + "export XBS_EXTERNAL_CONFIG=" + XbsConfigApi.q(XbsConfigApi.CONFIG) + "; "
            + "export XBS_EXTERNAL_LIST_DIR=" + XbsConfigApi.q(XbsConfigApi.LIST_DIR) + "; "
            + "export XBS_EXTERNAL_LOG_DIR=" + XbsConfigApi.q(configDir + "/logs") + "; ";
    }

    private static String buildShell() {
        String configDir = parent(XbsConfigApi.CONFIG);
        return "clean(){ printf '%s' \"$1\" | tr '\\r\\n\\t' '   ' | cut -c1-180; }; "
            + "probe_dir(){ P=\"$1\"; if [ -L \"$P\" ] || [ ! -d \"$P\" ]; then printf false; return; fi; "
            + "M=\"$P/.xbsapp-diagnostic-$$\"; if (umask 077; : > \"$M\") 2>/dev/null; then rm -f \"$M\"; printf true; else printf false; fi; }; "
            + "SU_PATH=$(command -v su 2>/dev/null || printf unknown); MANAGER=Generic; VERSION=unknown; "
            + "if [ -x /data/adb/ksu/bin/ksud ]; then MANAGER=KernelSU; VERSION=$(/data/adb/ksu/bin/ksud -V 2>&1 | head -n1); "
            + "elif command -v ksud >/dev/null 2>&1; then MANAGER=KernelSU; VERSION=$(ksud -V 2>&1 | head -n1); "
            + "elif [ -x /data/adb/ap/bin/apd ]; then MANAGER=APatch; VERSION=$(/data/adb/ap/bin/apd -V 2>&1 | head -n1); "
            + "elif command -v apd >/dev/null 2>&1; then MANAGER=APatch; VERSION=$(apd -V 2>&1 | head -n1); "
            + "elif command -v magisk >/dev/null 2>&1; then MANAGER=Magisk; VERSION=$(magisk -V 2>&1 | head -n1); "
            + "else VERSION=$(su -v 2>&1 | head -n1); case \"$VERSION\" in *invalid*|*usage*) VERSION=unknown;; esac; fi; "
            + XbsRuntimeResolver.resolveXbs("XBS_PATH")
            + XbsRuntimeResolver.resolveXbsCtl("XBSCTL_PATH")
            + "printf 'manager=%s\\n' \"$(clean \"$MANAGER\")\"; printf 'manager_version=%s\\n' \"$(clean \"$VERSION\")\"; printf 'su_path=%s\\n' \"$(clean \"$SU_PATH\")\"; "
            + "printf 'xbs_path=%s\\n' \"$(clean \"${XBS_PATH:-not found}\")\"; [ -n \"$XBS_PATH\" ] && printf 'xbs_executable=true\\n' || printf 'xbs_executable=false\\n'; "
            + "printf 'xbsctl_path=%s\\n' \"$(clean \"${XBSCTL_PATH:-not found}\")\"; [ -n \"$XBSCTL_PATH\" ] && printf 'xbsctl_executable=true\\n' || printf 'xbsctl_executable=false\\n'; "
            + fileFlags("config", XbsConfigApi.CONFIG, configDir)
            + dirFlags("list", XbsConfigApi.LIST_DIR)
            + dirFlags("backup", XbsConfigApi.BACKUP_DIR);
    }

    private static String fileFlags(String key, String path, String parent) {
        return "[ -e " + XbsConfigApi.q(path) + " ] && printf '" + key + "_exists=true\\n' || printf '" + key + "_exists=false\\n'; "
            + "[ -r " + XbsConfigApi.q(path) + " ] && printf '" + key + "_readable=true\\n' || printf '" + key + "_readable=false\\n'; "
            + "printf '" + key + "_writable=%s\\n' \"$(probe_dir " + XbsConfigApi.q(parent) + ")\"; ";
    }

    private static String dirFlags(String key, String path) {
        return "[ -d " + XbsConfigApi.q(path) + " ] && printf '" + key + "_exists=true\\n' || printf '" + key + "_exists=false\\n'; "
            + "[ -r " + XbsConfigApi.q(path) + " ] && printf '" + key + "_readable=true\\n' || printf '" + key + "_readable=false\\n'; "
            + "printf '" + key + "_writable=%s\\n' \"$(probe_dir " + XbsConfigApi.q(path) + ")\"; ";
    }

    private static String storageRepairSafetyShell() {
        return "safe_repair_dir(){ LABEL=\"$1\"; D=\"$2\"; "
            + "if [ -L \"$D\" ]; then printf 'Refusing symlinked XBS %s directory: %s\n' \"$LABEL\" \"$D\" >&2; exit 73; fi; "
            + "mkdir -p \"$D\"; "
            + "if [ ! -d \"$D\" ] || [ -L \"$D\" ]; then printf 'Unsafe XBS %s directory after repair: %s\n' \"$LABEL\" \"$D\" >&2; exit 73; fi; }; ";
    }

    private static String ensureDir(String label, String path) {
        return "safe_repair_dir " + XbsConfigApi.q(label) + " " + XbsConfigApi.q(path) + "; ";
    }

    private static String markerTest(String path) {
        return "if [ -L " + XbsConfigApi.q(path) + " ]; then printf 'Refusing symlinked marker directory: %s\n' "
            + XbsConfigApi.q(path) + " >&2; exit 73; fi; "
            + "M=" + XbsConfigApi.q(path + "/.xbsapp-repair-$$") + "; : > \"$M\"; rm -f \"$M\"; ";
    }

    private static Map<String, String> parse(String output) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : output.replace("\r", "").split("\n")) {
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            values.put(line.substring(0, equals).trim(), clean(line.substring(equals + 1)));
        }
        return values;
    }

    private static boolean bool(Map<String, String> values, String key) { return "true".equals(values.get(key)); }
    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
    private static boolean permissionGranted(Context context, String permission) {
        return context.getPackageManager().checkPermission(permission, context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }
    private static String parent(String path) { int slash = path.lastIndexOf('/'); return slash <= 0 ? "/" : path.substring(0, slash); }
    private static String clean(String value) {
        String cleaned = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim().replaceAll(" +", " ");
        return cleaned.length() <= 240 ? cleaned : cleaned.substring(0, 239) + "…";
    }
}
