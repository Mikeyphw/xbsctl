package io.github.mikeceead.xbscompanion;

import java.util.Locale;

final class RunDetailFormatter {
    private RunDetailFormatter() {}

    static boolean isActive(RunRecord record) {
        return RunFilter.isActive(record);
    }

    static String statusLine(RunRecord record) {
        if (record == null) return "UNKNOWN";
        StringBuilder line = new StringBuilder(safe(record.status).toUpperCase(Locale.ROOT));
        if (!isActive(record)) line.append(" · exit ").append(record.exitCode);
        if (!safe(record.source).isEmpty()) line.append(" · ").append(record.source);
        if (!safe(record.mutationClass).isEmpty()) line.append(" · ").append(record.mutationClass);
        if (record.timeoutSeconds > 0) line.append(" · timeout ").append(record.timeoutSeconds).append('s');
        return line.toString();
    }

    static String cardMessage(RunRecord record) {
        String message = safe(record == null ? "" : record.message);
        if (message.isEmpty()) message = isActive(record) ? "Root command in progress" : "No command summary recorded";
        return statusLine(record) + "\n" + message;
    }

    static String outputSummary(RunRecord record) {
        boolean hasStdout = !safe(record == null ? "" : record.stdout).isEmpty();
        boolean hasStderr = !safe(record == null ? "" : record.stderr).isEmpty();
        boolean hasError = !safe(record == null ? "" : record.error).isEmpty();
        if (!hasStdout && !hasStderr && !hasError) return "No captured output yet";
        StringBuilder summary = new StringBuilder();
        if (hasStdout) summary.append("stdout");
        if (hasStderr) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append("stderr");
        }
        if (hasError) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append("error");
        }
        return "Captured " + summary;
    }

    static String detailBody(RunRecord record) {
        if (record == null) return "Run record was not found.";
        StringBuilder body = new StringBuilder();
        append(body, "Run", record.runId);
        append(body, "Status", statusLine(record));
        append(body, "Command", record.command);
        append(body, "Backend", record.backend);
        append(body, "Workdir", record.workdir);
        append(body, "Message", record.message);
        body.append("\nSTDOUT\n").append(block(record.stdout));
        body.append("\n\nSTDERR\n").append(block(record.stderr));
        body.append("\n\nERROR\n").append(block(record.error));
        return body.toString();
    }

    private static void append(StringBuilder body, String label, String value) {
        body.append(label).append(": ").append(safe(value).isEmpty() ? "-" : value).append('\n');
    }

    private static String block(String value) {
        String safe = safe(value);
        return safe.isEmpty() ? "-" : safe;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
