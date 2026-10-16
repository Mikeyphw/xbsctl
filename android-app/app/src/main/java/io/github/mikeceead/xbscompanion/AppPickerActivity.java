package io.github.mikeceead.xbscompanion;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppPickerActivity extends XbsActivity {
    static final String EXTRA_SELECTED = "selected_packages";
    static final String EXTRA_TITLE = "title";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new LinkedHashSet<>();
    private UiKit ui;
    private LinearLayout list;
    private TextView summary;
    private EditText search;
    private SwitchCompat showUser, showSystem;
    private List<InstalledApp> apps = new ArrayList<>();
    private int visibleCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ArrayList<String> initial = getIntent().getStringArrayListExtra(EXTRA_SELECTED);
        if (initial != null) selected.addAll(initial);
        build();
        load(false);
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
        root.setOnApplyWindowInsetsListener((v, i) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets insets = i.getInsets(WindowInsets.Type.systemBars());
                top = insets.top; bottom = insets.bottom;
            } else {
                top = i.getSystemWindowInsetTop(); bottom = i.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return i;
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(8));
        head.addView(ui.text("XBS APP PICKER", 12, ui.palette.orange, true));
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        head.addView(ui.text(title == null ? "Select applications" : title, 27, ui.palette.text, true));
        summary = ui.text("Loading installed applications…", 12, ui.palette.muted, false);
        head.addView(summary);
        search = ui.input("", "Search app or package", false);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { render(); }
            public void afterTextChanged(Editable e) {}
        });
        head.addView(search);

        LinearLayout filters = ui.surface();
        filters.addView(ui.text("Application type", 13, ui.palette.muted, true));
        showUser = ui.toggle("User apps", true);
        showSystem = ui.toggle("System apps", true);
        showUser.setOnCheckedChangeListener((button, checked) -> ensureOneFilterAndRender(button));
        showSystem.setOnCheckedChangeListener((button, checked) -> ensureOneFilterAndRender(button));
        filters.addView(showUser);
        filters.addView(showSystem);
        LinearLayout filterActions = new LinearLayout(this);
        filterActions.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button selectVisible = ui.secondaryButton("Select shown", v -> selectVisible());
        android.widget.Button clear = ui.secondaryButton("Clear selected", v -> { selected.clear(); render(); });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMargins(0, 0, ui.dp(4), 0);
        filterActions.addView(selectVisible, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.setMargins(ui.dp(4), 0, 0, 0);
        filterActions.addView(clear, right);
        filters.addView(filterActions);
        filters.addView(ui.secondaryButton("Refresh installed apps", v -> load(true)));
        head.addView(filters);
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(20));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(ui.dp(14), ui.dp(5), ui.dp(14), ui.dp(10));
        android.widget.Button cancel = ui.secondaryButton("Cancel", v -> finish());
        android.widget.Button done = ui.button("Use selected", ui.palette.green, v -> finishSelection());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.setMargins(0, 0, ui.dp(4), 0);
        footer.addView(cancel, p);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1);
        p2.setMargins(ui.dp(4), 0, 0, 0);
        footer.addView(done, p2);
        root.addView(footer);
        setXbsContentView(root);
    }

    private void ensureOneFilterAndRender(android.widget.CompoundButton changed) {
        if (!showUser.isChecked() && !showSystem.isChecked()) {
            changed.setChecked(true);
            Toast.makeText(this, "At least one app type must remain visible", Toast.LENGTH_SHORT).show();
            return;
        }
        render();
    }

    private void load(boolean force) {
        if (summary != null) summary.setText(force ? R.string.xbs_loading_apps_refresh : R.string.xbs_loading_apps);
        executor.execute(() -> {
            List<InstalledApp> result = AppCatalog.load(this, force);
            runOnUiThread(() -> { apps = result; render(); });
        });
    }

    private boolean visible(InstalledApp app, String query) {
        if (app.system && !showSystem.isChecked()) return false;
        if (!app.system && !showUser.isChecked()) return false;
        return query.isEmpty()
            || app.label.toLowerCase(Locale.ROOT).contains(query)
            || app.packageName.toLowerCase(Locale.ROOT).contains(query);
    }

    private void render() {
        if (list == null || showUser == null || showSystem == null) return;
        list.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0, shownUser = 0, shownSystem = 0;
        for (InstalledApp app : apps) {
            if (!visible(app, query)) continue;
            shown++;
            if (app.system) shownSystem++; else shownUser++;
            LinearLayout row = ui.surface();
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(this);
            try {
                Drawable drawable = getPackageManager().getApplicationIcon(app.packageName);
                icon.setImageDrawable(drawable);
            } catch (Exception ignored) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            row.addView(icon, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(ui.dp(12), 0, ui.dp(8), 0);
            labels.addView(ui.text(app.label, 15, ui.palette.text, true));
            labels.addView(ui.text(app.packageName + " · UID " + app.uid + (app.system ? " · System" : " · User"), 11, ui.palette.muted, false));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            CheckBox check = new CheckBox(this);
            check.setChecked(selected.contains(app.packageName));
            check.setOnCheckedChangeListener((button, on) -> {
                if (on) selected.add(app.packageName); else selected.remove(app.packageName);
                updateSummary(visibleCount, -1, -1);
            });
            row.addView(check);
            row.setOnClickListener(v -> check.setChecked(!check.isChecked()));
            list.addView(row);
        }
        visibleCount = shown;
        updateSummary(shown, shownUser, shownSystem);
        if (shown == 0) {
            LinearLayout empty = ui.surface();
            empty.addView(ui.text("No matching applications", 16, ui.palette.text, true));
            empty.addView(ui.text("Change the search or enable both User apps and System apps.", 12, ui.palette.muted, false));
            list.addView(empty);
        }
    }

    private void selectVisible() {
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (InstalledApp app : apps) if (visible(app, query)) selected.add(app.packageName);
        render();
    }

    private void updateSummary(int shown, int user, int system) {
        if (summary == null) return;
        String cachedText = getResources().getQuantityString(R.plurals.xbs_cached_apps, apps.size(), apps.size());
        String selectedText = getResources().getQuantityString(R.plurals.xbs_selected_apps, selected.size(), selected.size());
        String shownText = getResources().getQuantityString(R.plurals.xbs_shown_apps, shown, shown);
        String detail = "";
        if (user >= 0) {
            String userText = getResources().getQuantityString(R.plurals.xbs_user_apps, user, user);
            String systemText = getResources().getQuantityString(R.plurals.xbs_system_apps, system, system);
            detail = getString(R.string.xbs_app_picker_summary_detail, userText, systemText);
        }
        summary.setText(getString(R.string.xbs_app_picker_summary, cachedText, selectedText, shownText, detail));
    }

    private void finishSelection() {
        Intent result = new Intent();
        result.putStringArrayListExtra(EXTRA_SELECTED, new ArrayList<>(selected));
        setResult(RESULT_OK, result);
        finish();
    }
}
