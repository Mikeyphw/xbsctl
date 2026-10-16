package io.github.mikeceead.xbscompanion;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

/** Read-only workbench for the exact Phase 31 release evidence bundled into this APK. */
public final class FinalReleaseSealActivity extends XbsActivity {
    private UiKit ui;
    private FinalReleaseSealReport report;

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (ui != null) render();
    }

    private void render() {
        ui = new UiKit(this);
        report = FinalReleaseSealStore.evaluate(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28));
        root.setBackgroundColor(ui.palette.background);
        root.addView(ui.text("FINAL RELEASE SEAL", 12, ui.palette.orange, true));
        root.addView(ui.primaryTitle("Phase 31 evidence"));
        root.addView(ui.text("Fourteen fail-closed gates. Missing, stale, synthetic, or mismatched evidence remains a blocker.", 13, ui.palette.muted, false));
        root.addView(ui.sectionTitle("Summary"));
        root.addView(summary());
        root.addView(ui.sectionTitle("Gates · " + report.gates.size()));
        for (FinalReleaseSealReport.Gate gate : report.gates) root.addView(gateCard(gate));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setXbsContentView(scroll);
    }

    private LinearLayout summary() {
        LinearLayout card = ui.surface();
        card.addView(ui.text(report.headline(), 21, report.ready() ? ui.palette.green : ui.palette.red, true));
        card.addView(ui.text(report.compactSummary(), 12, ui.palette.muted, false));
        card.addView(ui.text("Source seal · " + shortHash(report.sourceSealSha256), 12, ui.palette.muted, false));
        card.addView(ui.text("Evaluated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(new Date(report.evaluatedAt)), 12, ui.palette.muted, false));
        card.addView(ui.button("Re-evaluate installed APK", ui.palette.orange, view -> render()));
        card.addView(ui.secondaryButton("Copy final seal report", view -> copy()));
        return card;
    }

    private LinearLayout gateCard(FinalReleaseSealReport.Gate gate) {
        LinearLayout card = ui.surface();
        int color = gate.passed() ? ui.palette.green : ui.palette.red;
        card.addView(ui.text((gate.passed() ? "PASS · " : "BLOCKER · ") + gate.title, 16, color, true));
        card.addView(ui.text(gate.detail, 12, ui.palette.muted, false));
        if (gate.capturedAt > 0) {
            card.addView(ui.text("Evidence " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(gate.capturedAt)), 11, ui.palette.muted, false));
        }
        return card;
    }

    private void copy() {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("XBS final release seal", report.supportText()));
        Toast.makeText(this, "Final release seal copied", Toast.LENGTH_SHORT).show();
    }

    private static String shortHash(String value) {
        if (value == null || value.length() < 16) return "missing";
        return value.substring(0, 16) + "…";
    }
}
