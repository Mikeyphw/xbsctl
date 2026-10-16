package io.github.mikeceead.xbscompanion;

/** Bounded passive evidence for Runtime Notification 2.0. */
final class RuntimeNotificationReport {
    static final long STALE_AFTER_MS = 6 * 60 * 60_000L;

    final long capturedAt;
    final long publishedAt;
    final String token;
    final String bridgeMode;
    final boolean serviceActive;
    final String launchPurpose;
    final String launchCode;
    final String launchDetail;
    final long launchAt;
    final String actionCode;
    final String actionDetail;
    final long actionAt;
    final boolean timedOut;
    final String actionNonce;
    final String consumedActions;

    RuntimeNotificationReport(long capturedAt, long publishedAt, String token, String bridgeMode,
                              boolean serviceActive, String launchPurpose, String launchCode,
                              String launchDetail, long launchAt, String actionCode,
                              String actionDetail, long actionAt, boolean timedOut, String actionNonce, String consumedActions) {
        this.capturedAt = Math.max(0L, capturedAt);
        this.publishedAt = Math.max(0L, publishedAt);
        this.token = clean(token, 64);
        this.bridgeMode = clean(bridgeMode, 32);
        this.serviceActive = serviceActive;
        this.launchPurpose = clean(launchPurpose, 64);
        this.launchCode = clean(launchCode, 64);
        this.launchDetail = clean(launchDetail, 240);
        this.launchAt = Math.max(0L, launchAt);
        this.actionCode = clean(actionCode, 64);
        this.actionDetail = clean(actionDetail, 240);
        this.actionAt = Math.max(0L, actionAt);
        this.timedOut = timedOut;
        this.actionNonce = clean(actionNonce, 64);
        this.consumedActions = clean(consumedActions, 512);
    }

    static RuntimeNotificationReport empty() {
        return new RuntimeNotificationReport(0L, 0L, "", "", false, "", "", "", 0L, "", "", 0L, false, "", "");
    }

    boolean evidencePresent() { return capturedAt > 0L; }
    boolean fresh(long now) { return evidencePresent() && now >= capturedAt && now - capturedAt <= STALE_AFTER_MS; }
    boolean actionNonceReady() { return !actionNonce.isEmpty(); }
    boolean launchBlocked() {
        return !launchCode.isEmpty() && !ForegroundServiceLaunchResult.STARTED.equals(launchCode);
    }

    String summary() {
        if (!evidencePresent()) return "Runtime notification evidence has not been cached";
        if (timedOut) return "Foreground service timed out and stopped";
        if (launchBlocked()) return "Foreground launch blocked: " + launchCode;
        if (!actionCode.isEmpty() && !"allowed".equals(actionCode)) return "Latest action rejected: " + actionCode;
        return serviceActive ? "Runtime notification service active" : "Runtime notification service inactive";
    }

    String supportText() {
        return "captured_at=" + capturedAt + " published_at=" + publishedAt + " service_active=" + serviceActive + "\n"
            + "bridge_mode=" + bridgeMode + " token_present=" + !token.isEmpty() + "\n"
            + "launch_at=" + launchAt + " launch_purpose=" + launchPurpose + " launch_code=" + launchCode + "\n"
            + "launch_detail=" + launchDetail + "\n"
            + "action_at=" + actionAt + " action_code=" + actionCode + " action_detail=" + actionDetail + "\n"
            + "timed_out=" + timedOut + " action_nonce_present=" + !actionNonce.isEmpty() + " consumed_action_count=" + (consumedActions.isEmpty()?0:consumedActions.split(",").length) + "\n";
    }

    private static String clean(String value, int max) {
        String out = value == null ? "" : value.trim().replace('\n', ' ').replace('\r', ' ');
        return out.length() <= max ? out : out.substring(0, max);
    }
}
