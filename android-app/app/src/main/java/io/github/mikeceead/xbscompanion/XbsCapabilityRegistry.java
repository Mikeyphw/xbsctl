package io.github.mikeceead.xbscompanion;

import android.content.Context;

/** Single capability decision surface shared by commands, editors, diagnostics, and fixes. */
final class XbsCapabilityRegistry {
    final XbsCompatibilityReport report;

    private XbsCapabilityRegistry(XbsCompatibilityReport report) {
        this.report = report == null
            ? XbsCompatibilityReport.unavailable("Compatibility evidence is missing") : report;
    }

    static XbsCapabilityRegistry cached(Context context) {
        return new XbsCapabilityRegistry(XbsCompatibilityStore.cached(context));
    }

    static XbsCapabilityRegistry of(XbsCompatibilityReport report) {
        return new XbsCapabilityRegistry(report);
    }

    boolean negotiated() { return report.isLive(); }
    boolean contractAllowsExecution() { return !report.isLive() || report.compatible(); }

    boolean supportsCommand(String commandId) {
        if (commandId == null || commandId.trim().isEmpty()) return false;
        if (report.isLive()) return report.compatible() && report.supportsCommand(commandId);
        CliCommand fallback = XbsCliCatalog.find(commandId);
        return fallback != null && !fallback.isNative();
    }

    boolean supportsCommand(CliCommand command) {
        return command != null && (command.isNative() || supportsCommand(command.id));
    }

    boolean canWriteEventField(String field) {
        if (field == null || field.trim().isEmpty()) return false;
        return !report.isLive() || (report.compatible() && report.supportsEventField(field));
    }

    String summary() { return report.headline() + " · " + report.compactSummary(); }
}
