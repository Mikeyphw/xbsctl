package io.github.mikeceead.xbscompanion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;

/** Pure policy for trusted Runtime Notification 2.0 actions and presentation. */
final class RuntimeNotificationPolicy {
    static final long ACTION_MAX_AGE_MS = 10 * 60_000L;

    private RuntimeNotificationPolicy() {}

    static String token(XbsSnapshot snapshot, EventBridgeReport bridge) {
        XbsSnapshot state = snapshot == null ? XbsSnapshot.unavailable("missing") : snapshot;
        EventBridgeReport report = bridge == null ? EventBridgeReport.unavailable() : bridge;
        String canonical = state.available + "\n" + state.paused + "\n" + state.safeMode + "\n"
            + state.mode + "\n" + XbsSnapshot.join(state.active) + "\n" + state.capturedAt + "\n"
            + report.mode + "\n" + report.reasonCode + "\n" + report.sequence + "\n"
            + report.envelopeDigest + "\n" + report.securityFailure;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (int i = 0; i < 16; i++) out.append(String.format(java.util.Locale.US, "%02x", digest[i] & 0xff));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(canonical.hashCode());
        }
    }

    static Decision evaluateAction(String actionId, String event, String suppliedToken, long issuedAt,
                                   long now, String currentToken, EventBridgeReport bridge,
                                   boolean paused, boolean safeMode, Collection<String> activeEvents) {
        if (suppliedToken == null || suppliedToken.trim().isEmpty()) return Decision.reject("missing_token");
        if (issuedAt <= 0L || now < issuedAt || now - issuedAt > ACTION_MAX_AGE_MS) {
            return Decision.reject("expired_action");
        }
        if (!constantEquals(suppliedToken, currentToken)) return Decision.reject("stale_action");
        if (bridge != null && bridge.securityFailure) return Decision.reject("bridge_security_failure");
        if ("monitor_stop".equals(actionId)) return Decision.allow();
        if (bridge == null || (!bridge.authenticated() && !bridge.fallback())) return Decision.reject("bridge_unavailable");
        if (!NotificationUxPolicy.allowRuntimeAction(actionId, event, paused, safeMode, activeEvents)) {
            return Decision.reject("state_changed");
        }
        return Decision.allow();
    }

    static boolean mutationActionsAllowed(XbsSnapshot snapshot, EventBridgeReport bridge, boolean tokenPersisted) {
        return tokenPersisted && snapshot != null && snapshot.available
            && bridge != null && !bridge.securityFailure && (bridge.authenticated() || bridge.fallback());
    }

    static String transportLabel(EventBridgeReport bridge) {
        if (bridge == null || !bridge.evidencePresent()) return "Transport not checked";
        if (bridge.securityFailure) return "Verification blocked";
        if (bridge.authenticated()) return "Authenticated bridge";
        if (bridge.fallback()) return "Direct fallback";
        return "Bridge unavailable";
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    static final class Decision {
        final boolean allowed;
        final String code;
        private Decision(boolean allowed, String code) { this.allowed = allowed; this.code = code; }
        static Decision allow() { return new Decision(true, "allowed"); }
        static Decision reject(String code) { return new Decision(false, code); }
    }
}
