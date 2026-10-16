package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stable, non-overlapping destination catalog for More and Settings. */
final class SettingsCatalog {
    private static final List<SettingsDestination> DESTINATIONS;

    static {
        List<SettingsDestination> values = new ArrayList<>();
        values.add(new SettingsDestination(SettingsDestination.RUNTIME, "Runtime monitoring",
            "Persistent event monitoring, startup behavior, polling, status detail, and live process sampling.",
            "monitor", "boot", "poll", "process", "foreground service", "runtime"));
        values.add(new SettingsDestination(SettingsDestination.NOTIFICATIONS, "Notifications",
            "Permission readiness, result alerts, event feedback, and direct notification-channel controls.",
            "permission", "channel", "toast", "alert", "completion", "status notification"));
        values.add(new SettingsDestination(SettingsDestination.APPEARANCE, "Appearance",
            "Dynamic color behavior and an immediate palette preview.",
            "dynamic color", "monet", "theme", "palette", "preview"));
        values.add(new SettingsDestination(SettingsDestination.TASKER, "Tasker",
            "Tasker event emission, state variables, and integration status.",
            "automation", "plugin", "variables", "events", "states"));
        values.add(new SettingsDestination(SettingsDestination.DATA, "Data and retention",
            "Room history retention, activity cleanup, metadata refresh, and frontend cache controls.",
            "history", "room", "retention", "cache", "clear", "storage", "activity"));
        values.add(new SettingsDestination(SettingsDestination.CONFIGURATION, "Configuration tools",
            "Native configuration, event, essentials, Command Center, and typed quick-action surfaces.",
            "config", "event editor", "essentials", "command center", "quick actions"));
        values.add(new SettingsDestination(SettingsDestination.DIAGNOSTICS, "Diagnostics",
            "Debug Workbench health, root diagnostics, release readiness, and support-bundle capture and sharing.",
            "debug workbench", "root", "release", "support bundle", "health", "readiness", "troubleshoot"));
        values.add(new SettingsDestination(SettingsDestination.ABOUT, "About",
            "Version, platform target, execution model, and release-train information.",
            "version", "build", "sdk", "license", "root native", "4.0"));
        DESTINATIONS = Collections.unmodifiableList(values);
    }

    private SettingsCatalog() {}

    static List<SettingsDestination> all() { return DESTINATIONS; }

    static SettingsDestination find(String id) {
        if (id == null) return null;
        for (SettingsDestination destination : DESTINATIONS) {
            if (destination.id.equals(id)) return destination;
        }
        return null;
    }

    static List<SettingsDestination> filter(String query) {
        List<SettingsDestination> matches = new ArrayList<>();
        for (SettingsDestination destination : DESTINATIONS) {
            if (destination.matches(query)) matches.add(destination);
        }
        return Collections.unmodifiableList(matches);
    }
}
