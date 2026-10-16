package io.github.mikeceead.xbscompanion;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit confirmation surface for allowlisted guided repairs. */
public final class GuidedFixActivity extends XbsActivity {
    static final String EXTRA_CHECK_ID = "check_id";
    private static final int REQUEST_NOTIFICATIONS = 72;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private UiKit ui;
    private DebugHealthReport.Item item;
    private GuidedFixPolicy.Spec spec;
    private GuidedFixResult result;
    private boolean running;

    static void open(Context context, String checkId) {
        context.startActivity(new Intent(context, GuidedFixActivity.class).putExtra(EXTRA_CHECK_ID, checkId));
    }

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        registerXbsBackHandler(this::finish);
        item = find(DebugHealthStore.cached(this), IntentSecurityPolicy.internalId(getIntent(), EXTRA_CHECK_ID, 96));
        spec = GuidedFixPolicy.forItem(item);
        result = GuidedFixStore.cached(this);
        rebuild();
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS) runConfirmed();
    }

    private void rebuild() {
        ui = new UiKit(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28));
        content.setBackgroundColor(ui.palette.background);
        content.addView(ui.text("GUIDED FIX", 12, ui.palette.orange, true));
        content.addView(ui.text(spec.title, 26, ui.palette.text, true));
        content.addView(ui.text(spec.explanation, 13, ui.palette.muted, false));

        content.addView(ui.sectionTitle("Current evidence"));
        LinearLayout evidence = ui.surface();
        if (item == null) {
            evidence.addView(ui.text("The requested health check is no longer present.", 15, ui.palette.yellow, true));
        } else {
            evidence.addView(ui.text(item.title, 17, severityColor(item), true));
            evidence.addView(ui.text(item.severityLabel().toUpperCase(java.util.Locale.US)
                + " · evidence " + item.evidenceLabel(), 11, severityColor(item), true));
            evidence.addView(ui.text(item.detail, 12, ui.palette.muted, false));
        }
        evidence.addView(ui.text("Risk: " + spec.riskLabel(), 12,
            spec.risk == GuidedFixPolicy.RISK_ROOT_MUTATION ? ui.palette.red
                : spec.rootBacked() ? ui.palette.yellow : ui.palette.cyan, true));
        content.addView(evidence);

        content.addView(ui.sectionTitle("Action"));
        LinearLayout action = ui.surface();
        action.addView(ui.text("Nothing runs automatically", 16, ui.palette.green, true));
        action.addView(ui.text("The repair starts only after this screen and any required confirmation. Debug Health itself remains read-only.", 12, ui.palette.muted, false));
        if (spec.executable()) {
            action.addView(ui.button(running ? "Guided fix running…" : spec.buttonLabel, ui.palette.orange,
                view -> confirmOrRun()));
        } else {
            action.addView(ui.button(spec.buttonLabel, ui.palette.orange, view -> openSource()));
        }
        action.addView(ui.secondaryButton("Open source workbench", view -> openSource()));
        content.addView(action);

        if (result != null && !"none".equals(result.action)) {
            content.addView(ui.sectionTitle("Latest guided-fix result"));
            LinearLayout last = ui.surface();
            last.addView(ui.text(result.ok ? "Completed" : "Needs attention", 17,
                result.ok ? ui.palette.green : ui.palette.yellow, true));
            last.addView(ui.text(result.message, 12, ui.palette.muted, false));
            last.addView(ui.text("changed=" + result.changed + " · user action required=" + result.userActionRequired,
                11, ui.palette.cyan, false));
            if (result.userActionRequired && GuidedFixPolicy.REPAIR_NOTIFICATIONS.equals(result.action)) {
                last.addView(ui.secondaryButton("Open Android notification settings", view -> openNotificationSettings()));
            }
            content.addView(last);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setXbsContentView(scroll);
    }

    private void confirmOrRun() {
        if (running) return;
        if (!spec.confirmationRequired) { runConfirmed(); return; }
        new AlertDialog.Builder(this)
            .setTitle(spec.title + "?")
            .setMessage(spec.explanation + "\n\nRisk: " + spec.riskLabel())
            .setNegativeButton("Cancel", null)
            .setPositiveButton(spec.buttonLabel, (dialog, which) -> prepareAndRun())
            .show();
    }

    private void prepareAndRun() {
        if (GuidedFixPolicy.REPAIR_NOTIFICATIONS.equals(spec.action)
            && Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        runConfirmed();
    }

    private void runConfirmed() {
        if (running) return;
        running = true;
        rebuild();
        executor.execute(() -> {
            GuidedFixResult next = GuidedFixExecutor.execute(this, spec);
            GuidedFixStore.save(this, next);
            DebugHealthEvaluator.evaluate(this);
            runOnUiThread(() -> {
                result = next;
                item = find(DebugHealthStore.cached(this), spec.checkId);
                spec = GuidedFixPolicy.forItem(item);
                running = false;
                rebuild();
                Toast.makeText(this, next.ok ? "Guided fix completed" : "Guided fix needs attention", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void openSource() {
        String action = spec.sourceActionId;
        if (DebugHealthPolicy.ACTION_CONFIGURATION.equals(action)) {
            startActivity(new Intent(this, XbsConfigActivity.class));
        } else if (DebugHealthPolicy.ACTION_ACTIVITY.equals(action)) {
            startActivity(new Intent(this, ActivityWorkbenchActivity.class));
        } else if (DebugHealthPolicy.ACTION_RUNTIME.equals(action)) {
            MoreSettingsActivity.open(this, SettingsDestination.RUNTIME);
        } else if (DebugHealthPolicy.ACTION_PROCESS.equals(action)) {
            startActivity(new Intent(this, ProcessMonitorActivity.class));
        } else if (DebugHealthPolicy.ACTION_NOTIFICATIONS.equals(action)) {
            MoreSettingsActivity.open(this, SettingsDestination.NOTIFICATIONS);
        } else {
            startActivity(new Intent(this, RootDiagnosticsActivity.class));
        }
    }

    private void openNotificationSettings() {
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private int severityColor(DebugHealthReport.Item value) {
        if (value == null || value.unknown()) return ui.palette.cyan;
        if (value.blocker()) return ui.palette.red;
        if (value.warning()) return ui.palette.yellow;
        return ui.palette.green;
    }

    private static DebugHealthReport.Item find(DebugHealthReport report, String id) {
        if (report == null || id == null) return null;
        for (DebugHealthReport.Item item : report.items) if (id.equals(item.id)) return item;
        return null;
    }
}
