package io.github.mikeceead.xbscompanion;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only health workbench over cached and local Android evidence. */
public final class DebugWorkbenchActivity extends XbsActivity {
    private static final String[] DOMAINS = {
        DebugHealthPolicy.DOMAIN_ROOT,
        DebugHealthPolicy.DOMAIN_STORAGE,
        DebugHealthPolicy.DOMAIN_RUNTIME,
        DebugHealthPolicy.DOMAIN_NOTIFICATIONS,
        DebugHealthPolicy.DOMAIN_OBSERVABILITY
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private UiKit ui;
    private DebugHealthReport report;
    private boolean evaluating;
    private boolean initialResume = true;

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        registerXbsBackHandler(this::finish);
        report = DebugHealthStore.cached(this);
        rebuild();
        refresh(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (initialResume) {
            initialResume = false;
        } else if (!evaluating) {
            refresh(false);
        }
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void refresh(boolean toast) {
        if (evaluating) return;
        evaluating = true;
        rebuild();
        executor.execute(() -> {
            DebugHealthReport next = DebugHealthEvaluator.evaluate(this);
            runOnUiThread(() -> {
                report = next;
                evaluating = false;
                rebuild();
                if (toast) Toast.makeText(this, "Debug health re-evaluated", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void rebuild() {
        ui = new UiKit(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28));
        content.setBackgroundColor(ui.palette.background);
        content.addView(ui.text("DEBUG WORKBENCH", 12, ui.palette.orange, true));
        content.addView(ui.text("Health model", 28, ui.palette.text, true));
        content.addView(ui.text("A structured snapshot of cached root, compatibility, storage, history, service, notification, runtime, and process evidence.", 13, ui.palette.muted, false));
        content.addView(ui.text("Opening this screen never requests root, starts a command, or refreshes /proc.", 12, ui.palette.cyan, true));
        content.addView(ui.sectionTitle("Summary"));
        content.addView(summaryCard());

        for (String domain : DOMAINS) addDomain(content, domain);

        content.addView(ui.sectionTitle("Evidence sources"));
        content.addView(actionsCard());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setXbsContentView(scroll);
    }

    private LinearLayout summaryCard() {
        LinearLayout card = ui.surface();
        int color = overallColor();
        card.addView(ui.text(evaluating ? "Evaluating cached evidence…" : report.headline(), 21, color, true));
        card.addView(ui.text(report.compactSummary(), 12, ui.palette.muted, false));
        if (report.evaluatedAt > 0L) {
            card.addView(ui.text("Evaluated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(report.evaluatedAt)), 12, ui.palette.muted, false));
        } else {
            card.addView(ui.text("No Debug Workbench snapshot has been cached yet.", 12, ui.palette.muted, false));
        }
        card.addView(ui.button(evaluating ? "Evaluation running" : "Re-evaluate cached evidence",
            ui.palette.orange, view -> refresh(true)));
        card.addView(ui.secondaryButton("Copy health snapshot", view -> copy("XBS debug health", report.supportText(), "Debug health copied")));
        return card;
    }

    private void addDomain(LinearLayout content, String domain) {
        int count = 0;
        for (DebugHealthReport.Item item : report.items) if (domain.equals(item.domain)) count++;
        if (count == 0) return;
        content.addView(ui.sectionTitle(domain + " · " + count));
        for (DebugHealthReport.Item item : report.items) {
            if (!domain.equals(item.domain)) continue;
            content.addView(itemCard(item));
        }
    }

    private LinearLayout itemCard(DebugHealthReport.Item item) {
        LinearLayout card = ui.surface();
        card.addView(ui.text(item.title, 17, itemColor(item), true));
        card.addView(ui.text(item.severityLabel().toUpperCase(java.util.Locale.US)
            + " · evidence " + item.evidenceLabel(), 11, itemColor(item), true));
        card.addView(ui.text(item.detail, 12, ui.palette.muted, false));
        card.addView(ui.text("Source: " + value(item.source) + evidenceAge(item), 11, ui.palette.cyan, false));
        if (item.actionable() && !item.passed()) {
            card.addView(ui.secondaryButton("Open guided fix", view -> GuidedFixActivity.open(this, item.id)));
        }
        return card;
    }

    private LinearLayout actionsCard() {
        LinearLayout card = ui.surface();
        card.addView(ui.text("Refresh evidence at its source", 17, ui.palette.orange, true));
        card.addView(ui.text("The workbench only re-evaluates existing evidence. Open a source screen when a root, runtime, process, or configuration refresh is actually needed.", 12, ui.palette.muted, false));
        card.addView(ui.button("Open Root Diagnostics", ui.palette.orange,
            view -> startActivity(new Intent(this, RootDiagnosticsActivity.class))));
        card.addView(ui.secondaryButton("Open Activity Workbench",
            view -> startActivity(new Intent(this, ActivityWorkbenchActivity.class))));
        card.addView(ui.secondaryButton("Open Process Monitor",
            view -> startActivity(new Intent(this, ProcessMonitorActivity.class))));
        card.addView(ui.secondaryButton("Open Release Readiness",
            view -> startActivity(new Intent(this, ReleaseReadinessActivity.class))));
        card.addView(ui.secondaryButton("Copy redacted support bundle",
            view -> copy("XBS support bundle", SupportBundleBuilder.build(this), "Redacted support bundle copied")));
        return card;
    }

    private void openAction(String actionId) {
        if (DebugHealthPolicy.ACTION_CONFIGURATION.equals(actionId)) {
            startActivity(new Intent(this, XbsConfigActivity.class));
        } else if (DebugHealthPolicy.ACTION_ACTIVITY.equals(actionId)) {
            startActivity(new Intent(this, ActivityWorkbenchActivity.class));
        } else if (DebugHealthPolicy.ACTION_RUNTIME.equals(actionId)) {
            startActivity(new Intent(this, MainActivity.class).putExtra("page", 0));
        } else if (DebugHealthPolicy.ACTION_PROCESS.equals(actionId)) {
            startActivity(new Intent(this, ProcessMonitorActivity.class));
        } else if (DebugHealthPolicy.ACTION_NOTIFICATIONS.equals(actionId)) {
            MoreSettingsActivity.open(this, SettingsDestination.NOTIFICATIONS);
        } else {
            startActivity(new Intent(this, RootDiagnosticsActivity.class));
        }
    }

    private String actionLabel(String actionId) {
        if (DebugHealthPolicy.ACTION_CONFIGURATION.equals(actionId)) return "Open configuration journal";
        if (DebugHealthPolicy.ACTION_ACTIVITY.equals(actionId)) return "Open Activity Workbench";
        if (DebugHealthPolicy.ACTION_RUNTIME.equals(actionId)) return "Open runtime overview";
        if (DebugHealthPolicy.ACTION_PROCESS.equals(actionId)) return "Open Process Monitor";
        if (DebugHealthPolicy.ACTION_NOTIFICATIONS.equals(actionId)) return "Open notification controls";
        return "Open Root Diagnostics";
    }

    private int overallColor() {
        if (report.blockerCount() > 0) return ui.palette.red;
        if (report.warningCount() > 0) return ui.palette.yellow;
        if (report.unknownCount() > 0 || !report.evaluated()) return ui.palette.cyan;
        return ui.palette.green;
    }

    private int itemColor(DebugHealthReport.Item item) {
        if (item.blocker()) return ui.palette.red;
        if (item.warning()) return ui.palette.yellow;
        if (item.unknown()) return ui.palette.cyan;
        return ui.palette.green;
    }

    private String evidenceAge(DebugHealthReport.Item item) {
        if (item.capturedAt <= 0L) return " · no capture time";
        long age = Math.max(0L, System.currentTimeMillis() - item.capturedAt);
        if (age < 60_000L) return " · " + Math.max(0L, age / 1000L) + "s old";
        if (age < 60 * 60_000L) return " · " + age / 60_000L + "m old";
        return " · " + age / (60 * 60_000L) + "h old";
    }

    private void copy(String label, String value, String toast) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private static String value(String text) {
        return text == null || text.trim().isEmpty() ? "unknown" : text.trim();
    }
}
