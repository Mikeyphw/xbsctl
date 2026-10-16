package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.List;

/** Searchable settings hub embedded in the Settings bottom-navigation destination. */
final class SettingsHubView {
    private SettingsHubView() {}

    static void render(XbsActivity activity, UiKit ui, LinearLayout parent) {
        parent.addView(ui.sectionTitle("More and Settings"));
        LinearLayout intro = ui.surface();
        intro.addView(ui.text("One home for every app preference", 19, ui.palette.orange, true));
        intro.addView(ui.text("Search by task or open one of eight non-overlapping destinations. Controls live in exactly one section, while cards show their current state immediately.", 13, ui.palette.muted, false));
        EditText search = ui.input("", "Search settings, tools, and permissions", false);
        intro.addView(search);
        parent.addView(intro);

        LinearLayout results = new LinearLayout(activity);
        results.setOrientation(LinearLayout.VERTICAL);
        parent.addView(results);
        renderResults(activity, ui, results, "");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                renderResults(activity, ui, results, SettingsDestination.boundedQuery(value == null ? "" : value.toString()));
            }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    private static void renderResults(XbsActivity activity, UiKit ui, LinearLayout results, String query) {
        results.removeAllViews();
        List<SettingsDestination> matches = SettingsCatalog.filter(SettingsDestination.boundedQuery(query));
        if (matches.isEmpty()) {
            LinearLayout empty = ui.surface();
            empty.addView(ui.text("No matching setting", 17, ui.palette.text, true));
            empty.addView(ui.text("Try monitor, notification, Tasker, cache, configuration, diagnostics, or version.", 13, ui.palette.muted, false));
            results.addView(empty);
            return;
        }
        for (SettingsDestination destination : matches) {
            LinearLayout card = ui.surface();
            card.addView(ui.text(destination.title, 17, ui.palette.text, true));
            card.addView(ui.text(summary(activity, destination.id), 13, summaryColor(activity, ui, destination.id), true));
            card.addView(ui.text(destination.description, 12, ui.palette.muted, false));
            card.addView(ui.secondaryButton("Open " + destination.title, view -> MoreSettingsActivity.open(activity, destination.id)));
            results.addView(card);
        }
    }

    static String summary(Context context, String id) {
        if (SettingsDestination.RUNTIME.equals(id)) {
            return (AppSettings.monitorEnabled(context) ? "Monitor on" : "Monitor off")
                + " · " + AppSettings.pollSeconds(context) + "s polling"
                + " · boot " + (AppSettings.startOnBoot(context) ? "on" : "off");
        }
        if (SettingsDestination.NOTIFICATIONS.equals(id)) {
            NotificationController.Readiness readiness = NotificationController.readiness(context);
            String ready = readiness.permissionGranted && readiness.appEnabled ? "App notifications ready" : "Notifications need attention";
            return ready + " · " + AppSettings.commandResultNotificationModeLabel(context);
        }
        if (SettingsDestination.APPEARANCE.equals(id)) {
            return AppSettings.useMonet(context) && Build.VERSION.SDK_INT >= 31
                ? "Dynamic system palette active" : AppSettings.useMonet(context)
                ? "Dynamic colors requested; fallback palette active" : "Built-in XBS palette active";
        }
        if (SettingsDestination.TASKER.equals(id)) {
            return AppSettings.taskerEventsEnabled(context) ? "Event and state updates enabled" : "Tasker updates disabled";
        }
        if (SettingsDestination.DATA.equals(id)) {
            XbsHistoryStatus status = XbsHistoryStore.status(context);
            return "Keep " + AppSettings.historyRetention(context) + " runs · " + status.runCount + " stored";
        }
        if (SettingsDestination.CONFIGURATION.equals(id)) return "Native editors and typed command surfaces";
        if (SettingsDestination.DIAGNOSTICS.equals(id)) {
            DebugHealthReport health = DebugHealthStore.cached(context);
            if (!health.evaluated()) return "Debug health not evaluated";
            return health.blockerCount() > 0 ? health.blockerCount() + " health blocker(s)"
                : health.warningCount() > 0 ? health.warningCount() + " health warning(s)"
                : health.unknownCount() > 0 ? health.unknownCount() + " unknown health check(s)"
                : "Debug health checks passing";
        }
        return "XBS Companion " + BuildConfig.VERSION_NAME + " · target SDK " + context.getApplicationInfo().targetSdkVersion;
    }

    private static int summaryColor(Context context, UiKit ui, String id) {
        if (SettingsDestination.NOTIFICATIONS.equals(id)) {
            NotificationController.Readiness readiness = NotificationController.readiness(context);
            return readiness.permissionGranted && readiness.appEnabled ? ui.palette.green : ui.palette.yellow;
        }
        if (SettingsDestination.DIAGNOSTICS.equals(id)) {
            DebugHealthReport health = DebugHealthStore.cached(context);
            if (!health.evaluated() || health.unknownCount() > 0) return ui.palette.cyan;
            return health.blockerCount() > 0 ? ui.palette.red
                : health.warningCount() > 0 ? ui.palette.yellow : ui.palette.green;
        }
        return ui.palette.cyan;
    }
}
