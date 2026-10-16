package io.github.mikeceead.xbscompanion;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Searchable, filterable, selectable Activity workbench over Room-backed command history and event evidence. */
public final class ActivityWorkbenchActivity extends XbsActivity {
    private enum Mode { COMMANDS, EVENTS }

    private final Set<String> selected = new HashSet<>();
    private UiKit ui;
    private String queryText = "";
    private ActivityQuery.Status status = ActivityQuery.Status.ALL;
    private ActivityQuery.Window window = ActivityQuery.Window.ALL;
    private String source = ActivityQuery.SOURCE_ALL;
    private boolean pinnedOnly;
    private Mode mode = Mode.COMMANDS;
    private boolean registered;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { rebuild(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            queryText = state.getString("query", "");
            status = ActivityQuery.Status.values()[safeOrdinal(state.getInt("status"), ActivityQuery.Status.values().length)];
            window = ActivityQuery.Window.values()[safeOrdinal(state.getInt("window"), ActivityQuery.Window.values().length)];
            source = state.getString("source", ActivityQuery.SOURCE_ALL);
            pinnedOnly = state.getBoolean("pinned_only", false);
            mode = state.getBoolean("events", false) ? Mode.EVENTS : Mode.COMMANDS;
            selected.addAll(state.getStringArrayList("selected") == null ? new ArrayList<>() : state.getStringArrayList("selected"));
        }
        rebuild();
        revealRunFromIntent();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("query", queryText);
        out.putInt("status", status.ordinal());
        out.putInt("window", window.ordinal());
        out.putString("source", source);
        out.putBoolean("pinned_only", pinnedOnly);
        out.putBoolean("events", mode == Mode.EVENTS);
        out.putStringArrayList("selected", new ArrayList<>(selected));
        super.onSaveInstanceState(out);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(RunStore.ACTION_UPDATED);
            filter.addAction(XbsStateStore.ACTION_STATE_UPDATED);
            ContextCompat.registerReceiver(this, updates, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
    }

    @Override protected void onStop() {
        if (registered) { unregisterReceiver(updates); registered = false; }
        super.onStop();
    }

    private void rebuild() {
        ui = new UiKit(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.palette.background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(10));
        header.addView(ui.text("ACTIVITY WORKBENCH", 12, ui.palette.orange, true));
        header.addView(ui.text(mode == Mode.COMMANDS ? "Command history" : "Event timeline", 28, ui.palette.text, true));
        header.addView(ui.text("Search, classify, pin, select, export, and safely prune local evidence.", 13, ui.palette.muted, false));
        header.addView(ui.secondaryButton("Back to XBS Companion", view -> finish()));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(30));
        addControls(content);
        if (mode == Mode.COMMANDS) addCommandHistory(content); else addEventTimeline(content);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setXbsContentView(root);
    }

    private void addControls(LinearLayout content) {
        content.addView(ui.sectionTitle("Find activity"));
        LinearLayout searchCard = ui.surface();
        EditText search = ui.input(queryText, mode == Mode.COMMANDS
            ? "Search title, command, status, source, output, or run ID"
            : "Search event name, type, state, severity, or message", false);
        searchCard.addView(search);
        searchCard.addView(ui.button("Apply search", ui.palette.orange, view -> {
            queryText = search.getText().toString();
            rebuild();
        }));
        searchCard.addView(ui.secondaryButton("Clear search", view -> { queryText = ""; rebuild(); }));
        content.addView(searchCard);

        content.addView(ui.sectionTitle("Filters"));
        LinearLayout filters = ui.surface();
        filters.addView(ui.button("View: " + (mode == Mode.COMMANDS ? "Commands" : "Events"), ui.palette.cyan, view -> {
            mode = mode == Mode.COMMANDS ? Mode.EVENTS : Mode.COMMANDS;
            if (mode == Mode.EVENTS) { source = ActivityQuery.SOURCE_ALL; pinnedOnly = false; }
            rebuild();
        }));
        filters.addView(ui.secondaryButton("Status: " + status.label, view -> { status = mode == Mode.EVENTS ? (status == ActivityQuery.Status.PROBLEMS ? ActivityQuery.Status.ALL : ActivityQuery.Status.PROBLEMS) : status.next(); rebuild(); }));
        filters.addView(ui.secondaryButton("Time: " + window.label, view -> { window = window.next(); rebuild(); }));
        if (mode == Mode.COMMANDS) {
            filters.addView(ui.secondaryButton("Source: " + ActivityQuery.sourceLabel(source), view -> {
                source = ActivityQuery.nextSource(source); rebuild();
            }));
            SwitchCompat pinned = ui.toggle("Pinned records only", pinnedOnly);
            pinned.setOnCheckedChangeListener((button, checked) -> { pinnedOnly = checked; rebuild(); });
            filters.addView(pinned);
            filters.addView(ui.secondaryButton("Retention: " + AppSettings.historyRetention(this) + " command records", view -> cycleRetention()));
        }
        content.addView(filters);
    }

