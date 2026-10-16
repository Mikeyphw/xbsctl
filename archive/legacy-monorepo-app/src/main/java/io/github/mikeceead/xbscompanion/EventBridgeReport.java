package io.github.mikeceead.xbscompanion;

import org.json.JSONObject;

/** Bounded evidence describing the authenticated event transport or its adaptive fallback. */
final class EventBridgeReport {
    static final int FORMAT_VERSION = 1;
    static final String MODE_AUTHENTICATED = "authenticated";
    static final String MODE_FALLBACK = "fallback";
    static final String MODE_UNAVAILABLE = "unavailable";
    static final long STALE_AFTER_MS = 6 * 60 * 60_000L;

    final long capturedAt;
    final String mode;
    final String reasonCode;
    final String detail;
    final String algorithm;
    final String keyId;
    final long sequence;
    final long issuedAt;
    final String envelopeDigest;
    final boolean duplicate;
    final boolean keyRotated;
    final boolean securityFailure;
    final boolean fallbackAvailable;

    EventBridgeReport(long capturedAt, String mode, String reasonCode, String detail,
                      String algorithm, String keyId, long sequence, long issuedAt,
                      String envelopeDigest, boolean duplicate, boolean keyRotated,
                      boolean securityFailure, boolean fallbackAvailable) {
        this.capturedAt = Math.max(0L, capturedAt);
        this.mode = safeMode(mode);
        this.reasonCode = clean(reasonCode);
        this.detail = bounded(detail, 360);
        this.algorithm = clean(algorithm);
        this.keyId = cleanHex(keyId, 16);
        this.sequence = Math.max(0L, sequence);
        this.issuedAt = Math.max(0L, issuedAt);
        this.envelopeDigest = cleanHex(envelopeDigest, 64);
        this.duplicate = duplicate;
        this.keyRotated = keyRotated;
        this.securityFailure = securityFailure;
        this.fallbackAvailable = fallbackAvailable;
    }

    static EventBridgeReport unavailable() {
        return new EventBridgeReport(0L, MODE_UNAVAILABLE, "not_sampled",
            "Authenticated event bridge has not been sampled yet", "", "", 0L, 0L,
            "", false, false, false, false);
    }

    static EventBridgeReport authenticated(long now, String algorithm, String keyId,
                                            long sequence, long issuedAt, String digest,
                                            boolean duplicate, boolean keyRotated) {
        String detail = duplicate ? "Authenticated snapshot already observed"
            : keyRotated ? "Authenticated snapshot accepted after verified key rotation"
            : "Authenticated event snapshot accepted";
        return new EventBridgeReport(now, MODE_AUTHENTICATED, duplicate ? "duplicate" : "accepted",
            detail, algorithm, keyId, sequence, issuedAt, digest, duplicate, keyRotated,
            false, true);
    }

    static EventBridgeReport fallback(long now, String reasonCode, String detail,
                                      long sequence, long issuedAt, String digest,
                                      boolean securityFailure, boolean fallbackAvailable) {
        return new EventBridgeReport(now, MODE_FALLBACK, reasonCode, detail, "", "",
            sequence, issuedAt, digest, false, false, securityFailure, fallbackAvailable);
    }

    boolean evidencePresent() { return capturedAt > 0L; }
    boolean authenticated() { return MODE_AUTHENTICATED.equals(mode); }
    boolean fallback() { return MODE_FALLBACK.equals(mode); }
    boolean fresh(long now) { return capturedAt > 0L && now >= capturedAt && now - capturedAt <= STALE_AFTER_MS; }

    String headline() {
        if (authenticated()) return duplicate ? "Authenticated event feed · duplicate safely ignored" : "Authenticated event feed active";
        if (fallback()) return securityFailure ? "Authenticated event feed rejected" : "Using polling for XBS events";
        return "Event transport not checked yet";
    }

    String userSummary() {
        if (authenticated()) return duplicate
            ? "The authenticated event feed is healthy; this snapshot was already processed."
            : "XBS events are arriving through the authenticated event feed.";
        if (fallback() && securityFailure)
            return "The authenticated event feed failed security verification. Polling is not trusted as a substitute for this failure.";
        if (fallback() && fallbackAvailable)
            return "The authenticated event feed is unavailable, so Companion is polling XBS instead. Updates may arrive more slowly.";
        if (fallback()) return "The authenticated event feed is unavailable and no usable fallback sample is available.";
        return "Event transport has not been checked yet.";
    }

