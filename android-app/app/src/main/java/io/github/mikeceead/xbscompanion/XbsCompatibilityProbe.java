package io.github.mikeceead.xbscompanion;

import android.content.Context;

/** Executes the module-published capability command and degrades explicitly to the static catalog. */
final class XbsCompatibilityProbe {
    private XbsCompatibilityProbe() {}

    static XbsCompatibilityReport collect(Context context, boolean forceRoot) {
        Context app = context.getApplicationContext();
        XbsCompatibilityReport cached = XbsCompatibilityStore.cached(app);
        if (!forceRoot && cached.isLive() && cached.isFresh(System.currentTimeMillis())) return cached;
        RootStatus root = XbsRootApi.checkRoot(app, forceRoot);
        if (!root.granted) return fallback(app, root.message);
        XbsRootApi.CommandOutput output = XbsRootApi.runRoot(command());
        if (output.exitCode != 0) {
            String error = output.error.isEmpty() ? output.stdout : output.error;
            return fallback(app, "Installed module does not publish compatibility evidence: " + oneLine(error));
        }
        XbsCompatibilityReport report = XbsCompatibilityReport.parse(output.stdout, System.currentTimeMillis());
        if (!report.isLive()) return fallback(app, report.error);
        XbsCompatibilityStore.save(app, report);
        return report;
    }

    private static XbsCompatibilityReport fallback(Context context, String reason) {
        XbsCompatibilityReport report = XbsCompatibilityReport.fallback(System.currentTimeMillis(), oneLine(reason));
        XbsCompatibilityStore.save(context, report);
        return report;
    }

    private static String command() {
        return XbsRuntimeResolver.xbsJsonError("--json capabilities", "xbs.capabilities",
            "xbs executable not found", "Install or enable the XtremeBS module");
    }

    private static String oneLine(String value) {
        String cleaned = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim().replaceAll(" +", " ");
        return cleaned.length() <= 240 ? cleaned : cleaned.substring(0, 239) + "…";
    }
}
