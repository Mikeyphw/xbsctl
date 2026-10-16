package io.github.mikeceead.xbscompanion;

import android.content.Intent;
import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Searchable, schema-aware command catalog with immutable generated previews. */
@SuppressLint("SetTextI18n")
public final class CliCommandActivity extends XbsActivity {
    private static final String STATE_QUERY = "command_center_query";
    private static final String STATE_CATEGORY = "command_center_category";
    private static final String ALL = "All groups";
    private static final int MAX_SEARCH_CHARS = 160;
    private static final int MAX_SHOWN_COMMANDS = 120;

    private UiKit ui;
    private LinearLayout list;
    private String query = "";
    private String category = ALL;
    private RootDiagnosticsReport diagnostics = RootDiagnosticsReport.unchecked();
    private XbsCapabilityRegistry capabilities = XbsCapabilityRegistry.of(
        XbsCompatibilityReport.unavailable("Not loaded"));

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            query = state.getString(STATE_QUERY, "");
            category = state.getString(STATE_CATEGORY, ALL);
        }
        diagnostics = RootDiagnosticsStore.cached(this);
        capabilities = XbsCapabilityRegistry.cached(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        diagnostics = RootDiagnosticsStore.cached(this);
        capabilities = XbsCapabilityRegistry.cached(this);
        populate();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_QUERY, query);
        outState.putString(STATE_CATEGORY, category);
        super.onSaveInstanceState(outState);
    }

    private void render() {
        ui = new UiKit(this);
        getWindow().setStatusBarColor(ui.palette.background);
        getWindow().setNavigationBarColor(ui.palette.background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.palette.background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(10));
        header.addView(ui.text("XBS COMMAND CENTER", 12, ui.palette.orange, true));
        header.addView(ui.text("Typed action catalog", 28, ui.palette.text, true));
        header.addView(ui.text("Search the complete supported CLI surface. Commands show risk, expected effect, timeout, compatibility, and an immutable generated preview.",
            13, ui.palette.muted, false));
        header.addView(ui.text(CommandCenterPolicy.runnableCount() + " runnable · "
            + CommandCenterPolicy.blockedCount() + " intentionally unavailable in Android",
            12, ui.palette.cyan, true));
        header.addView(ui.text(capabilities.summary() + " · " + capabilities.report.commandIds.size() + " advertised commands",
            12, capabilities.report.compatible() ? ui.palette.green : ui.palette.yellow, true));

        EditText search = ui.input(query, "Search commands, effects, and paths", false);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                query = boundedSearch(text == null ? "" : text.toString());
                populate();
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        header.addView(search);

        List<String> categories = new ArrayList<>();
        categories.add(ALL);
        categories.addAll(XbsCliCatalog.categories());
        Spinner group = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categories);
        group.setAdapter(adapter);
        int selected = Math.max(0, categories.indexOf(category));
        group.setSelection(selected);
        group.setBackground(ui.flat(ui.palette.surfaceRaised, 10));
        group.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        group.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                category = categories.get(position);
                populate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(-1, ui.dp(52));
        groupParams.setMargins(0, ui.dp(8), 0, 0);
        header.addView(group, groupParams);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(10));
        footer.addView(ui.secondaryButton("Back to XBS", view -> finish()));
        root.addView(footer);
        setXbsContentView(root);
        populate();
    }

    private void populate() {
        if (list == null || ui == null) return;
        list.removeAllViews();
        String needle = boundedSearch(query).toLowerCase(Locale.ROOT);
        String lastCategory = "";
        int shown = 0;
        long now = System.currentTimeMillis();
        for (CliCommand command : XbsCliCatalog.all()) {
            if (!ALL.equals(category) && !category.equals(command.category)) continue;
            if (!needle.isEmpty() && !CommandCenterPolicy.searchText(command).contains(needle)) continue;
            CommandCenterPolicy.Descriptor descriptor = CommandCenterPolicy.describe(command, diagnostics, capabilities.report, now);
            if (!command.category.equals(lastCategory)) {
                list.addView(ui.sectionTitle(command.category));
                lastCategory = command.category;
            }
            list.addView(commandCard(command, descriptor));
            shown++;
            if (shown >= MAX_SHOWN_COMMANDS) break;
        }
        if (shown == 0) {
            LinearLayout empty = ui.surface();
            empty.addView(ui.text("No matching actions", 17, ui.palette.text, true));
            empty.addView(ui.text("Try another search or select All groups.", 13, ui.palette.muted, false));
            list.addView(empty);
        }
    }

    private View commandCard(CliCommand command, CommandCenterPolicy.Descriptor descriptor) {
        LinearLayout card = ui.surface();
        int titleColor = descriptor.risk == CommandCenterPolicy.Risk.DESTRUCTIVE
            ? ui.palette.red : descriptor.enabled() ? ui.palette.text : ui.palette.muted;
        card.addView(ui.text(command.title, 18, titleColor, true));
        card.addView(ui.text(command.description, 13, ui.palette.muted, false));
        String path = command.isNative() ? "Native Android workflow" : command.executable + " " + String.join(" ", command.path);
        card.addView(ui.text(path.trim(), 12, ui.palette.cyan, false));
        int statusColor = descriptor.availability == CommandCenterPolicy.Availability.BLOCKED
            ? ui.palette.red : descriptor.availability == CommandCenterPolicy.Availability.UNKNOWN
            ? ui.palette.yellow : ui.palette.green;
        card.addView(ui.text(descriptor.availabilityLabel + " · " + descriptor.riskLabel()
            + (descriptor.timeoutSeconds > 0 ? " · " + descriptor.timeoutSeconds + "s timeout" : ""),
            12, statusColor, true));
        card.addView(ui.text(descriptor.expectedEffect, 12, ui.palette.muted, false));
        if (!descriptor.guidance.isEmpty()) card.addView(ui.text(descriptor.guidance, 12, ui.palette.yellow, false));
        card.setAlpha(descriptor.enabled() ? 1f : 0.66f);
        card.setOnClickListener(view -> {
            if (!descriptor.enabled()) showCompatibility(command, descriptor);
            else open(command, descriptor);
        });
        return card;
    }

    private void open(CliCommand command, CommandCenterPolicy.Descriptor descriptor) {
        if (command.isNative()) {
            openNative(command.special);
            return;
        }
        showCommandForm(command, descriptor);
    }

    private void showCommandForm(CliCommand command, CommandCenterPolicy.Descriptor descriptor) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(24));
        content.setBackgroundColor(ui.palette.background);
        content.addView(ui.text(command.category.toUpperCase(Locale.ROOT), 12, ui.palette.orange, true));
        content.addView(ui.text(command.title, 24, ui.palette.text, true));
        content.addView(ui.text(command.description, 13, ui.palette.muted, false));
        int riskColor = descriptor.risk == CommandCenterPolicy.Risk.DESTRUCTIVE
            ? ui.palette.red : descriptor.risk == CommandCenterPolicy.Risk.STATE_CHANGE
            ? ui.palette.yellow : ui.palette.green;
        content.addView(ui.text(descriptor.riskLabel() + " · timeout " + descriptor.timeoutSeconds + "s",
            13, riskColor, true));
        content.addView(ui.text("Expected effect: " + descriptor.expectedEffect, 13, ui.palette.muted, false));
        if (!descriptor.guidance.isEmpty()) content.addView(ui.text(descriptor.guidance, 12, ui.palette.yellow, false));

        Map<String,View> controls = new LinkedHashMap<>();
        for (CliField field : command.fields) addField(content, controls, field);

        content.addView(ui.sectionTitle("Generated command"));
        TextView preview = ui.text("", 12, ui.palette.cyan, false);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        preview.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        preview.setBackground(ui.flat(ui.palette.surfaceRaised, 10));
        content.addView(preview);
        content.addView(ui.text("Preview is generated from the allowlisted schema and cannot be edited.",
            11, ui.palette.muted, false));

        Button run = ui.button(descriptor.confirmationRequired ? "Review destructive action" : "Run action",
            descriptor.confirmationRequired ? ui.palette.red : ui.palette.green, null);
        run.setOnClickListener(view -> {
            Map<String,String> values = values(command, controls);
            final String generated;
            try {
                generated = command.build(values);
                XbsCommandSpec.fromCatalog(command, values, ExecutionSource.APP);
            } catch (IllegalArgumentException error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                refreshPreview(command, controls, preview, run);
                return;
            }
            Runnable launch = () -> {
                CommandLaunchResult result = RootCommandRunner.runCatalog(this, command, values, ExecutionSource.APP);
                Toast.makeText(this, result.started ? "Command started" : result.error, Toast.LENGTH_SHORT).show();
                if (result.started) dialog.dismiss();
            };
            if (descriptor.confirmationRequired) confirmDestructive(command, descriptor, generated, launch);
            else launch.run();
        });
        content.addView(run);
        content.addView(ui.secondaryButton("Cancel", view -> dialog.dismiss()));

        attachPreviewListeners(command, controls, preview, run);
        refreshPreview(command, controls, preview, run);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void addField(LinearLayout content, Map<String,View> controls, CliField field) {
        TextView label = ui.text(field.label + (field.required ? " *" : ""), 13, ui.palette.muted, true);
        label.setPadding(0, ui.dp(10), 0, 0);
        content.addView(label);
        View control;
        if (field.kind == CliField.Kind.BOOLEAN) {
            SwitchCompat toggle = ui.toggle("Enable", "true".equalsIgnoreCase(field.defaultValue));
            control = toggle;
            content.addView(toggle);
        } else if (field.kind == CliField.Kind.CHOICE) {
            Spinner spinner = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, field.choices);
            spinner.setAdapter(adapter);
            int index = 0;
            for (int i = 0; i < field.choices.length; i++) if (field.choices[i].equals(field.defaultValue)) index = i;
            spinner.setSelection(index);
            spinner.setBackground(ui.flat(ui.palette.surfaceRaised, 10));
            spinner.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
            content.addView(spinner, new LinearLayout.LayoutParams(-1, ui.dp(52)));
            control = spinner;
        } else {
            EditText input = ui.input(field.defaultValue, field.hint,
                field.kind == CliField.Kind.MULTILINE || field.kind == CliField.Kind.WORDS);
            if (field.kind == CliField.Kind.INTEGER) {
                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            }
            content.addView(input);
            control = input;
        }
        controls.put(field.name, control);
    }

    private void attachPreviewListeners(CliCommand command, Map<String,View> controls, TextView preview, Button run) {
        for (View control : controls.values()) {
            if (control instanceof EditText) {
                ((EditText) control).addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                        refreshPreview(command, controls, preview, run);
                    }
                    @Override public void afterTextChanged(Editable editable) {}
                });
            } else if (control instanceof SwitchCompat) {
                ((SwitchCompat) control).setOnCheckedChangeListener((button, checked) ->
                    refreshPreview(command, controls, preview, run));
            } else if (control instanceof Spinner) {
                ((Spinner) control).setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        refreshPreview(command, controls, preview, run);
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
            }
        }
    }

    private void refreshPreview(CliCommand command, Map<String,View> controls, TextView preview, Button run) {
        try {
            String generated = command.build(values(command, controls));
            preview.setText(generated);
            preview.setTextColor(ui.palette.cyan);
            run.setEnabled(true);
        } catch (IllegalArgumentException error) {
            preview.setText("Needs input: " + error.getMessage());
            preview.setTextColor(ui.palette.red);
            run.setEnabled(false);
        }
    }

    private static String boundedSearch(String value) {
        String clean = value == null ? "" : value.trim().replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ");
        return clean.length() <= MAX_SEARCH_CHARS ? clean : clean.substring(0, MAX_SEARCH_CHARS);
    }

    private Map<String,String> values(CliCommand command, Map<String,View> controls) {
        Map<String,String> values = new LinkedHashMap<>();
        for (CliField field : command.fields) {
            View control = controls.get(field.name);
            String value = "";
            if (control instanceof EditText) value = ((EditText) control).getText().toString();
            else if (control instanceof SwitchCompat) value = ((SwitchCompat) control).isChecked() ? "true" : "false";
            else if (control instanceof Spinner && ((Spinner) control).getSelectedItem() != null) {
                value = ((Spinner) control).getSelectedItem().toString();
            }
            values.put(field.name, value);
        }
        return values;
    }

    private void confirmDestructive(CliCommand command, CommandCenterPolicy.Descriptor descriptor,
                                    String generated, Runnable launch) {
        new AlertDialog.Builder(this)
            .setTitle(command.title + "?")
            .setMessage(descriptor.expectedEffect + "\n\nTimeout: " + descriptor.timeoutSeconds
                + " seconds\n\nGenerated command:\n" + generated)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run destructive action", (dialog, which) -> launch.run())
            .show();
    }

    private void showCompatibility(CliCommand command, CommandCenterPolicy.Descriptor descriptor) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(descriptor.availabilityLabel)
            .setMessage(descriptor.guidance)
            .setNegativeButton("Close", null);
        if ("config_set".equals(command.id) || "config_edit_cli".equals(command.id)) {
            builder.setPositiveButton("Open native editor", (dialog, which) ->
                startActivity(new Intent(this, XbsConfigActivity.class)));
        } else {
            builder.setPositiveButton("Open diagnostics", (dialog, which) ->
                startActivity(new Intent(this, RootDiagnosticsActivity.class)));
        }
        builder.show();
    }

    private void openNative(String special) {
        if ("config".equals(special)) startActivity(new Intent(this, XbsConfigActivity.class));
        else if ("events".equals(special)) startActivity(new Intent(this, XbsConfigActivity.class).putExtra("events_only", true));
        else Toast.makeText(this, "Native destination is unavailable", Toast.LENGTH_LONG).show();
    }
}
