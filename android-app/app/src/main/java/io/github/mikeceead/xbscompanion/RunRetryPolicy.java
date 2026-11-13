package io.github.mikeceead.xbscompanion;

/** Retry guard for commands replayed from persisted run history. */
final class RunRetryPolicy {
    private static final int MAX_PREVIEW_CHARS = 280;

    private RunRetryPolicy() {}

    static boolean canRetry(RunRecord record) {
        return evaluate(record).allowed;
    }

    static Decision evaluate(RunRecord record) {
        if (record == null) return Decision.blocked("Run record is missing");
        if (RunFilter.isActive(record)) return Decision.blocked("Active commands cannot be retried");
        if ("pending".equals(record.status) || "recovery_required".equals(record.status)
                || record.exitCode==125) return Decision.blocked("Uncertain outcome requires manual reconciliation; no automatic replay");
        String command = safe(record.command);
        if (command.isEmpty()) return Decision.blocked("Run has no stored command");
        boolean destructive = "destructive".equals(safe(record.mutationClass));
        String title = safe(record.title).isEmpty() ? "command" : safe(record.title);
        String actionId = retryActionId(record);
        String preview = preview(command);
        String description = destructive
            ? "Rerun destructive command from history after confirmation"
            : "Rerun command from history";
        String message = destructive
            ? "This will rerun a destructive root command. Review the command before continuing.\n\n" + preview
            : "Rerun this root command?\n\n" + preview;
        return Decision.allowed(actionId, "Retry " + title, description, command, destructive,
            destructive, message);
    }

    static String retryActionId(RunRecord record) {
        String raw = safe(record == null ? "" : record.actionId);
        if (raw.isEmpty()) raw = "command";
        if (raw.startsWith("retry_")) raw = raw.substring("retry_".length());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < 48; i++) {
            char c = raw.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c - 'A' + 'a');
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out.append(c);
            else if (c == '_' || c == '-' || c == '.' || c == ' ') out.append('_');
        }
        String value = collapseUnderscores(out.toString());
        if (value.isEmpty()) value = "command";
        return "retry_" + value;
    }

    private static String preview(String command) {
        String value = command.replace('\n', ' ').replace('\r', ' ').trim();
        if (value.length() <= MAX_PREVIEW_CHARS) return value;
        return value.substring(0, MAX_PREVIEW_CHARS - 1) + "…";
    }

    private static String collapseUnderscores(String value) {
        StringBuilder out = new StringBuilder();
        boolean underscore = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_') {
                if (!underscore && out.length() > 0) out.append(c);
                underscore = true;
            } else {
                out.append(c);
                underscore = false;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') out.deleteCharAt(out.length() - 1);
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    static final class Decision {
        final boolean allowed;
        final boolean requiresConfirmation;
        final boolean destructive;
        final String reason;
        final String actionId;
        final String title;
        final String description;
        final String command;
        final String message;

        private Decision(boolean allowed, boolean requiresConfirmation, boolean destructive, String reason,
                         String actionId, String title, String description, String command, String message) {
            this.allowed = allowed;
            this.requiresConfirmation = requiresConfirmation;
            this.destructive = destructive;
            this.reason = reason == null ? "" : reason;
            this.actionId = actionId == null ? "" : actionId;
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.command = command == null ? "" : command;
            this.message = message == null ? "" : message;
        }

        static Decision blocked(String reason) {
            return new Decision(false, false, false, reason, "", "", "", "", "");
        }

        static Decision allowed(String actionId, String title, String description, String command,
                                boolean destructive, boolean requiresConfirmation, String message) {
            return new Decision(true, requiresConfirmation, destructive, "", actionId, title, description,
                command, message);
        }
    }
}
