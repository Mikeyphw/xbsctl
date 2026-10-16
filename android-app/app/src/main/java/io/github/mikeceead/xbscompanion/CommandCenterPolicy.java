package io.github.mikeceead.xbscompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure policy for catalog availability, risk, expected effects, and bounded execution. */
final class CommandCenterPolicy {
    enum Availability { AVAILABLE, UNKNOWN, BLOCKED }
    enum Risk { READ_ONLY, STATE_CHANGE, DESTRUCTIVE }

    static final class Descriptor {
        final Availability availability;
        final Risk risk;
        final String availabilityLabel;
        final String guidance;
        final String expectedEffect;
        final int timeoutSeconds;
        final boolean confirmationRequired;

        Descriptor(Availability availability, Risk risk, String availabilityLabel, String guidance,
                   String expectedEffect, int timeoutSeconds, boolean confirmationRequired) {
            this.availability = availability;
            this.risk = risk;
            this.availabilityLabel = clean(availabilityLabel);
            this.guidance = clean(guidance);
            this.expectedEffect = clean(expectedEffect);
            this.timeoutSeconds = timeoutSeconds;
            this.confirmationRequired = confirmationRequired;
        }

        boolean enabled() { return availability != Availability.BLOCKED; }
        String riskLabel() {
            if (risk == Risk.DESTRUCTIVE) return "Destructive";
            if (risk == Risk.STATE_CHANGE) return "Changes runtime or files";
            return "Read only";
        }
    }

