package io.github.mikeceead.xbscompanion;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated root, module, storage, notification, and service diagnostics workbench. */
public final class RootDiagnosticsActivity extends XbsActivity {
    private static final int REQUEST_NOTIFICATIONS = 71;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private RootDiagnosticsReport report;
    private UiKit ui;
    private boolean probing;

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        report = RootDiagnosticsStore.cached(this);
        render();
        if (!report.isFresh(System.currentTimeMillis())) refresh(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (report != null && report.capturedAt > 0 && !probing) refresh(false);
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) refresh(false);
    }

    private void render() {
        ui = new UiKit(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28));
        root.setBackgroundColor(ui.palette.background);
        root.addView(ui.text("ROOT DIAGNOSTICS", 12, ui.palette.orange, true));
        root.addView(ui.text("Runtime readiness", 28, ui.palette.text, true));
        root.addView(ui.text("Root provider, XBS binaries, storage paths, notifications and foreground services.", 13, ui.palette.muted, false));
        root.addView(ui.sectionTitle("Summary"));
        root.addView(summaryCard());
        root.addView(ui.sectionTitle("Root provider"));
        root.addView(rootCard());
        root.addView(ui.sectionTitle("XBS binaries"));
        root.addView(binaryCard());
        root.addView(ui.sectionTitle("Schema compatibility"));
        root.addView(compatibilityCard());
        root.addView(ui.sectionTitle("Configuration storage"));
        root.addView(storageCard());
        root.addView(ui.sectionTitle("Notifications"));
        root.addView(notificationCard());
        root.addView(ui.sectionTitle("Foreground services"));
        root.addView(serviceCard());
        root.addView(ui.sectionTitle("Guided fixes"));
        root.addView(fixesCard());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setXbsContentView(scroll);
    }

    private LinearLayout summaryCard() {
        LinearLayout card = ui.surface();
        int color = report.failureCount() > 0 ? ui.palette.red : report.warningCount() > 0 ? ui.palette.yellow : ui.palette.green;
        card.addView(ui.text(probing ? "Running diagnostics…" : report.headline(), 20, color, true));
        card.addView(ui.text(report.compactSummary(), 12, ui.palette.muted, false));
        if (report.capturedAt > 0) card.addView(ui.text("Checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(report.capturedAt)), 12, ui.palette.muted, false));
        card.addView(ui.button(probing ? "Diagnostics running" : "Run full diagnostics", ui.palette.orange, v -> refresh(true)));
        card.addView(ui.secondaryButton("Copy diagnostics summary", v -> copyDiagnostics()));
        return card;
    }

    private LinearLayout rootCard() {
        LinearLayout card = ui.surface();
        card.addView(line("Root access", yesNo(report.rootGranted), report.rootGranted));
        card.addView(line("Provider", value(report.manager), report.rootGranted));
        card.addView(line("Provider version", value(report.managerVersion), report.rootGranted));
        card.addView(line("su path", value(report.suPath), report.rootGranted));
        if (!report.error.isEmpty()) card.addView(ui.text(report.error, 12, ui.palette.red, false));
        return card;
    }

    private LinearLayout binaryCard() {
        LinearLayout card = ui.surface();
        card.addView(line("xbs", value(report.xbsPath), report.xbsExecutable));
        card.addView(line("XBSctl", value(report.xbsctlPath), report.xbsctlExecutable));
        card.addView(ui.text("Both executables must be available from /system/bin or the XtremeBS module directory.", 12, ui.palette.muted, false));
        return card;
    }


    private LinearLayout compatibilityCard() {
        XbsCapabilityRegistry capabilities = XbsCapabilityRegistry.cached(this);
        XbsCompatibilityReport compatibility = capabilities.report;
        LinearLayout card = ui.surface();
        int color = compatibility.compatible() ? ui.palette.green
            : compatibility.isLive() ? ui.palette.red : ui.palette.yellow;
        card.addView(ui.text(compatibility.headline(), 17, color, true));
        card.addView(ui.text(compatibility.compactSummary(), 12, ui.palette.muted, false));
        card.addView(line("Module", compatibility.moduleId + " " + compatibility.moduleVersion, compatibility.isLive()));
        card.addView(line("CLI schema", value(compatibility.commandSchemaVersion), compatibility.isLive()));
        card.addView(line("Config version", Integer.toString(compatibility.configVersion), compatibility.configSupported()));
        card.addView(line("Advertised commands", Integer.toString(compatibility.commandIds.size()), compatibility.isLive()));
        card.addView(line("Advertised event fields", Integer.toString(compatibility.eventFields.size()), compatibility.isLive()));
        if (!compatibility.error.isEmpty()) card.addView(ui.text(compatibility.error, 12, ui.palette.yellow, false));
        return card;
    }

    private LinearLayout storageCard() {
        LinearLayout card = ui.surface();
        card.addView(pathLine("Config", XbsConfigApi.CONFIG, report.configExists, report.configReadable, report.configWritable));
        card.addView(pathLine("Lists", XbsConfigApi.LIST_DIR, report.listExists, report.listReadable, report.listWritable));
        card.addView(pathLine("Backups", XbsConfigApi.BACKUP_DIR, report.backupExists, report.backupReadable, report.backupWritable));
        card.addView(ui.text("Writability is verified with short-lived marker files. Existing configuration content is never modified by the probe.", 12, ui.palette.muted, false));
        return card;
    }

    private LinearLayout notificationCard() {
        LinearLayout card = ui.surface();
        NotificationController.Readiness readiness = NotificationController.readiness(this);
        card.addView(line("Runtime permission", yesNo(readiness.permissionGranted), readiness.permissionGranted));
        card.addView(line("App notifications", readiness.appEnabled ? "Enabled" : "Blocked", readiness.appEnabled));
        card.addView(line("Active command channel", ready(readiness.commandProgressChannelReady), readiness.commandProgressChannelReady));
        card.addView(line("Command result channel", ready(readiness.commandResultsChannelReady), readiness.commandResultsChannelReady));
        card.addView(line("Runtime status channel", ready(readiness.runtimeChannelReady), readiness.runtimeChannelReady));
        card.addView(line("Important alerts channel", ready(readiness.alertsChannelReady), readiness.alertsChannelReady));
        card.addView(ui.text("Command completion policy: " + AppSettings.commandResultNotificationModeLabel(this), 12, ui.palette.muted, false));
        return card;
    }

    private LinearLayout serviceCard() {
        LinearLayout card = ui.surface();
        card.addView(line("Foreground service permission", ready(report.foregroundServicePermission), report.foregroundServicePermission));
        card.addView(line("Special-use declaration", ready(report.specialUsePermission), report.specialUsePermission));
        card.addView(line("Command service", report.commandServiceActive ? "Running" : report.activeCommandCount > 0 ? "Not running with active records" : "Idle", report.activeCommandCount == 0 || report.commandServiceActive));
        card.addView(line("Active command records", Integer.toString(report.activeCommandCount), report.activeCommandCount == 0 || report.commandServiceActive));
        card.addView(line("Event monitor", report.monitorServiceActive ? "Running" : report.monitorConfigured ? "Configured but not running" : "Disabled", !report.monitorConfigured || report.monitorServiceActive));
        EventBridgeReport bridge = EventBridgeStore.cached(this);
        card.addView(line("Authenticated event bridge", bridge.headline(), bridge.authenticated() && !bridge.securityFailure));
        return card;
    }

    private LinearLayout fixesCard() {
        LinearLayout card = ui.surface();
        if (!report.rootGranted) card.addView(ui.button("Check or request root", ui.palette.orange, v -> refresh(true)));
        if (report.rootGranted && !report.binariesReady()) card.addView(ui.button("Show XBS module guidance", ui.palette.orange, v -> showModuleGuidance()));
        XbsCapabilityRegistry capabilities = XbsCapabilityRegistry.cached(this);
        if (!capabilities.negotiated() || !capabilities.contractAllowsExecution()) card.addView(ui.button("Refresh schema compatibility", ui.palette.orange, v -> refresh(true)));
        if (report.rootGranted && !report.storageReady()) card.addView(ui.button("Repair XBS storage paths", ui.palette.orange, v -> confirmRepairPaths()));
        if (!report.notificationPermissionGranted && Build.VERSION.SDK_INT >= 33) card.addView(ui.button("Request notification permission", ui.palette.orange, v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS)));
        NotificationController.Readiness readiness = NotificationController.readiness(this);
        if (!report.notificationsReady()) card.addView(ui.secondaryButton("Open notification settings", v -> openNotificationSettings()));
        if (!readiness.commandProgressChannelReady) card.addView(ui.secondaryButton("Fix active command channel", v -> openNotificationChannel(NotificationController.CHANNEL_COMMAND_PROGRESS)));
        if (!readiness.commandResultsChannelReady) card.addView(ui.secondaryButton("Fix command result channel", v -> openNotificationChannel(NotificationController.CHANNEL_COMMAND_RESULTS)));
        if (!readiness.runtimeChannelReady) card.addView(ui.secondaryButton("Fix runtime status channel", v -> openNotificationChannel(NotificationController.CHANNEL_STATUS)));
        if (!readiness.alertsChannelReady) card.addView(ui.secondaryButton("Fix important alerts channel", v -> openNotificationChannel(NotificationController.CHANNEL_ALERTS)));
        if (report.failureCount() == 0 && report.warningCount() == 0) card.addView(ui.text("No guided fixes are currently needed.", 14, ui.palette.green, true));
        card.addView(ui.secondaryButton("Open Debug Workbench", v -> startActivity(new Intent(this, DebugWorkbenchActivity.class))));
        card.addView(ui.secondaryButton("Open release readiness", v -> startActivity(new Intent(this, ReleaseReadinessActivity.class))));
        card.addView(ui.secondaryButton("Copy redacted support bundle", v -> copySupportBundle()));
        return card;
    }

    private TextView line(String label, String value, boolean ok) {
        TextView text = ui.text(label + "  ·  " + value, 14, ok ? ui.palette.green : ui.palette.red, false);
        text.setPadding(0, ui.dp(5), 0, ui.dp(5));
        return text;
    }

    private TextView pathLine(String label, String path, boolean exists, boolean readable, boolean writable) {
        boolean ok = exists && readable && writable;
        return line(label, path + " · exists=" + exists + " read=" + readable + " write=" + writable, ok);
    }

    private void refresh(boolean forceRoot) {
        if (probing) return;
        probing = true;
        render();
        executor.execute(() -> {
            RootDiagnosticsReport updated = RootDiagnosticsProbe.collect(this, forceRoot);
            runOnUiThread(() -> { report = updated; probing = false; render(); });
        });
    }

    private void confirmRepairPaths() {
        new AlertDialog.Builder(this)
            .setTitle("Repair XBS storage paths?")
            .setMessage("Create the canonical config, list and backup directories. If the config is missing, XBS init will create it. Existing files are preserved.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Repair", (dialog, which) -> repairPaths())
            .show();
    }

    private void repairPaths() {
        Toast.makeText(this, "Repairing XBS paths…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            XbsRootApi.CommandOutput output = RootDiagnosticsProbe.repairStoragePaths(this);
            runOnUiThread(() -> {
                String message = output.exitCode == 0 ? output.stdout : output.error.isEmpty() ? output.stdout : output.error;
                Toast.makeText(this, message.isEmpty() ? "Storage repair failed" : message, Toast.LENGTH_LONG).show();
                refresh(false);
            });
        });
    }

    private void showModuleGuidance() {
        new AlertDialog.Builder(this)
            .setTitle("XBS module not ready")
            .setMessage("Open your root manager, confirm that the XtremeBS module is installed and enabled, then reboot if the module was newly installed or updated. Run diagnostics again after boot.")
            .setNegativeButton("Close", null)
            .setPositiveButton("Open app settings", (dialog, which) -> startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS)))
            .show();
    }

    private void openNotificationSettings() {
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private void openNotificationChannel(String channelId) {
        startActivity(NotificationController.channelSettingsIntent(this, channelId));
    }

    private void copyDiagnostics() {
        copy("XBS root diagnostics", report.supportText());
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show();
    }

    private void copySupportBundle() {
        copy("XBS support bundle", SupportBundleBuilder.build(this));
        Toast.makeText(this, "Redacted support bundle copied", Toast.LENGTH_SHORT).show();
    }

    private void copy(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private static String value(String value) { return value == null || value.trim().isEmpty() ? "Unknown" : value; }
    private static String yesNo(boolean value) { return value ? "Granted" : "Not granted"; }
    private static String ready(boolean value) { return value ? "Ready" : "Not ready"; }
}