    private void addCommandHistory(LinearLayout content) {
        long now = System.currentTimeMillis();
        ActivityQuery query = new ActivityQuery(queryText, status, window, source, pinnedOnly);
        List<RunRecord> all = RunStore.all(this);
        List<RunRecord> visible = query.apply(all, now);
        selected.retainAll(ids(all));

        content.addView(ui.sectionTitle("Command records"));
        LinearLayout summary = ui.surface();
        summary.addView(ui.text(query.summary(visible.size()), 16, ui.palette.orange, true));
        summary.addView(ui.text("Stored: " + all.size() + " · pinned: " + pinnedCount(all)
            + " · selected: " + selected.size(), 13, ui.palette.muted, false));
        summary.addView(ui.secondaryButton("Select visible finished records", view -> {
            for (RunRecord run : visible) if (!RunFilter.isActive(run)) selected.add(run.runId);
            rebuild();
        }));
        summary.addView(ui.secondaryButton("Clear selection", view -> { selected.clear(); rebuild(); }));
        summary.addView(ui.button("Export selected", ui.palette.green, view -> exportRuns(selectedRuns(all), false)));
        summary.addView(ui.secondaryButton("Share visible results", view -> exportRuns(visible, true)));
        summary.addView(ui.secondaryButton("Delete selected finished records", view -> confirmDeleteSelected(all)));
        summary.addView(ui.secondaryButton("Clear current unpinned finished filter", view -> confirmClearVisible(visible)));
        content.addView(summary);

        if (visible.isEmpty()) {
            content.addView(messageCard("No matching command records", "Adjust search, status, source, time, or pin filters."));
            return;
        }
        int limit = Math.min(AppSettings.historyRetention(this), visible.size());
        for (int i = 0; i < limit; i++) content.addView(runCard(visible.get(i)));
        if (visible.size() > limit) content.addView(messageCard("More records hidden", (visible.size() - limit) + " matching record(s) exceed the current retention view limit."));
    }

    private void addEventTimeline(LinearLayout content) {
        long now = System.currentTimeMillis();
        ActivityQuery query = new ActivityQuery(queryText, status, window, ActivityQuery.SOURCE_ALL, false);
        List<XbsEvent> events = query.applyEvents(XbsStateStore.events(this), now);
        content.addView(ui.sectionTitle("Runtime events"));
        LinearLayout summary = ui.surface();
        summary.addView(ui.text(events.size() + " matching event(s) · " + status.label + " · " + window.label, 16, ui.palette.orange, true));
        summary.addView(ui.text("Event records are bounded runtime evidence and are separate from Room command history.", 13, ui.palette.muted, false));
        summary.addView(ui.button("Share visible events", ui.palette.green, view -> exportEvents(events)));
        summary.addView(ui.secondaryButton("Clear event timeline", view -> confirmClearEvents()));
        content.addView(summary);
        if (events.isEmpty()) {
            content.addView(messageCard("No matching events", "Enable the event monitor or adjust search, status, and time filters."));
            return;
        }
        for (int i = 0; i < Math.min(180, events.size()); i++) content.addView(eventCard(events.get(i)));
    }

