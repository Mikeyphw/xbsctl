package io.github.mikeceead.xbscompanion;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class EventControlActivity extends XbsActivity {
    private UiKit ui;
    @Override protected void onCreate(Bundle state) { super.onCreate(state); rebuild(); }
    @Override protected void onResume() { super.onResume(); rebuild(); }

    private void rebuild() {
        ui = new UiKit(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(28));
        content.setBackgroundColor(ui.palette.background);
        content.addView(ui.text("XBS COMPANION", 12, ui.palette.orange, true));
        content.addView(ui.text("Event controls", 28, ui.palette.text, true));
        XbsSnapshot snapshot = XbsStateStore.current(this);
        TextView summary = ui.text(snapshot.summary(), 13, ui.palette.muted, false);
        summary.setPadding(0, ui.dp(5), 0, ui.dp(12));
        content.addView(summary);
        if (!snapshot.active.isEmpty()) content.addView(ui.sectionTitle("Active"));
        for (String name : snapshot.active) content.addView(eventCard(name, true));
        if (!snapshot.inactive.isEmpty()) content.addView(ui.sectionTitle("Available"));
        for (String name : snapshot.inactive) content.addView(eventCard(name, false));
        content.addView(ui.secondaryButton("Refresh status", v -> EventMonitorService.refresh(this)));
        content.addView(ui.secondaryButton("Close", v -> finish()));
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setXbsContentView(scroll);
    }

    private LinearLayout eventCard(String name, boolean active) {
        LinearLayout card = ui.surface();
        card.addView(ui.text(XbsSnapshot.pretty(name), 17, active ? ui.palette.green : ui.palette.text, true));
        card.addView(ui.text(active ? "Currently active" : "Available to start", 12, ui.palette.muted, false));
        String action = active ? "event_stop" : "event_run";
        card.addView(ui.button(active ? "Stop event" : "Start event", active ? ui.palette.red : ui.palette.orange,
            v -> { RootCommandRunner.run(this, SuiteCatalog.find("xbs", action), name, null); finish(); }));
        return card;
    }
}
