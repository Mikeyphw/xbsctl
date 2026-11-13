package io.github.mikeceead.xbscompanion;

/** Process-local durable cursor commit guard AFTER authenticated bridge verification.
 * The caller must only supply HMAC-verified envelopes. This class never grants trust.
 */
final class R05cReplayCursorPolicy {
    private R05cReplayCursorPolicy() {}

    static boolean mayCommit(String previousKey, long previousSequence, String previousDigest,
                             String verifiedKey, long verifiedSequence, String verifiedDigest) {
        if (!valid(verifiedKey, 16) || !valid(verifiedDigest, 64) || verifiedSequence <= 0L) return false;
        if (previousKey == null || previousKey.isEmpty()) return previousSequence == 0L && (previousDigest == null || previousDigest.isEmpty());
        if (!valid(previousKey, 16) || !valid(previousDigest, 64) || previousSequence < 0L) return false;
        if (!previousKey.equals(verifiedKey)) return true; // Rotation was already authenticated by verifier.
        if (verifiedSequence < previousSequence) return false;
        return verifiedSequence != previousSequence || previousDigest.equals(verifiedDigest);
    }

    private static boolean valid(String value, int n) {
        return value != null && value.matches("[0-9a-f]{" + n + "}");
    }
}