    private LinearLayout runCard(RunRecord run) {
        LinearLayout card = ui.surface();
        boolean active = RunFilter.isActive(run);
        boolean problem = ActivityQuery.isProblem(run);
        int color = problem ? ui.palette.red : "ok".equals(run.status) ? ui.palette.green : active ? ui.palette.yellow : ui.palette.text;
        card.addView(ui.text((run.pinned ? "★ " : "") + run.title, 17, color, true));
        card.addView(ui.text(RunDetailFormatter.cardMessage(run), 12, ui.palette.muted, false));
        card.addView(ui.text("Source: " + ActivityQuery.sourceLabel(run.source) + " · "
            + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(timestamp(run))), 12, ui.palette.cyan, false));
        card.addView(ui.text(RunDetailFormatter.outputSummary(run), 12, ui.palette.muted, false));
        card.addView(actionRow(
            ui.secondaryButton(selected.contains(run.runId) ? "Selected" : "Select", view -> {
                if (!selected.add(run.runId)) selected.remove(run.runId); rebuild();
            }),
            ui.secondaryButton(run.pinned ? "Unpin" : "Pin", view -> { RunStore.setPinned(this, run.runId, !run.pinned); rebuild(); })
        ));
        card.addView(actionRow(
            ui.secondaryButton("Details", view -> showRun(run)),
            active && RunStore.isStaleActive(this, run) ? ui.button("Force finalize", ui.palette.red, view -> forceFinalize(run))
                : active ? ui.button("Cancel", ui.palette.red, view -> cancelRun(run))
                : RunFilter.isRetryable(run) ? ui.button("Retry", ui.palette.orange, view -> retryRun(run))
                : ui.secondaryButton("Delete", view -> confirmDeleteRun(run))
        ));
        return card;
    }

    private LinearLayout eventCard(XbsEvent event) {
        LinearLayout card = ui.surface();
        card.addView(ui.text(event.message, 16, event.isImportant() ? ui.palette.yellow : ui.palette.text, true));
        card.addView(ui.text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(new Date(event.timestamp * 1000L)) + " · " + event.type + " · " + event.state, 12, ui.palette.muted, false));
        card.addView(ui.text("Severity: " + event.severity + (event.replayed ? " · replayed" : ""), 12, ui.palette.cyan, false));
        card.addView(ui.secondaryButton("Copy event evidence", view -> copyText("XBS event", event.payloadJson)));
        return card;
    }

    private LinearLayout actionRow(Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMargins(0, ui.dp(2), ui.dp(4), 0);
        row.addView(first, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.setMargins(ui.dp(4), ui.dp(2), 0, 0);
        row.addView(second, right);
        return row;
    }

    private void cycleRetention() {
        int next = AppSettings.nextHistoryRetention(this);
        AppSettings.setHistoryRetention(this, next);
        RunStore.applyRetention(this);
        Toast.makeText(this, "Retention set to " + next + " command records", Toast.LENGTH_SHORT).show();
        rebuild();
    }

    private void confirmDeleteRun(RunRecord run) {
        if (RunFilter.isActive(run)) { Toast.makeText(this, "Active commands cannot be deleted", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("Delete this command record?")
            .setMessage(run.title + "\n\nThis removes only local history evidence.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> { RunStore.deleteFinished(this, run.runId); selected.remove(run.runId); rebuild(); })
            .show();
    }

    private void confirmDeleteSelected(List<RunRecord> all) {
        List<String> ids = new ArrayList<>();
        for (RunRecord run : all) if (selected.contains(run.runId) && !RunFilter.isActive(run)) ids.add(run.runId);
        if (ids.isEmpty()) { Toast.makeText(this, "No selected finished records", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("Delete " + ids.size() + " selected record(s)?")
            .setMessage("Active commands are always protected. This removes only local history evidence.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> {
                int deleted = RunStore.deleteFinished(this, ids);
                selected.removeAll(ids);
                Toast.makeText(this, "Deleted " + deleted + " record(s)", Toast.LENGTH_SHORT).show();
                rebuild();
            }).show();
    }

    private void confirmClearVisible(List<RunRecord> visible) {
        List<String> ids = new ArrayList<>();
        for (RunRecord run : visible) if (!RunFilter.isActive(run) && !run.pinned) ids.add(run.runId);
        if (ids.isEmpty()) { Toast.makeText(this, "No unpinned finished records in this filter", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("Clear " + ids.size() + " filtered record(s)?")
            .setMessage("Pinned and active records are protected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear", (dialog, which) -> { RunStore.deleteFinished(this, ids); selected.removeAll(ids); rebuild(); })
            .show();
    }

    private void confirmClearEvents() {
        new AlertDialog.Builder(this).setTitle("Clear event timeline?")
            .setMessage("This removes bounded local event evidence. It does not change XBS runtime state.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear", (dialog, which) -> { XbsStateStore.clearEvents(this); rebuild(); })
            .show();
    }

    private void exportRuns(List<RunRecord> runs, boolean share) {
        if (runs.isEmpty()) { Toast.makeText(this, "No command records to export", Toast.LENGTH_SHORT).show(); return; }
        String text = ActivityExportFormatter.format(runs);
        if (share) shareText("XBS Activity export", text); else copyText("XBS Activity export", text);
    }

    private void exportEvents(List<XbsEvent> events) {
        if (events.isEmpty()) { Toast.makeText(this, "No event records to export", Toast.LENGTH_SHORT).show(); return; }
        shareText("XBS Event timeline", ActivityExportFormatter.formatEvents(events));
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void shareText(String subject, String text) {
        Intent send = IntentSecurityPolicy.textShare(subject, text);
        startActivity(Intent.createChooser(send, "Share XBS activity"));
    }

    private void revealRunFromIntent() {
        String runId = IntentSecurityPolicy.internalId(getIntent(), "run_id", 96);
        if (runId == null || runId.trim().isEmpty()) return;
        getIntent().removeExtra("run_id");
        RunRecord run = RunStore.find(this, runId);
        if (run != null) showRun(run);
    }

    private void showRun(RunRecord run) {
        String body = RunDetailFormatter.detailBody(run);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(run.title).setMessage(body)
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy", (d, w) -> copyText("XBS result", body));
        if (RunDetailFormatter.isActive(run)) {
            if (RunStore.isStaleActive(this, run)) dialog.setNeutralButton("Force finalize", (d, w) -> forceFinalize(run));
            else dialog.setNeutralButton("Cancel run", (d, w) -> cancelRun(run));
        }
        dialog.show();
    }

    private void cancelRun(RunRecord run) {
        RootCommandService.requestCancel(this, run.runId);
        Toast.makeText(this, "Cancelling " + run.title, Toast.LENGTH_SHORT).show();
    }

    private void forceFinalize(RunRecord run) {
        if (RunStore.forceFinalizeStale(this, run.runId, "Force-finalized stale active command; no live foreground owner remains")) {
            Toast.makeText(this, "Stale command finalized", Toast.LENGTH_SHORT).show();
            rebuild();
        } else {
            Toast.makeText(this, "Command still appears live; cancel first", Toast.LENGTH_SHORT).show();
        }
    }

    private void retryRun(RunRecord run) {
        RunRetryPolicy.Decision decision = RunRetryPolicy.evaluate(run);
        if (!decision.allowed) { Toast.makeText(this, decision.reason, Toast.LENGTH_SHORT).show(); return; }
        Runnable launch = () -> {
            CommandLaunchResult result = RootCommandRunner.runCommand(this, decision.actionId, decision.title,
                decision.description, decision.command, decision.destructive, null, ExecutionSource.APP);
            Toast.makeText(this, result.started ? "Retry started" : result.error, Toast.LENGTH_SHORT).show();
        };
        if (decision.requiresConfirmation) {
            new AlertDialog.Builder(this).setTitle(decision.title).setMessage(decision.message)
                .setNegativeButton("Cancel", null).setPositiveButton("Retry", (d, w) -> launch.run()).show();
        } else launch.run();
    }

    private LinearLayout messageCard(String title, String message) {
        LinearLayout card = ui.surface();
        card.addView(ui.text(title, 17, ui.palette.text, true));
        card.addView(ui.text(message, 13, ui.palette.muted, false));
        return card;
    }

    private static Set<String> ids(List<RunRecord> runs) {
        Set<String> ids = new HashSet<>();
        if (runs != null) for (RunRecord run : runs) if (run != null) ids.add(run.runId);
        return ids;
    }

    private List<RunRecord> selectedRuns(List<RunRecord> all) {
        List<RunRecord> runs = new ArrayList<>();
        for (RunRecord run : all) if (selected.contains(run.runId)) runs.add(run);
        return runs;
    }

    private static int pinnedCount(List<RunRecord> runs) {
        int count = 0;
        if (runs != null) for (RunRecord run : runs) if (run != null && run.pinned) count++;
        return count;
    }

    private static long timestamp(RunRecord run) { return run.finishedAt > 0L ? run.finishedAt : run.startedAt; }
    private static int safeOrdinal(int value, int length) { return value >= 0 && value < length ? value : 0; }
}
