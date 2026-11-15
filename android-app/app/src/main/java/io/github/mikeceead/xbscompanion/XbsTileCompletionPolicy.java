package io.github.mikeceead.xbscompanion;

/** Command-history and fresh daemon evidence must both prove a tile transition. */
final class XbsTileCompletionPolicy {
    enum Outcome { WAIT, CONFIRMED, FAILED, RECOVERY_REQUIRED }
    private XbsTileCompletionPolicy() {}
    static Outcome evaluate(String status, boolean authenticated, boolean newer,
                            boolean targetObserved) {
        if ("recovery_required".equals(status)) return Outcome.RECOVERY_REQUIRED;
        if ("failed".equals(status) || "cancelled".equals(status)) return Outcome.FAILED;
        if (!"ok".equals(status)) return Outcome.WAIT;
        return authenticated && newer && targetObserved ? Outcome.CONFIRMED : Outcome.WAIT;
    }
}
