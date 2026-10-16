package io.github.mikeceead.xbscompanion;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Detail host for the eight non-overlapping More and Settings destinations. */
public final class MoreSettingsActivity extends XbsActivity {
    static final String EXTRA_DESTINATION = "destination";
    private static final int REQUEST_NOTIFICATIONS = 44;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private UiKit ui;
    private SettingsDestination destination;

    static void open(Context context, String destinationId) {
        context.startActivity(new Intent(context, MoreSettingsActivity.class)
            .putExtra(EXTRA_DESTINATION, destinationId));
    }

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        destination = SettingsCatalog.find(getIntent().getStringExtra(EXTRA_DESTINATION));
        if (destination == null) destination = SettingsCatalog.all().get(0);
        registerXbsBackHandler(this::finish);
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        if (ui != null) rebuild();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) rebuild();
    }

    private void rebuild() {
        ui = new UiKit(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);
        setXbsContentView(root());
    }

    private View root() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(18));
        root.setBackgroundColor(ui.palette.background);

        LinearLayout header = ui.surface();
        header.addView(ui.text("MORE AND SETTINGS", 12, ui.palette.orange, true));
        header.addView(ui.text(destination.title, 27, ui.palette.text, true));
        header.addView(ui.text(SettingsHubView.summary(this, destination.id), 13, ui.palette.cyan, true));
        header.addView(ui.text(destination.description, 13, ui.palette.muted, false));
        header.addView(ui.secondaryButton("Back to searchable settings", view -> finish()));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        renderDestination(content);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void renderDestination(LinearLayout content) {
        switch (destination.id) {
            case SettingsDestination.NOTIFICATIONS: renderNotifications(content); break;
            case SettingsDestination.APPEARANCE: renderAppearance(content); break;
            case SettingsDestination.TASKER: renderTasker(content); break;
            case SettingsDestination.DATA: renderData(content); break;
            case SettingsDestination.CONFIGURATION: renderConfiguration(content); break;
            case SettingsDestination.DIAGNOSTICS: renderDiagnostics(content); break;
            case SettingsDestination.ABOUT: renderAbout(content); break;
            default: renderRuntime(content); break;
        }
    }

    private void renderRuntime(LinearLayout content) {
        content.addView(ui.sectionTitle("Persistent monitoring"));
        LinearLayout monitor = ui.surface();
        addToggle(monitor, "Persistent XBS event monitor",
            AppSettings.monitorEnabled(this) ? "Running preference is enabled. Runtime service state is refreshed immediately." : "Stopped. The app will not continuously poll the XBS event journal.",
            AppSettings.monitorEnabled(this), on -> {
                AppSettings.setMonitorEnabled(this, on);
                if (on) {
                    requestNotificationPermission();
                    EventMonitorService.start(this);
                } else {
                    EventMonitorService.stop(this);
                }
                rebuild();
            });
        addToggle(monitor, "Restart monitor after boot",
            AppSettings.startOnBoot(this) ? "Boot receiver will restart monitoring when monitoring is enabled." : "Boot completion will not restart monitoring.",
            AppSettings.startOnBoot(this), on -> { AppSettings.setStartOnBoot(this, on); rebuild(); });
        addToggle(monitor, "Detailed multi-event notification",
            AppSettings.detailedStatusNotification(this) ? "Persistent status includes simultaneous active-event detail." : "Persistent status uses the compact runtime summary.",
            AppSettings.detailedStatusNotification(this), on -> {
                AppSettings.setDetailedStatusNotification(this, on);
                EventMonitorService.refresh(this);
                rebuild();
            });
        monitor.addView(selectionButton("Polling interval", AppSettings.pollSeconds(this) + " seconds", view -> showPollDialog()));
        EventBridgeReport bridge = EventBridgeStore.cached(this);
        monitor.addView(ui.text("Event transport · " + bridge.headline(), 12,
            bridge.securityFailure ? ui.palette.red : bridge.authenticated() ? ui.palette.green : ui.palette.muted, false));
        content.addView(monitor);

        content.addView(ui.sectionTitle("Runtime tools"));
        LinearLayout tools = ui.surface();
        tools.addView(ui.text("Sampling and runtime controls", 17, ui.palette.orange, true));
        tools.addView(ui.text("Process sampling remains user-triggered here. Typed runtime mutations live in Quick Actions and Command Center under Configuration tools.", 12, ui.palette.muted, false));
        tools.addView(ui.button("Open live process monitor", ui.palette.orange,
            view -> startActivity(new Intent(this, ProcessMonitorActivity.class))));
        content.addView(tools);
    }

    private void renderNotifications(LinearLayout content) {
        NotificationController.Readiness readiness = NotificationController.readiness(this);
        content.addView(ui.sectionTitle("Permission status"));
        LinearLayout status = ui.surface();
        RuntimeNotificationReport runtimeReport = readiness.runtimeReport;
        status.addView(ui.text("Runtime Notification 2.0 · " + runtimeReport.summary(), 13, runtimeReport.launchBlocked() ? ui.palette.red : ui.palette.muted, false));
        status.addView(ui.text(readiness.permissionGranted && readiness.appEnabled ? "Notifications ready" : "Notifications need attention",
            18, readiness.permissionGranted && readiness.appEnabled ? ui.palette.green : ui.palette.yellow, true));
        status.addView(ui.text("Permission: " + (readiness.permissionGranted ? "granted" : "not granted")
            + " · app setting: " + (readiness.appEnabled ? "enabled" : "disabled"), 13, ui.palette.muted, false));
        if (Build.VERSION.SDK_INT >= 33 && !readiness.permissionGranted) {
            status.addView(ui.button("Request notification permission", ui.palette.orange, view -> requestNotificationPermission()));
        }
        status.addView(ui.secondaryButton("Open app notification settings", view -> openNotificationSettings()));
        content.addView(status);

        content.addView(ui.sectionTitle("Alert behavior"));
        LinearLayout behavior = ui.surface();
        behavior.addView(selectionButton("Command completion alerts", AppSettings.commandResultNotificationModeLabel(this), view -> showResultModeDialog()));
        behavior.addView(selectionButton("Event feedback", AppSettings.toastModeLabel(this), view -> showToastModeDialog()));
        behavior.addView(ui.text("Command progress always uses one consolidated foreground notification while work is active. Completion alerts follow the selected policy.", 12, ui.palette.muted, false));
        content.addView(behavior);

        content.addView(ui.sectionTitle("Notification channels"));
        LinearLayout channels = ui.surface();
        channels.addView(ui.text("Direct channel controls", 17, ui.palette.orange, true));
        channels.addView(ui.text("Channel importance, sound, vibration, and visibility are owned by Android system settings.", 12, ui.palette.muted, false));
        channels.addView(ui.secondaryButton("Active command progress", view -> openNotificationChannel(NotificationController.CHANNEL_COMMAND_PROGRESS)));
        channels.addView(ui.secondaryButton("Command results", view -> openNotificationChannel(NotificationController.CHANNEL_COMMAND_RESULTS)));
        channels.addView(ui.secondaryButton("Runtime status", view -> openNotificationChannel(NotificationController.CHANNEL_STATUS)));
        channels.addView(ui.secondaryButton("Important alerts", view -> openNotificationChannel(NotificationController.CHANNEL_ALERTS)));
        content.addView(channels);
    }

    private void renderAppearance(LinearLayout content) {
        content.addView(ui.sectionTitle("Color behavior"));
        LinearLayout appearance = ui.surface();
        boolean dynamic = AppSettings.useMonet(this);
        addToggle(appearance, "Use Android dynamic colors",
            dynamic && Build.VERSION.SDK_INT >= 31 ? "The palette is derived from Android system colors." : dynamic
                ? "Dynamic colors are requested, but this Android version uses the built-in XBS fallback palette." : "The stable built-in XBS palette is active.",
            dynamic, on -> { AppSettings.setUseMonet(this, on); rebuild(); });
        content.addView(appearance);

        content.addView(ui.sectionTitle("Palette preview"));
        LinearLayout preview = ui.surface();
        preview.addView(ui.text("Primary status", 17, ui.palette.green, true));
        preview.addView(ui.text("Informational accent", 15, ui.palette.cyan, true));
        preview.addView(ui.text("Attention state", 15, ui.palette.yellow, true));
        preview.addView(ui.text("Destructive or blocked state", 15, ui.palette.red, true));
        preview.addView(ui.text("Action accent", 15, ui.palette.orange, true));
        preview.addView(ui.text("Changes are applied immediately across newly rebuilt screens.", 12, ui.palette.muted, false));
        content.addView(preview);
    }

    private void renderTasker(LinearLayout content) {
        content.addView(ui.sectionTitle("Tasker event bridge"));
        LinearLayout tasker = ui.surface();
        addToggle(tasker, "Emit Tasker events and state re-queries",
            AppSettings.taskerEventsEnabled(this) ? "XBS runtime transitions update Tasker event and condition surfaces." : "The app keeps local state, but does not emit Tasker transition updates.",
            AppSettings.taskerEventsEnabled(this), on -> { AppSettings.setTaskerEventsEnabled(this, on); rebuild(); });
        tasker.addView(ui.text(isTaskerInstalled() ? "Tasker package detected" : "Tasker package not detected",
            14, isTaskerInstalled() ? ui.palette.green : ui.palette.yellow, true));
        tasker.addView(ui.text("Tasker configurations are HMAC-signed, certificate-pinned, bounded, and must be re-saved after this security upgrade.", 12, ui.palette.muted, false));
        tasker.addView(ui.text(ExternalEntryStore.cached(this).summary(), 12, ui.palette.muted, false));
        tasker.addView(ui.text("State variables: %xbs_active_profiles and %xbs_active_count. Multiple simultaneous events remain represented individually.", 12, ui.palette.muted, false));
        content.addView(tasker);

        content.addView(ui.sectionTitle("Integration notes"));
        LinearLayout notes = ui.surface();
        notes.addView(ui.text("Actions, events, and states", 17, ui.palette.orange, true));
        notes.addView(ui.text("Actions, events, and states now use literal HMAC-signed configurations, current Tasker certificate pinning, bounded asynchronous receivers, and explicit completion delivery. Re-save older Tasker configurations after this upgrade.", 12, ui.palette.muted, false));
        content.addView(notes);
    }

    private void renderData(LinearLayout content) {
        XbsHistoryStatus history = XbsHistoryStore.status(this);
        content.addView(ui.sectionTitle("Room history"));
        LinearLayout historyCard = ui.surface();
        historyCard.addView(ui.text(history.readyForRelease() ? "Local history ready" : "Local history needs attention",
            18, history.readyForRelease() ? ui.palette.green : ui.palette.red, true));
        historyCard.addView(ui.text("Runs: " + history.runCount + " · transactions: " + history.transactionCount
            + " · migrated: " + history.importedLegacyRuns + " · skipped: " + history.skippedLegacyRuns, 12, ui.palette.muted, false));
        historyCard.addView(selectionButton("Run retention", AppSettings.historyRetention(this) + " records", view -> showRetentionDialog()));
        historyCard.addView(ui.secondaryButton("Open Activity Workbench", view -> startActivity(new Intent(this, ActivityWorkbenchActivity.class))));
        historyCard.addView(ui.secondaryButton("Clear finished, unpinned runs", view -> confirmClearFinished()));
        historyCard.addView(ui.secondaryButton("Clear all local activity history", view -> confirmClearAllHistory()));
        content.addView(historyCard);

        content.addView(ui.sectionTitle("Frontend caches"));
        LinearLayout caches = ui.surface();
        caches.addView(ui.text("Bounded metadata and state caches", 17, ui.palette.orange, true));
        caches.addView(ui.text("Installed apps: 30 minutes · configuration: 15 seconds · list/rule index: 30 seconds · process sample: 1.5 seconds. Manual refresh bypasses the relevant cache.", 12, ui.palette.muted, false));
        caches.addView(ui.secondaryButton("Refresh installed-app metadata", view -> refreshAppCache()));
        caches.addView(ui.secondaryButton("Clear all frontend caches", view -> {
            XbsCache.clear(this);
            Toast.makeText(this, "Frontend caches cleared", Toast.LENGTH_SHORT).show();
            rebuild();
        }));
        content.addView(caches);
    }

    private void renderConfiguration(LinearLayout content) {
        content.addView(ui.sectionTitle("Native editors"));
        LinearLayout editors = ui.surface();
        editors.addView(ui.text("Transactional configuration surfaces", 17, ui.palette.orange, true));
        editors.addView(ui.text("Configuration and list mutations use the Phase 14 journal and Phase 15 validation flow.", 12, ui.palette.muted, false));
        editors.addView(ui.button("Configuration editor", ui.palette.orange, view -> startActivity(new Intent(this, XbsConfigActivity.class))));
        editors.addView(ui.secondaryButton("Default essentials editor", view -> startActivity(new Intent(this, XbsEssentialsActivity.class))));
        editors.addView(ui.secondaryButton("Event manager", view -> startActivity(new Intent(this, XbsConfigActivity.class).putExtra("events_only", true))));
        content.addView(editors);

        content.addView(ui.sectionTitle("Typed command tools"));
        LinearLayout commands = ui.surface();
        commands.addView(ui.text("Allowlisted actions only", 17, ui.palette.orange, true));
        commands.addView(ui.text("Quick Actions and Command Center generate immutable previews and never expose an arbitrary shell editor.", 12, ui.palette.muted, false));
        commands.addView(ui.button("Open Quick Actions", ui.palette.orange, view -> QuickActionsSheet.show(this)));
        commands.addView(ui.secondaryButton("Open Command Center", view -> startActivity(new Intent(this, CliCommandActivity.class))));
        content.addView(commands);
    }

    private void renderDiagnostics(LinearLayout content) {
        DebugHealthReport debugHealth = DebugHealthStore.cached(this);
        RootStatus root = XbsRootApi.cachedRootStatus(this);
        ReleaseReadinessReport readiness = ReleaseReadinessEvaluator.evaluate(this);
        FinalReleaseSealReport finalSeal = FinalReleaseSealStore.evaluate(this);
        XbsCapabilityRegistry capabilities = XbsCapabilityRegistry.cached(this);
        XbsCompatibilityReport compatibility = capabilities.report;
        content.addView(ui.sectionTitle("Health workbenches"));
        LinearLayout health = ui.surface();
        int healthColor = !debugHealth.evaluated() || debugHealth.unknownCount() > 0 ? ui.palette.cyan
            : debugHealth.blockerCount() > 0 ? ui.palette.red
            : debugHealth.warningCount() > 0 ? ui.palette.yellow : ui.palette.green;
        health.addView(ui.text(debugHealth.headline(), 18, healthColor, true));
        health.addView(ui.text(debugHealth.compactSummary(), 12, ui.palette.muted, false));
        health.addView(ui.text(root.summary(), 15, root.granted ? ui.palette.green : ui.palette.yellow, true));
        health.addView(ui.text(readiness.headline(), 15,
            readiness.blockerCount() > 0 ? ui.palette.red : readiness.warningCount() > 0 ? ui.palette.yellow : ui.palette.green, true));
        health.addView(ui.text(readiness.compactSummary(), 12, ui.palette.muted, false));
        health.addView(ui.text(compatibility.headline() + " · " + compatibility.compactSummary(), 12,
            compatibility.compatible() ? ui.palette.green : ui.palette.yellow, false));
        SecuritySealReport security = SecuritySealStore.cached(this);
        PerformanceReport performance = PerformanceStore.cached(this);
        health.addView(ui.text(security.headline(), 15, !security.evaluated()?ui.palette.cyan:security.blockerCount()>0?ui.palette.red:ui.palette.green, true));
        health.addView(ui.text(performance.summary(), 12, ui.palette.cyan, false));
        health.addView(ui.button("Open Debug Workbench", ui.palette.orange, view -> startActivity(new Intent(this, DebugWorkbenchActivity.class))));
        health.addView(ui.secondaryButton("Open Security Seal", view -> startActivity(new Intent(this, SecuritySealActivity.class))));
        health.addView(ui.secondaryButton("Open Root Diagnostics", view -> startActivity(new Intent(this, RootDiagnosticsActivity.class))));
        health.addView(ui.secondaryButton("Open Release Readiness", view -> startActivity(new Intent(this, ReleaseReadinessActivity.class))));
        health.addView(ui.secondaryButton("Open Final Release Seal", view -> startActivity(new Intent(this, FinalReleaseSealActivity.class))));
        health.addView(ui.text(finalSeal.headline(), 12, finalSeal.ready() ? ui.palette.green : ui.palette.red, false));
        content.addView(health);

        content.addView(ui.sectionTitle("Support evidence"));
        LinearLayout support = ui.surface();
        support.addView(ui.text("Bounded support bundle v" + SupportBundleBuilder.FORMAT_VERSION, 17, ui.palette.orange, true));
        support.addView(ui.text("The bundle includes bounded health and runtime evidence, then applies deterministic secret, path, package, address, and identifier redaction before copy or share.", 12, ui.palette.muted, false));
        support.addView(ui.secondaryButton("Copy redacted support bundle", view -> copySupportBundle()));
        support.addView(ui.secondaryButton("Share redacted support bundle", view -> shareSupportBundle()));
        content.addView(support);
    }

    private void renderAbout(LinearLayout content) {
        content.addView(ui.sectionTitle("Application"));
        LinearLayout app = ui.surface();
        app.addView(ui.text("XBS Companion " + BuildConfig.VERSION_NAME, 21, ui.palette.orange, true));
        app.addView(ui.text("versionCode " + BuildConfig.VERSION_CODE + " · target SDK " + getApplicationInfo().targetSdkVersion
            + " · minimum SDK 26", 13, ui.palette.muted, false));
        app.addView(ui.text("Phase 31 final release seal: native 4.2.0 readiness and support bundle v18", 14, ui.palette.cyan, true));
        content.addView(app);

        content.addView(ui.sectionTitle("Execution model"));
        LinearLayout model = ui.surface();
        model.addView(ui.text("Root-native Android frontend", 17, ui.palette.green, true));
        model.addView(ui.text("XBS actions execute through the bounded root command service. The app does not depend on Termux and does not expose arbitrary shell text.", 12, ui.palette.muted, false));
        model.addView(ui.text("Current workstream: Phase 26 authenticated event bridge with adaptive fallback.", 12, ui.palette.muted, false));
        content.addView(model);
    }

    private void addToggle(LinearLayout card, String label, String summary, boolean checked, ToggleChange change) {
        SwitchCompat toggle = ui.toggle(label, checked);
        toggle.setOnCheckedChangeListener((button, on) -> change.onChanged(on));
        card.addView(toggle);
        card.addView(ui.text(summary, 12, ui.palette.muted, false));
    }

    private LinearLayout selectionButton(String label, String value, View.OnClickListener listener) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(ui.text(label, 15, ui.palette.text, true));
        block.addView(ui.text(value, 12, ui.palette.cyan, true));
        block.addView(ui.secondaryButton("Change " + label.toLowerCase(java.util.Locale.US), listener));
        return block;
    }

    private void showPollDialog() {
        int[] values = {2, 3, 5, 10, 15, 30};
        String[] labels = {"2 seconds", "3 seconds", "5 seconds", "10 seconds", "15 seconds", "30 seconds"};
        int selected = indexOf(values, AppSettings.pollSeconds(this));
        new AlertDialog.Builder(this).setTitle("Polling interval")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                AppSettings.setPollSeconds(this, values[which]);
                dialog.dismiss();
                if (AppSettings.monitorEnabled(this)) EventMonitorService.refresh(this);
                rebuild();
            }).setNegativeButton("Cancel", null).show();
    }

    private void showResultModeDialog() {
        String[] labels = {"Off", "Failures, timeouts and cancellations", "All completions"};
        int selected = AppSettings.commandResultNotificationMode(this);
        new AlertDialog.Builder(this).setTitle("Command completion alerts")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                AppSettings.setCommandResultNotificationMode(this, which);
                dialog.dismiss();
                rebuild();
            }).setNegativeButton("Cancel", null).show();
    }

    private void showToastModeDialog() {
        String[] labels = {"Off", "Event start and stop", "Safe mode and failures", "All changes"};
        int selected = AppSettings.toastMode(this);
        new AlertDialog.Builder(this).setTitle("Event feedback")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                AppSettings.setToastMode(this, which);
                dialog.dismiss();
                rebuild();
            }).setNegativeButton("Cancel", null).show();
    }

    private void showRetentionDialog() {
        int[] values = {30, 60, 120, 240};
        String[] labels = {"30 records", "60 records", "120 records", "240 records"};
        int selected = indexOf(values, AppSettings.historyRetention(this));
        new AlertDialog.Builder(this).setTitle("Run history retention")
            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                AppSettings.setHistoryRetention(this, values[which]);
                RunStore.applyRetention(this);
                dialog.dismiss();
                rebuild();
            }).setNegativeButton("Cancel", null).show();
    }

    private void confirmClearFinished() {
        new AlertDialog.Builder(this).setTitle("Clear finished runs?")
            .setMessage("Active and pinned records stay protected. Finished, unpinned records are removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear", (dialog, which) -> { RunStore.clearFinished(this); rebuild(); })
            .show();
    }

    private void confirmClearAllHistory() {
        new AlertDialog.Builder(this).setTitle("Clear all local activity history?")
            .setMessage("Command history and runtime events are removed from the app. Active root commands are not cancelled.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear", (dialog, which) -> {
                RunStore.clear(this);
                XbsStateStore.clearEvents(this);
                rebuild();
            }).show();
    }

    private void refreshAppCache() {
        executor.execute(() -> {
            AppCatalog.invalidate(this);
            int count = AppCatalog.load(this, true).size();
            runOnUiThread(() -> Toast.makeText(this, "Cached " + count + " installed apps", Toast.LENGTH_SHORT).show());
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void openNotificationSettings() {
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private void openNotificationChannel(String channelId) {
        startActivity(NotificationController.channelSettingsIntent(this, channelId));
    }

    private void copySupportBundle() {
        String bundle = SupportBundleBuilder.build(this);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("XBS support bundle", bundle));
        Toast.makeText(this, "Redacted support bundle copied", Toast.LENGTH_SHORT).show();
    }

    private void shareSupportBundle() {
        String bundle = SupportBundleBuilder.build(this);
        Intent send = IntentSecurityPolicy.textShare("XBS Companion support bundle", bundle);
        startActivity(Intent.createChooser(send, "Share XBS support bundle"));
    }

    private boolean isTaskerInstalled() {
        return packageInstalled("net.dinglisch.android.taskerm") || packageInstalled("net.dinglisch.android.tasker");
    }

    private boolean packageInstalled(String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                getPackageManager().getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
            } else {
                legacyPackageInfo(packageName);
            }
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void legacyPackageInfo(String packageName) throws PackageManager.NameNotFoundException {
        getPackageManager().getPackageInfo(packageName, 0);
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return 0;
    }

    private interface ToggleChange { void onChanged(boolean value); }
}