    private static final Set<String> READ_ONLY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "help", "status", "paths", "validate", "capabilities", "config_get", "config_validate", "config_schema",
        "event_list", "ctl_help", "ctl_active", "ctl_status", "ctl_detail", "ctl_validate",
        "schema_root", "schema_commands", "schema_command", "schema_output", "schema_config",
        "completion_zsh", "manpage_show"
    )));

    private static final Set<String> ANDROID_BLOCKED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "config_set", "config_edit_cli", "completion_install", "completion_complete_zsh", "manpage_install"
    )));

    private CommandCenterPolicy() {}

    static Descriptor describe(CliCommand command, RootDiagnosticsReport diagnostics, long now) {
        return describe(command, diagnostics, XbsCompatibilityReport.fallback(now, "Static catalog fallback"), now);
    }

    static Descriptor describe(CliCommand command, RootDiagnosticsReport diagnostics,
                               XbsCompatibilityReport compatibility, long now) {
        CliCommand allowlisted = requireAllowlisted(command == null ? "" : command.id);
        Risk risk = risk(allowlisted);
        int timeout = TimeoutPolicy.forMutation(mutation(allowlisted)).seconds;
        String effect = expectedEffect(allowlisted);

        if (!allowlisted.special.isEmpty()) {
            return new Descriptor(Availability.AVAILABLE, risk, "Native screen", "",
                effect, 0, false);
        }
        if (ANDROID_BLOCKED.contains(allowlisted.id)) {
            return new Descriptor(Availability.BLOCKED, risk, "Unavailable in Android",
                blockedGuidance(allowlisted.id), effect, timeout, false);
        }

        RootDiagnosticsReport report = diagnostics == null ? RootDiagnosticsReport.unchecked() : diagnostics;
        if (!report.isFresh(now)) {
            return new Descriptor(Availability.UNKNOWN, risk, "Compatibility not checked",
                "Run Root Diagnostics to verify the installed XBS executable before launching this action.",
                effect, timeout, risk == Risk.DESTRUCTIVE);
        }
        boolean executableReady = "XBSctl".equals(allowlisted.executable)
            ? report.xbsctlExecutable : report.xbsExecutable;
        if (!executableReady) {
            String binary = "XBSctl".equals(allowlisted.executable) ? "XBSctl" : "xbs";
            return new Descriptor(Availability.BLOCKED, risk, binary + " unavailable",
                "Install or enable the XtremeBS module, then rerun Root Diagnostics.",
                effect, timeout, false);
        }
        XbsCompatibilityReport contract = compatibility == null
            ? XbsCompatibilityReport.fallback(now, "Compatibility evidence is missing") : compatibility;
        if (contract.isLive() && !contract.compatible()) {
            return new Descriptor(Availability.BLOCKED, risk, "Incompatible module contract",
                contract.headline() + ". Open Root Diagnostics for version and schema evidence.",
                effect, timeout, false);
        }
        if (contract.isLive() && !contract.supportsCommand(allowlisted.id)) {
            return new Descriptor(Availability.BLOCKED, risk, "Not advertised by module",
                "The installed module capability contract does not publish this command. The app will not guess that it exists.",
                effect, timeout, false);
        }
        if (!contract.isLive() || !contract.isFresh(now)) {
            return new Descriptor(Availability.UNKNOWN, risk, "Static fallback catalog",
                "Live module capabilities are unavailable or stale. Refresh Root Diagnostics before relying on this action.",
                effect, timeout, risk == Risk.DESTRUCTIVE);
        }
        return new Descriptor(Availability.AVAILABLE, risk, "Advertised by module", "", effect, timeout,
            risk == Risk.DESTRUCTIVE);
    }

    static CliCommand requireAllowlisted(String id) {
        CliCommand command = XbsCliCatalog.find(id);
        if (command == null) throw new IllegalArgumentException("Command is not in the XBS catalog");
        return command;
    }

    static boolean isAndroidBlocked(String id) { return ANDROID_BLOCKED.contains(id); }

    static MutationClass mutation(CliCommand command) {
        Risk risk = risk(command);
        if (risk == Risk.READ_ONLY) return MutationClass.READ_ONLY;
        if (risk == Risk.DESTRUCTIVE) return MutationClass.DESTRUCTIVE;
        return MutationClass.STATE_CHANGE;
    }

    static Risk risk(CliCommand command) {
        if (command != null && command.destructive) return Risk.DESTRUCTIVE;
        if (command != null && READ_ONLY.contains(command.id)) return Risk.READ_ONLY;
        return Risk.STATE_CHANGE;
    }

    static String expectedEffect(CliCommand command) {
        if (command == null) return "No effect information is available.";
        switch (command.id) {
            case "help": return "Print the installed XBS help without changing runtime state.";
            case "status": case "ctl_status": return "Read the current runtime summary and active profiles.";
            case "paths": return "Read resolved configuration, list, log, and runtime paths.";
            case "capabilities": return "Read the installed module compatibility contract and schema digest.";
            case "validate": case "config_validate": case "ctl_validate": return "Validate configuration data without saving changes.";
            case "init": return "Create missing XBS configuration and integration assets.";
            case "config_get": return "Read configuration data without changing files.";
            case "config_set": return "Write one configuration value outside the app transaction workflow.";
            case "config_edit_cli": return "Open an external editor command, which Android intentionally does not expose.";
            case "config_native": return "Open the transaction-backed native configuration editor.";
            case "config_schema": case "schema_config": return "Read configuration schema metadata.";
            case "runtime_reload": case "ctl_reload": return "Reload the installed configuration into the running XBS daemon.";
            case "runtime_pause": case "ctl_pause": return "Pause automatic XBS processing while preserving configuration.";
            case "runtime_resume": case "ctl_resume": return "Resume XBS processing or leave safe mode.";
            case "runtime_safe": case "ctl_safe": return "Change safe-mode state for the running XBS daemon.";
            case "event_list": case "ctl_active": return "Read event catalog or active-event state.";
            case "event_run": case "ctl_start": return "Start one event profile without stopping other active profiles.";
            case "event_stop": case "ctl_stop": return "Stop the selected event profile while leaving unrelated profiles active.";
            case "event_manage": return "Open the native event manager and transaction-backed editor.";
            case "ctl_detail": return "Read detailed controller, CPU, Wi-Fi, Doze, and runtime evidence.";
            case "schema_root": case "schema_commands": case "schema_command": case "schema_output": return "Read published CLI schema metadata.";
            case "completion_zsh": return "Generate completion text without writing files.";
            case "completion_install": case "completion_complete_zsh": return "Perform shell-integration work that is not supported from Android.";
            case "manpage_show": return "Generate the XBS manpage without writing files.";
            case "manpage_install": return "Install shell documentation outside the Android app workflow.";
            default: return "Run the selected allowlisted XBS operation.";
        }
    }

    static String searchText(CliCommand command) {
        if (command == null) return "";
        return (command.id + " " + command.category + " " + command.title + " "
            + command.description + " " + command.executable + " "
            + String.join(" ", command.path) + " " + expectedEffect(command)).toLowerCase(Locale.ROOT);
    }

    static int runnableCount() {
        int count = 0;
        for (CliCommand command : XbsCliCatalog.all()) if (command.special.isEmpty() && !isAndroidBlocked(command.id)) count++;
        return count;
    }

    static int blockedCount() {
        int count = 0;
        for (CliCommand command : XbsCliCatalog.all()) if (isAndroidBlocked(command.id)) count++;
        return count;
    }

    private static String blockedGuidance(String id) {
        if ("config_set".equals(id) || "config_edit_cli".equals(id)) {
            return "Use the native Configuration editor so JSON and managed list files are validated, journaled, and rolled back as one transaction.";
        }
        if ("completion_install".equals(id) || "completion_complete_zsh".equals(id)) {
            return "Shell completion installation belongs in a terminal environment. The Android app keeps command execution root-native and non-interactive.";
        }
        if ("manpage_install".equals(id)) {
            return "Install the manpage from a terminal where the destination directory and ownership can be reviewed.";
        }
        return "This catalog command is not supported from Android.";
    }

    private static String clean(String value) {
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim().replaceAll(" +", " ");
        return text.length() <= 320 ? text : text.substring(0, 319) + "…";
    }
}
