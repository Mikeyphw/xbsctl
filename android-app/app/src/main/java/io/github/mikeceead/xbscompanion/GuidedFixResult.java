package io.github.mikeceead.xbscompanion;

/** Bounded evidence from the most recent user-confirmed guided repair. */
final class GuidedFixResult {
    static final int FORMAT_VERSION = 1;
    final String checkId;
    final String action;
    final boolean ok;
    final boolean changed;
    final boolean userActionRequired;
    final long startedAt;
    final long finishedAt;
    final String message;

    GuidedFixResult(String checkId, String action, boolean ok, boolean changed,
                    boolean userActionRequired, long startedAt, long finishedAt, String message) {
        this.checkId = clean(checkId);
        this.action = clean(action);
        this.ok = ok;
        this.changed = changed;
        this.userActionRequired = userActionRequired;
        this.startedAt = Math.max(0L, startedAt);
        this.finishedAt = Math.max(this.startedAt, finishedAt);
        this.message = bounded(message, 700);
    }

    static GuidedFixResult none() {
        return new GuidedFixResult("", "none", false, false, false, 0L, 0L,
            "No guided fix has been run yet");
    }

    String supportText() {
        return "format=" + FORMAT_VERSION + "\n"
            + "check=" + line(checkId) + " action=" + line(action)
            + " ok=" + ok + " changed=" + changed
            + " user_action_required=" + userActionRequired + "\n"
            + "started_at=" + startedAt + " finished_at=" + finishedAt + "\n"
            + "message=" + line(message) + "\n";
    }

    org.json.JSONObject toJson() {
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("format", FORMAT_VERSION);
            json.put("checkId", checkId);
            json.put("action", action);
            json.put("ok", ok);
            json.put("changed", changed);
            json.put("userActionRequired", userActionRequired);
            json.put("startedAt", startedAt);
            json.put("finishedAt", finishedAt);
            json.put("message", message);
        } catch (Exception ignored) {}
        return json;
    }

    static GuidedFixResult fromJson(org.json.JSONObject json) {
        if (json == null || json.optInt("format", 0) != FORMAT_VERSION) return none();
        return new GuidedFixResult(json.optString("checkId"), json.optString("action"),
            json.optBoolean("ok"), json.optBoolean("changed"),
            json.optBoolean("userActionRequired"), json.optLong("startedAt"),
            json.optLong("finishedAt"), json.optString("message"));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String bounded(String value, int max) {
        String clean = clean(value);
        return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
    }
    private static String line(String value) {
        return clean(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
            .replaceAll(" +", " ");
    }
}
