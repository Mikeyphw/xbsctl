package io.github.mikeceead.xbscompanion;

import java.util.Locale;

/** Pure replay, freshness, and size policy applied after root-side HMAC verification. */
final class EventBridgePolicy {
    static final long MAX_AGE_MS = 24 * 60 * 60_000L;
    static final long MAX_FUTURE_SKEW_MS = 5 * 60_000L;
    static final int MAX_PAYLOAD_CHARS = 256_000;

    private EventBridgePolicy() {}

    static final class Decision {
        final boolean accepted;
        final boolean duplicate;
        final boolean keyRotated;
        final String code;
        final String detail;
        Decision(boolean accepted, boolean duplicate, boolean keyRotated, String code, String detail) {
            this.accepted = accepted; this.duplicate = duplicate; this.keyRotated = keyRotated;
            this.code = code; this.detail = detail;
        }
    }

    static Decision evaluate(long now, long sequence, long issuedAt, String keyId, String digest,
                             int payloadChars, long lastSequence, String lastKeyId, String lastDigest) {
        if (sequence < 1L) return reject("invalid_sequence", "Sequence must be positive");
        if (issuedAt < 1L) return reject("invalid_timestamp", "Issued timestamp is missing");
        if (payloadChars < 2 || payloadChars > MAX_PAYLOAD_CHARS) return reject("oversized_payload", "Payload is outside the bounded size policy");
        String normalizedKey = hex(keyId, 16);
        String normalizedDigest = hex(digest, 64);
        if (normalizedKey.isEmpty()) return reject("invalid_key_id", "Key identifier is malformed");
        if (normalizedDigest.isEmpty()) return reject("invalid_digest", "Envelope digest is malformed");
        if (issuedAt > now + MAX_FUTURE_SKEW_MS) return reject("future_timestamp", "Snapshot timestamp is too far in the future");
        if (now >= issuedAt && now - issuedAt > MAX_AGE_MS) return reject("stale_envelope", "Authenticated snapshot is older than 24 hours");

        String previousKey = hex(lastKeyId, 16);
        String previousDigest = hex(lastDigest, 64);
        boolean rotation = !previousKey.isEmpty() && !previousKey.equals(normalizedKey);
        if (rotation) return new Decision(true, false, true, "key_rotated", "Verified key rotation resets the replay sequence");
        if (previousKey.isEmpty()) return new Decision(true, false, false, "first_snapshot", "First authenticated snapshot accepted");
        if (sequence < lastSequence) return reject("replay", "Sequence is older than the last accepted snapshot");
        if (sequence == lastSequence) {
            if (normalizedDigest.equals(previousDigest)) return new Decision(true, true, false, "duplicate", "Duplicate authenticated snapshot accepted without replaying events");
            return reject("equivocation", "The same sequence was observed with a different digest");
        }
        return new Decision(true, false, false, "accepted", "Authenticated snapshot is fresh and monotonic");
    }

    /** Only availability and authenticated-freshness failures may use legacy direct polling. */
    static boolean fallbackAllowed(String code) {
        String value = normalize(code);
        return "missing".equals(value) || "unreadable".equals(value)
            || "xbs_missing".equals(value) || "verifier_unavailable".equals(value)
            || "python_unavailable".equals(value) || "publication_unavailable".equals(value)
            || "bridge_not_published".equals(value) || "stale_envelope".equals(value)
            || "verifier_usage".equals(value);
    }

    /** Everything outside the narrow fallback allowlist fails closed by default. */
    static boolean securityFailure(String code) {
        return !fallbackAllowed(code);
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.US);
    }
    private static Decision reject(String code, String detail) { return new Decision(false, false, false, code, detail); }
    private static String hex(String value, int length) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return normalized.matches("[0-9a-f]{" + length + "}") ? normalized : "";
    }
}
