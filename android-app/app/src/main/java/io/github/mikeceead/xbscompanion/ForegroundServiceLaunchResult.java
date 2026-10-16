package io.github.mikeceead.xbscompanion;

/** Bounded result for a foreground-service launch attempt. */
final class ForegroundServiceLaunchResult {
    static final String STARTED = "started";
    static final String BACKGROUND_RESTRICTED = "background_restricted";
    static final String SECURITY_ERROR = "security_error";
    static final String ILLEGAL_STATE = "illegal_state";
    static final String RUNTIME_ERROR = "runtime_error";

    final boolean started;
    final String purpose;
    final String code;
    final String detail;
    final long at;

    ForegroundServiceLaunchResult(boolean started, String purpose, String code, String detail, long at) {
        this.started = started;
        this.purpose = bounded(purpose, 64);
        this.code = bounded(code, 64);
        this.detail = bounded(detail, 240);
        this.at = Math.max(0L, at);
    }

    static ForegroundServiceLaunchResult started(String purpose, long at) {
        return new ForegroundServiceLaunchResult(true, purpose, STARTED, "Foreground service launch accepted", at);
    }

    static ForegroundServiceLaunchResult failed(String purpose, String code, Throwable error, long at) {
        String message = error == null ? "Foreground service launch failed"
            : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return new ForegroundServiceLaunchResult(false, purpose, code, message, at);
    }

    private static String bounded(String value, int max) {
        String clean = value == null ? "" : value.trim().replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
