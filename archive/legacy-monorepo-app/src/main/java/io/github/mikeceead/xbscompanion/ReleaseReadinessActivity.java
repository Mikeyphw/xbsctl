package io.github.mikeceead.xbscompanion;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Final cached-evidence release gate with direct navigation to every actionable subsystem. */
public final class ReleaseReadinessActivity extends XbsActivity {
    private UiKit ui;
    private ReleaseReadinessReport report;

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        evaluateAndRender();
    }

    @Override protected void onResume() {
        super.onResume();
        if (report != null) evaluateAndRender();
    }

    private void evaluateAndRender() {
        report = ReleaseReadinessEvaluator.evaluate(this);
        ui = new UiKit(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28));
        root.setBackgroundColor(ui.palette.background);
        root.addView(ui.text("RELEASE READINESS", 12, ui.palette.orange, true));
        root.addView(ui.text("Phase 18 seal", 28, ui.palette.text, true));
        root.addView(ui.text("A deterministic gate over cached root, local-history, runtime, transaction, notification, and process evidence.", 13, ui.palette.muted, false));
        root.addView(ui.sectionTitle("Gate"));
        root.addView(summaryCard());
        addItems(root, "Blockers", ReleaseReadinessReport.BLOCKER);
        addItems(root, "Attention", ReleaseReadinessReport.WARNING);
        addPassedSummary(root);
        root.addView(ui.sectionTitle("Actions"));
        root.addView(actionsCard());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setXbsContentView(scroll);
    }


    private void addPassedSummary(LinearLayout root) {
        int count = report.passCount();
        root.addView(ui.sectionTitle("Passed checks · " + count));
        LinearLayout card = ui.surface();
        card.addView(ui.text(count + " checks passed", 16, ui.palette.green, true));
        card.addView(ui.text("Passing checks are collapsed so blockers and warnings stay prominent.", 12, ui.palette.muted, false));
        if (count > 0) card.addView(ui.secondaryButton("Show passed checks", v -> showPassedChecks()));
        root.addView(card);
    }

    private void showPassedChecks() {
        StringBuilder body = new StringBuilder();
        for (ReleaseReadinessReport.Item item : report.items) {
            if (!item.passed()) continue;
            if (body.length() > 0) body.append("\n\n");
            body.append("✓ ").append(item.title).append("\n").append(item.detail);
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Passed checks · " + report.passCount())
            .setMessage(body.length() == 0 ? "No passed checks." : body.toString())
            .setPositiveButton("Close", null)
            .show();
    }
    private LinearLayout summaryCard() {
        LinearLayout card = ui.surface();
        int color = report.blockerCount() > 0 ? ui.palette.red
            : report.warningCount() > 0 ? ui.palette.yellow : ui.palette.green;
        card.addView(ui.text(report.headline(), 21, color, true));
        card.addView(ui.text(report.compactSummary(), 12, ui.palette.muted, false));
        card.addView(ui.text("Version " + report.versionName + " (" + report.versionCode + ") · "
            + (report.debugBuild ? "debug build" : "release build"), 12, ui.palette.muted, false));
        card.addView(ui.text("Evaluated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(new Date(report.evaluatedAt)), 12, ui.palette.muted, false));
        card.addView(ui.button("Re-evaluate cached evidence", ui.palette.orange, v -> evaluateAndRender()));
        card.addView(ui.secondaryButton("Copy readiness report", v -> copy("XBS release readiness", report.supportText(), "Readiness report copied")));
        return card;
    }

    private void addItems(LinearLayout root, String title, int severity) {
        int count = 0;
        for (ReleaseReadinessReport.Item item : report.items) if (item.severity == severity) count++;
        if (count == 0 && severity != ReleaseReadinessReport.PASS) return;
        root.addView(ui.sectionTitle(title + " · " + count));
        if (count == 0) {
            LinearLayout empty = ui.surface();
            empty.addView(ui.text("No " + title.toLowerCase(Locale.ROOT), 16, ui.palette.green, true));
            root.addView(empty);
            return;
        }
        for (ReleaseReadinessReport.Item item : report.items) {
            if (item.severity != severity) continue;
            LinearLayout card = ui.compactSurface();
            int color = item.blocker() ? ui.palette.red : item.warning() ? ui.palette.yellow : ui.palette.green;
            card.addView(ui.text(item.title, 16, color, true));
            card.addView(ui.text(item.detail, 12, ui.palette.muted, false));
            if (!item.passed() && hasAction(item.id)) {
                card.addView(ui.secondaryButton(actionLabel(item.id), v -> openAction(item.id)));
            }
            root.addView(card);
        }
    }

    private LinearLayout actionsCard() {
        LinearLayout card = ui.surface();
        card.addView(ui.text("Release details", 17, ui.palette.orange, true));
        card.addView(ui.text("Use System Health for remediation. This screen remains a release-engineering detail view.", 12, ui.palette.muted, false));
        card.addView(ui.button("Back to System Health", ui.palette.orange,
            v -> startActivity(new Intent(this, DebugWorkbenchActivity.class))));
        card.addView(ui.secondaryButton("Copy redacted support bundle",
            v -> copy("XBS support bundle", SupportBundleBuilder.build(this), "Redacted support bundle copied")));
        return card;
    }

    private boolean hasAction(String id) {
        return id.startsWith("diagnostics") || "root_access".equals(id) || "xbs_binaries".equals(id)
            || "storage_paths".equals(id) || "foreground_services".equals(id)
            || "command_service".equals(id) || "config_transaction".equals(id)
            || id.startsWith("history_") || id.startsWith("runtime") || "notifications".equals(id)
            || "event_monitor".equals(id) || id.startsWith("process");
    }

    private String actionLabel(String id) {
        if ("config_transaction".equals(id)) return "Open configuration journal";
        if (id.startsWith("history_")) return "Recheck local history";
        if (id.startsWith("runtime")) return "Open runtime overview";
        if ("notifications".equals(id)) return "Open notification settings";
        if ("event_monitor".equals(id)) return "Open monitor settings";
        if (id.startsWith("process")) return "Open process monitor";
        return "Open root diagnostics";
    }

    private void openAction(String id) {
        if ("config_transaction".equals(id)) {
            startActivity(new Intent(this, XbsConfigActivity.class));
        } else if (id.startsWith("history_")) {
            XbsHistoryStore.status(this);
            evaluateAndRender();
        } else if (id.startsWith("runtime")) {
            startActivity(new Intent(this, MainActivity.class).putExtra("page", 0));
        } else if ("notifications".equals(id)) {
            openNotificationSettings();
        } else if ("event_monitor".equals(id)) {
            startActivity(new Intent(this, MainActivity.class).putExtra("page", 4));
        } else if (id.startsWith("process")) {
            startActivity(new Intent(this, ProcessMonitorActivity.class));
        } else {
            startActivity(new Intent(this, RootDiagnosticsActivity.class));
        }
    }

    private void openNotificationSettings() {
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private void copy(String label, String value, String toast) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }
}
