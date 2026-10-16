package io.github.mikeceead.xbscompanion;

/** Shared root-runtime discovery for the repo-built XtremeBS Magisk/KSU module. */
final class XbsRuntimeResolver {
    static final String MODULE_ID = "XtremeBS";
    private static final String[] MODULE_ROOTS = new String[] {
        "/data/adb/modules/" + MODULE_ID,
        "/data/adb/modules_update/" + MODULE_ID,
        "/data/adb/ksu/modules/" + MODULE_ID,
        "/data/adb/ksu/modules_update/" + MODULE_ID
    };
    private static final String[] SYSTEM_BIN_FALLBACKS = new String[] {
        "/system/bin"
    };

    private XbsRuntimeResolver() {}

    static String resolveXbs(String variable) { return resolveExecutable(variable, "xbs"); }
    static String resolveXbsCtl(String variable) { return resolveExecutable(variable, "XBSctl"); }
    static String resolveXtremeBSd(String variable) { return resolveExecutable(variable, "XtremeBSd"); }
    static String resolveDefaultEssentials(String variable) { return resolveModuleFile(variable, "defaults/essentials.allowlist"); }

    static String xbs(String args) { return requireExecutable("XBS_BIN", "xbs", args, "xbs executable not found"); }
    static String xbsCtl(String args) { return requireExecutable("XBSCTL_BIN", "XBSctl", args, "XBSctl executable not found"); }

    static String xbsJsonError(String args, String action, String message, String error) {
        return requireExecutableJsonError("XBS_BIN", "xbs", args, action, message, error);
    }

    static String xbsCtlJsonError(String args, String action, String message, String error) {
        return requireExecutableJsonError("XBSCTL_BIN", "XBSctl", args, action, message, error);
    }

    static String availability(String variable, String... executables) {
        StringBuilder shell = new StringBuilder(variable).append("=false; ");
        for (String executable : executables) {
            String pathVar = variable + "_" + executable.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(java.util.Locale.US);
            shell.append(resolveExecutable(pathVar, executable))
                .append("if [ -n \"$").append(pathVar).append("\" ]; then ")
                .append(variable).append("=true; fi; ");
        }
        return shell.toString();
    }

    static String probeCalls(String functionName, String executable) {
        StringBuilder shell = new StringBuilder();
        for (String path : executablePaths(executable)) {
            shell.append(functionName).append(' ').append(q(path)).append("; ");
        }
        return shell.toString();
    }

    private static String requireExecutable(String variable, String executable, String args, String missingMessage) {
        return resolveExecutable(variable, executable)
            + "if [ -n \"$" + variable + "\" ]; then \"$" + variable + "\" " + normalizeArgs(args)
            + "; else printf '" + escapeSingle(missingMessage) + "\\n' >&2; exit 127; fi";
    }

    private static String requireExecutableJsonError(String variable, String executable, String args,
                                                     String action, String message, String error) {
        return resolveExecutable(variable, executable)
            + "if [ -n \"$" + variable + "\" ]; then \"$" + variable + "\" " + normalizeArgs(args)
            + "; else printf '%s\\n' '"
            + "{\"ok\":false,\"action\":\"" + json(action) + "\",\"message\":\"" + json(message)
            + "\",\"error\":\"" + json(error) + "\"}'"
            + "; exit 127; fi";
    }

    private static String resolveExecutable(String variable, String executable) {
        return resolveFromPaths(variable, executablePaths(executable), true);
    }

    private static String resolveModuleFile(String variable, String relative) {
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        for (String root : MODULE_ROOTS) paths.add(root + "/" + relative);
        return resolveFromPaths(variable, paths, false);
    }

    private static java.util.List<String> executablePaths(String executable) {
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        for (String root : MODULE_ROOTS) paths.add(root + "/system/bin/" + executable);
        for (String root : SYSTEM_BIN_FALLBACKS) paths.add(root + "/" + executable);
        return paths;
    }

    private static String resolveFromPaths(String variable, java.util.List<String> paths, boolean executableOnly) {
        StringBuilder shell = new StringBuilder(variable).append("=''; for P in");
        for (String path : paths) shell.append(' ').append(q(path));
        shell.append("; do ");
        shell.append(executableOnly ? "[ -x \"$P\" ]" : "[ -e \"$P\" ]");
        shell.append(" && ").append(variable).append("=\"$P\" && break; done; ");
        return shell.toString();
    }

    private static String normalizeArgs(String args) {
        return args == null ? "" : args.trim();
    }

    private static String q(String value) { return "'" + escapeSingle(value == null ? "" : value) + "'"; }
    private static String escapeSingle(String value) { return value.replace("'", "'\\''"); }
    private static String json(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