    String compactSummary() {
        return "mode=" + mode + " reason=" + reasonCode + " sequence=" + sequence
            + " duplicate=" + duplicate + " key_rotated=" + keyRotated
            + " security_failure=" + securityFailure + " fallback_available=" + fallbackAvailable;
    }

    String supportText() {
        StringBuilder out = new StringBuilder(768);
        out.append("format=").append(FORMAT_VERSION)
            .append(" captured_at=").append(capturedAt)
            .append(" mode=").append(mode)
            .append(" fresh=").append(fresh(System.currentTimeMillis())).append('\n');
        out.append("reason_code=").append(reasonCode)
            .append(" security_failure=").append(securityFailure)
            .append(" fallback_available=").append(fallbackAvailable).append('\n');
        out.append("algorithm=").append(algorithm)
            .append(" key_id=").append(keyId)
            .append(" sequence=").append(sequence)
            .append(" issued_at=").append(issuedAt)
            .append(" duplicate=").append(duplicate)
            .append(" key_rotated=").append(keyRotated).append('\n');
        out.append("envelope_digest=").append(envelopeDigest).append('\n');
        out.append("detail=").append(oneLine(detail)).append('\n');
        return out.toString();
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("format", FORMAT_VERSION); json.put("capturedAt", capturedAt);
            json.put("mode", mode); json.put("reasonCode", reasonCode); json.put("detail", detail);
            json.put("algorithm", algorithm); json.put("keyId", keyId); json.put("sequence", sequence);
            json.put("issuedAt", issuedAt); json.put("envelopeDigest", envelopeDigest);
            json.put("duplicate", duplicate); json.put("keyRotated", keyRotated);
            json.put("securityFailure", securityFailure); json.put("fallbackAvailable", fallbackAvailable);
        } catch (Exception ignored) {}
        return json;
    }

    static EventBridgeReport fromJson(JSONObject json) {
        if (json == null || intValue(json, "format", 0) != FORMAT_VERSION) return unavailable();
        String mode = stringValue(json, "mode", MODE_UNAVAILABLE);
        if (!isMode(mode)) return unavailable();
        return new EventBridgeReport(longValue(json, "capturedAt", 0L), mode,
            stringValue(json, "reasonCode", ""), stringValue(json, "detail", ""), stringValue(json, "algorithm", ""),
            stringValue(json, "keyId", ""), longValue(json, "sequence", 0L), longValue(json, "issuedAt", 0L),
            stringValue(json, "envelopeDigest", ""), boolValue(json, "duplicate", false),
            boolValue(json, "keyRotated", false), boolValue(json, "securityFailure", false),
            boolValue(json, "fallbackAvailable", false));
    }

    private static String stringValue(JSONObject json, String key, String fallback) {
        Object value = json == null ? null : json.opt(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static long longValue(JSONObject json, String key, long fallback) {
        Object value = json == null ? null : json.opt(key);
        if (!(value instanceof Number)) return fallback;
        String raw = String.valueOf(value);
        return raw.matches("-?[0-9]+") ? ((Number) value).longValue() : fallback;
    }

    private static int intValue(JSONObject json, String key, int fallback) {
        long value = longValue(json, key, fallback);
        return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? fallback : (int) value;
    }

    private static boolean boolValue(JSONObject json, String key, boolean fallback) {
        Object value = json == null ? null : json.opt(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static boolean isMode(String value) {
        return MODE_AUTHENTICATED.equals(value) || MODE_FALLBACK.equals(value) || MODE_UNAVAILABLE.equals(value);
    }

    private static String safeMode(String value) { return isMode(clean(value)) ? clean(value) : MODE_UNAVAILABLE; }

    private static String cleanHex(String value, int length) {
        String normalized = clean(value).toLowerCase(java.util.Locale.US);
        return normalized.matches("[0-9a-f]{" + length + "}") ? normalized : "";
    }
    private static String bounded(String value, int max) {
        String normalized = clean(value);
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
    private static String oneLine(String value) { return clean(value).replace('\r',' ').replace('\n',' ').replace('\t',' ').replaceAll(" +", " "); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
