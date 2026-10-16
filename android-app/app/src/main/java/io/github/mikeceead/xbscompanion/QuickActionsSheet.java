package io.github.mikeceead.xbscompanion;

import android.app.Activity;
import android.content.Intent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Compact, typed launch surface for the most common XBS operations. */
final class QuickActionsSheet {
    private QuickActionsSheet() {}

    static void show(Activity activity) {
        UiKit ui = new UiKit(activity);
        XbsSnapshot snapshot = XbsStateStore.current(activity);
        RootDiagnosticsReport diagnostics = RootDiagnosticsStore.cached(activity);
        XbsCapabilityRegistry capabilities = XbsCapabilityRegistry.cached(activity);
        XbsCompatibilityReport compatibility = capabilities.report;
        boolean commandsReady = (!diagnostics.isFresh(System.currentTimeMillis()) || diagnostics.xbsExecutable)
            && capabilities.contractAllowsExecution();

        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(24));
        content.setBackgroundColor(ui.palette.background);
        content.addView(ui.text("QUICK ACTIONS", 12, ui.palette.orange, true));
        content.addView(ui.text("XBS command deck", 24, ui.palette.text, true));
        content.addView(ui.text(snapshot.summary(), 13, ui.palette.muted, false));

        if (!commandsReady) {
            content.addView(ui.text("XBS executable or compatibility contract is unavailable. Open diagnostics before launching runtime actions.",
                13, ui.palette.yellow, true));
        } else if (!compatibility.isLive()) {
            content.addView(ui.text("Quick Actions are using the static fallback catalog. Refresh Root Diagnostics to negotiate live capabilities.",
                12, ui.palette.yellow, false));
        } else if (!diagnostics.isFresh(System.currentTimeMillis())) {
            content.addView(ui.text("Compatibility evidence is stale. Actions remain available, but Root Diagnostics should be refreshed.",
                12, ui.palette.yellow, false));
        }

        Button validate = ui.button("Validate configuration", ui.palette.green,
            view -> launch(activity, dialog, "validate", "", false));
        Button reload = ui.secondaryButton("Reload runtime",
            view -> launch(activity, dialog, "reload", "", false));
        Button pause = ui.secondaryButton(snapshot.paused ? "Resume runtime" : "Pause runtime",
            view -> launch(activity, dialog, snapshot.paused ? "resume" : "pause", "", false));
        Button safe = ui.secondaryButton(snapshot.safeMode ? "Exit safe mode" : "Enter safe mode",
            view -> launch(activity, dialog, snapshot.safeMode ? "safe_off" : "safe_on", "", false));
        validate.setEnabled(commandsReady);
        reload.setEnabled(commandsReady);
        pause.setEnabled(commandsReady);
        safe.setEnabled(commandsReady);
        content.addView(validate);
        content.addView(reload);
        content.addView(pause);
        content.addView(safe);

        content.addView(ui.sectionTitle("Events"));
        boolean manualActive = snapshot.active.contains("manual");
        Button manual = ui.secondaryButton(manualActive ? "Stop manual event" : "Start manual event",
            view -> launch(activity, dialog, manualActive ? "event_stop" : "event_run", "manual", manualActive));
        manual.setEnabled(commandsReady);
        content.addView(manual);
        content.addView(ui.secondaryButton("Open all event controls", view -> {
            dialog.dismiss();
            activity.startActivity(new Intent(activity, EventControlActivity.class));
        }));

        content.addView(ui.sectionTitle("Tools"));
        content.addView(ui.secondaryButton("Root Diagnostics", view -> {
            dialog.dismiss();
            activity.startActivity(new Intent(activity, RootDiagnosticsActivity.class));
        }));
        content.addView(ui.secondaryButton("Command Center", view -> {
            dialog.dismiss();
            activity.startActivity(new Intent(activity, CliCommandActivity.class));
        }));
        content.addView(ui.secondaryButton("Close", view -> dialog.dismiss()));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.show();
    }

    private static void launch(Activity activity, BottomSheetDialog sheet, String actionId,
                               String argument, boolean destructive) {
        XbsCapabilityRegistry capabilities = XbsCapabilityRegistry.cached(activity);
        RootDiagnosticsReport diagnostics = RootDiagnosticsStore.cached(activity);
        String commandId = commandId(actionId);
        CliCommand command = XbsCliCatalog.find(commandId);
        if (command != null) {
            CommandCenterPolicy.Descriptor descriptor = CommandCenterPolicy.describe(
                command, diagnostics, capabilities.report, System.currentTimeMillis());
            if (descriptor.availability == CommandCenterPolicy.Availability.BLOCKED) {
                Toast.makeText(activity, descriptor.availabilityLabel, Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (capabilities.negotiated() && !capabilities.supportsCommand(commandId)) {
            Toast.makeText(activity, "Installed module does not advertise this quick action", Toast.LENGTH_LONG).show();
            return;
        }
        SuiteAction action = SuiteCatalog.find("xbs", actionId);
        if (action == null) {
            Toast.makeText(activity, "Quick action is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        Runnable run = () -> {
            CommandLaunchResult result = RootCommandRunner.run(activity, action, argument, null, ExecutionSource.APP);
            Toast.makeText(activity, result.started ? "XBS action started" : result.error, Toast.LENGTH_SHORT).show();
            if (result.started) sheet.dismiss();
        };
        if (!destructive) {
            run.run();
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle("Stop manual event?")
            .setMessage("Only the manual event will be stopped. Other simultaneous XBS events remain active.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Stop event", (dialog, which) -> run.run())
            .show();
    }

    private static String commandId(String actionId) {
        if ("reload".equals(actionId)) return "runtime_reload";
        if ("pause".equals(actionId)) return "runtime_pause";
        if ("resume".equals(actionId)) return "runtime_resume";
        if ("safe_on".equals(actionId) || "safe_off".equals(actionId)) return "runtime_safe";
        if ("event_run".equals(actionId)) return "event_run";
        if ("event_stop".equals(actionId)) return "event_stop";
        return actionId;
    }
}
