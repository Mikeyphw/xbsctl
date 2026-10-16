package io.github.mikeceead.xbscompanion;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Unified System Health surface. Fixes stay in place; Details is the only diagnostic drill-down. */
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
        content.addView(ui.text("DIAGNOSTICS", 12, ui.palette.orange, true));
        content.addView(ui.text("System health", 28, ui.palette.text, true));
        content.addView(ui.text("One place to understand and repair XBS Companion. Fix runs the bounded remediation here; Details opens a technical source only when you ask for it.", 13, ui.palette.muted, false));
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
            card.addView(ui.text("No System Health snapshot has been cached yet.", 12, ui.palette.muted, false));
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
        LinearLayout card = ui.compactSurface();
        card.addView(ui.text(item.title, 17, itemColor(item), true));
        card.addView(ui.text(item.severityLabel().toUpperCase(java.util.Locale.US)
            + " · evidence " + item.evidenceLabel(), 11, itemColor(item), true));
        card.addView(ui.text(item.detail, 12, ui.palette.muted, false));
        card.addView(ui.text("Source: " + value(item.source) + evidenceAge(item), 11, ui.palette.cyan, false));
        if (item.actionable() && !item.passed()) {
            GuidedFixPolicy.Spec spec = GuidedFixPolicy.forItem(item);
            if (spec.executable()) {
                card.addView(ui.button("Fix", ui.palette.orange, view -> confirmAndRunFix(spec)));
            }
            card.addView(ui.secondaryButton("Details", view -> openAction(item.actionId)));
        }
        return card;
    }

    private LinearLayout actionsCard() {
        LinearLayout card = ui.surface();
        card.addView(ui.text("Technical evidence", 17, ui.palette.orange, true));
        card.addView(ui.text("Most problems can be fixed above without leaving System Health. These tools are for inspection and support.", 12, ui.palette.muted, false));
        card.addView(ui.button("Refresh root evidence", ui.palette.orange,
            view -> confirmAndRunFix(GuidedFixPolicy.forItem(findItem("root_evidence")))));
        card.addView(ui.secondaryButton("Root diagnostics",
            view -> startActivity(new Intent(this, RootDiagnosticsActivity.class))));
        card.addView(ui.secondaryButton("Release details",
            view -> startActivity(new Intent(this, ReleaseReadinessActivity.class))));
        card.addView(ui.secondaryButton("Copy redacted support bundle",
            view -> copy("XBS support bundle", SupportBundleBuilder.build(this), "Redacted support bundle copied")));
        return card;
    }

    private DebugHealthReport.Item findItem(String id) {
        for (DebugHealthReport.Item item : report.items) if (id.equals(item.id)) return item;
        return null;
    }

    private void confirmAndRunFix(GuidedFixPolicy.Spec spec) {
        if (spec == null || !spec.executable()) return;
        Runnable run = () -> runFix(spec);
        if (!spec.confirmationRequired) { run.run(); return; }
        new AlertDialog.Builder(this)
            .setTitle(spec.title)
            .setMessage(spec.explanation + "\n\n" + spec.riskLabel())
            .setNegativeButton("Cancel", null)
            .setPositiveButton(spec.buttonLabel, (dialog, which) -> run.run())
            .show();
    }

    private void runFix(GuidedFixPolicy.Spec spec) {
        if (evaluating) return;
        evaluating = true;
        rebuild();
        executor.execute(() -> {
            GuidedFixResult result = GuidedFixExecutor.execute(this, spec);
            GuidedFixStore.save(this, result);
            DebugHealthReport next = DebugHealthEvaluator.evaluate(this);
            runOnUiThread(() -> {
                report = next;
                evaluating = false;
                rebuild();
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
            });
        });
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
