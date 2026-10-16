package io.github.mikeceead.xbscompanion;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProcessMonitorActivity extends XbsActivity {
    static final String EXTRA_PICK = "pick";
    static final String EXTRA_RULES = "process_rules";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final Set<String> selected = new LinkedHashSet<>();
    private UiKit ui;
    private LinearLayout list;
    private TextView summary;
    private EditText search;
    private EditText niceInput;
    private ProcessSnapshot snapshot = ProcessSnapshot.error("Loading");
    private boolean pickMode;
    private boolean resumed;
    private int sortMode;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refresh(true);
            handler.postDelayed(this, 2000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        ui = new UiKit(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.palette.background);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets values = insets.getInsets(WindowInsets.Type.systemBars());
                top = values.top;
                bottom = values.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(8));
        head.addView(ui.text("XBS LIVE PROCESSES", 12, ui.palette.orange, true));
        head.addView(ui.text(pickMode ? "Select process rules" : "Process monitor", 28, ui.palette.text, true));
        summary = ui.text("Sampling CPU and memory…", 12, ui.palette.muted, false);
        head.addView(summary);
        search = ui.input("", "Search process, app, package or PID", false);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable value) {}
        });
        head.addView(search);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button sort = ui.secondaryButton("Sort: CPU", view -> {
            sortMode = (sortMode + 1) % 3;
            ((android.widget.Button) view).setText(
                sortMode == 0 ? R.string.xbs_sort_cpu : sortMode == 1 ? R.string.xbs_sort_ram : R.string.xbs_sort_name);
            render();
        });
        android.widget.Button refresh = ui.secondaryButton("Refresh", view -> refresh(true));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        first.setMargins(0, 0, ui.dp(4), 0);
        controls.addView(sort, first);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMargins(ui.dp(4), 0, 0, 0);
        controls.addView(refresh, second);
        head.addView(controls);

        if (pickMode) {
            niceInput = ui.input("10", "Nice value for selected rules", false);
            niceInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            head.addView(niceInput);
        }
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(20));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout foot = new LinearLayout(this);
        foot.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(10));
        if (pickMode) {
            android.widget.Button cancel = ui.secondaryButton("Cancel", view -> finish());
            android.widget.Button add = ui.button("Add selected rules", ui.palette.green, view -> finishSelection());
            LinearLayout.LayoutParams firstFooter = new LinearLayout.LayoutParams(0, -2, 1);
            firstFooter.setMargins(0, 0, ui.dp(4), 0);
            foot.addView(cancel, firstFooter);
            LinearLayout.LayoutParams secondFooter = new LinearLayout.LayoutParams(0, -2, 1);
            secondFooter.setMargins(ui.dp(4), 0, 0, 0);
            foot.addView(add, secondFooter);
        } else {
            foot.addView(ui.secondaryButton("Close", view -> finish()));
        }
        root.addView(foot);
        setXbsContentView(root);
    }

    private void refresh(boolean force) {
        if (!loading.compareAndSet(false, true)) return;
        executor.execute(() -> {
            ProcessSnapshot result = ProcessRepository.load(this, force);
            runOnUiThread(() -> {
                snapshot = result;
                loading.set(false);
                render();
            });
        });
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        if (!snapshot.rootAvailable) {
            RootStatus root = XbsRootApi.cachedRootStatus(this);
            LinearLayout error = ui.surface();
            error.addView(ui.text(root.granted ? "Process scan unavailable" : "Root process access unavailable", 18, ui.palette.red, true));
            error.addView(ui.text(ResultFormatter.notificationText(snapshot.error), 12, ui.palette.muted, false));
            error.addView(ui.text("Root: " + root.summary(), 11, ui.palette.muted, false));
            if (snapshot.diagnostics.scanned > 0) {
                error.addView(ui.text(snapshot.diagnostics.summary(), 11, ui.palette.yellow, true));
            }
            error.addView(ui.button("Retry process scan", ui.palette.orange, view -> refresh(true)));
            error.addView(ui.secondaryButton("Recheck root authorization", view -> checkRoot()));
            list.addView(error);
            return;
        }

        if (snapshot.diagnostics.partial()) {
            LinearLayout warning = ui.surface();
            warning.addView(ui.text("Partial /proc visibility", 17, ui.palette.yellow, true));
            warning.addView(ui.text(snapshot.diagnostics.summary(), 12, ui.palette.muted, false));
            warning.addView(ui.text("CPU and memory values below only represent readable process entries.", 11, ui.palette.muted, false));
            list.addView(warning);
        }

        List<XbsProcess> rows = new ArrayList<>(snapshot.processes);
        if (sortMode == 1) {
            Collections.sort(rows, Comparator.comparingLong((XbsProcess process) -> process.rssKb).reversed());
        } else if (sortMode == 2) {
            Collections.sort(rows, Comparator.comparing((XbsProcess process) -> process.appLabel.toLowerCase(Locale.ROOT))
                .thenComparing((XbsProcess process) -> process.processName));
        }

        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        long totalRam = 0L;
        for (XbsProcess process : rows) {
            String haystack = (process.processName + " " + process.command + " " + process.executable + " "
                + process.selinuxContext + " " + process.packageName + " " + process.appLabel + " "
                + process.pid + " " + process.parentPid).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !haystack.contains(query)) continue;
            if (shown >= 250) break;
            shown++;
            totalRam += process.rssKb;
            list.addView(processCard(process));
        }

        String shownText = getResources().getQuantityString(R.plurals.xbs_shown_processes, shown, shown);
        String processText = getResources().getQuantityString(R.plurals.xbs_total_processes, snapshot.processes.size(), snapshot.processes.size());
        summary.setText(getString(R.string.xbs_process_summary,
            shownText,
            processText,
            ram(totalRam),
            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(snapshot.capturedAt))));
        if (shown == 0) {
            LinearLayout empty = ui.surface();
            empty.addView(ui.text("No matching processes", 17, ui.palette.text, true));
            list.addView(empty);
        }
    }

    private LinearLayout processCard(XbsProcess process) {
        LinearLayout card = ui.surface();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        try {
            Drawable drawable = getPackageManager().getApplicationIcon(process.packageName);
            icon.setImageDrawable(drawable);
        } catch (Exception ignored) {
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        top.addView(icon, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(ui.dp(11), 0, ui.dp(5), 0);
        labels.addView(ui.text(process.appLabel, 15, ui.palette.text, true));
        labels.addView(ui.text(process.packageName.isEmpty() ? "No Android package mapping" : process.packageName, 11, ui.palette.muted, false));
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (pickMode) {
            CheckBox check = new CheckBox(this);
            check.setChecked(selected.contains(process.processName));
            check.setOnCheckedChangeListener((button, on) -> {
                if (on) selected.add(process.processName);
                else selected.remove(process.processName);
            });
            top.addView(check);
            card.setOnClickListener(view -> check.setChecked(!check.isChecked()));
        }
        card.addView(top);
        card.addView(ui.text(process.processName, 14, ui.palette.orange, true));
        card.addView(ui.text(String.format(Locale.US, "CPU %.1f%% · RAM %s · nice %d", process.cpuPercent, process.ramLabel(), process.niceValue), 13, ui.palette.green, false));
        card.addView(ui.text("PID " + process.pid + " · PPID " + process.parentPid + " · UID " + process.uid, 11, ui.palette.muted, false));
        if (!process.ruleEvents.isEmpty()) card.addView(ui.text("XBS rules: " + XbsSnapshot.join(process.ruleEvents), 11, ui.palette.yellow, true));
        if (process.commandLineObserved && !process.command.equals(process.processName)) {
            card.addView(ui.text(process.command, 10, ui.palette.muted, false));
        }
        if (process.executableObserved && !process.executable.isEmpty()) {
            card.addView(ui.text("Executable: " + process.executable, 10, ui.palette.muted, false));
        }
        if (process.contextObserved && !process.selinuxContext.isEmpty()) {
            card.addView(ui.text("SELinux: " + process.selinuxContext, 10, ui.palette.muted, false));
        }
        return card;
    }

    private void checkRoot() {
        executor.execute(() -> {
            RootStatus status = XbsRootApi.checkRoot(this, true);
            runOnUiThread(() -> {
                Toast.makeText(this, status.summary(), Toast.LENGTH_LONG).show();
                refresh(true);
            });
        });
    }

    private void finishSelection() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least one process", Toast.LENGTH_SHORT).show();
            return;
        }
        int nice = 10;
        try { nice = Integer.parseInt(niceInput.getText().toString().trim()); }
        catch (Exception ignored) {}
        if (nice < -20 || nice > 19) {
            new AlertDialog.Builder(this)
                .setTitle("Invalid nice value")
                .setMessage("Linux nice values must be between -20 and 19.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        StringBuilder rules = new StringBuilder();
        for (String process : selected) rules.append(process).append('\t').append(nice).append('\n');
        Intent result = new Intent();
        result.putExtra(EXTRA_RULES, rules.toString());
        setResult(RESULT_OK, result);
        finish();
    }

    private static String ram(long kb) {
        if (kb >= 1048576L) return String.format(Locale.US, "%.2f GB", kb / 1048576.0);
        return String.format(Locale.US, "%.1f MB", kb / 1024.0);
    }
}
