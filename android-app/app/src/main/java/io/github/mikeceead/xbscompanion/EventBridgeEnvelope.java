package io.github.mikeceead.xbscompanion;

import org.json.JSONObject;

/** Parses only root-verified machine output; key material never enters Android. */
final class EventBridgeEnvelope {
    static final int MAX_MACHINE_OUTPUT_CHARS = 512_000;
    final boolean authenticated;
    final String reasonCode;
    final String detail;
    final String algorithm;
    final String keyId;
    final long sequence;
    final long issuedAt;
    final String digest;
    final String snapshotJson;
    final int payloadChars;

    private EventBridgeEnvelope(boolean authenticated, String reasonCode, String detail,
                                String algorithm, String keyId, long sequence, long issuedAt,
                                String digest, String snapshotJson, int payloadChars) {
        this.authenticated = authenticated; this.reasonCode = clean(reasonCode); this.detail = clean(detail);
        this.algorithm = clean(algorithm); this.keyId = clean(keyId); this.sequence = Math.max(0L, sequence);
        this.issuedAt = Math.max(0L, issuedAt); this.digest = clean(digest);
        this.snapshotJson = snapshotJson == null ? "" : snapshotJson; this.payloadChars = Math.max(0, payloadChars);
    }

    static EventBridgeEnvelope parse(String raw) {
        String machine = raw == null ? "" : raw.trim();
        if (machine.isEmpty()) {
            return failure("verifier_unavailable", "Root verifier emitted no machine output");
        }
        if (machine.length() > MAX_MACHINE_OUTPUT_CHARS) {
            return failure("oversized_verifier_output", "Root verifier output exceeds authenticated bridge bounds");
        }
        if (!looksJsonObject(machine)) {
            return looksCliUsage(machine)
                ? failure("verifier_usage", "Root verifier emitted CLI usage instead of JSON; adaptive fallback will poll legacy runtime state")
                : failure("invalid_machine_output", "Root verifier output was not a JSON object: " + preview(machine));
        }
        try {
            JSONObject root = new JSONObject(machine);
            Object dataValue = root.opt("data");
            if (dataValue != null && dataValue != JSONObject.NULL && !(dataValue instanceof JSONObject)) {
                return typedJsonFallback("data", dataValue);
            }
            JSONObject data = dataValue instanceof JSONObject ? (JSONObject) dataValue : null;
            Object bridgeValue = data == null ? null : data.opt("bridge");
            if (bridgeValue != null && bridgeValue != JSONObject.NULL && !(bridgeValue instanceof JSONObject)) {
                return typedJsonFallback("data.bridge", bridgeValue);
            }
            JSONObject bridge = bridgeValue instanceof JSONObject ? (JSONObject) bridgeValue : null;
            if (bridge == null) {
                if (root.has("reason_code") || Boolean.FALSE.equals(root.opt("ok"))) {
                    return topLevelVerifierFailure(root);
                }
                String detail = optionalString(root, "error", optionalString(root, "message", "Bridge payload is missing data.bridge"));
                return looksCliUsage(detail) || root.has("usage")
                    ? failure("verifier_usage", "Root verifier emitted CLI usage instead of data.bridge JSON")
                    : failure("missing_bridge", detail);
            }
            Object okValue = root.opt("ok");
            if (okValue != null && okValue != JSONObject.NULL && !(okValue instanceof Boolean)) {
                return typedJsonFallback("ok", okValue);
            }
            Object authenticatedValue = bridge.opt("authenticated");
            if (authenticatedValue != null && authenticatedValue != JSONObject.NULL && !(authenticatedValue instanceof Boolean)) {
                return typedJsonFallback("data.bridge.authenticated", authenticatedValue);
            }
            boolean ok = Boolean.TRUE.equals(okValue);
            boolean bridgeAuthenticated = Boolean.TRUE.equals(authenticatedValue);
            if (!ok || !bridgeAuthenticated) {
                String reason = requiredOptionalString(bridge, "reason_code", "verifier_failed", "data.bridge.reason_code");
                String detail = requiredOptionalString(bridge, "detail", optionalString(root, "error", "Root verifier rejected the bridge envelope"), "data.bridge.detail");
                if (reason == null || detail == null) return typedJsonFallback(reason == null ? "data.bridge.reason_code" : "data.bridge.detail", reason == null ? bridge.opt("reason_code") : bridge.opt("detail"));
                return failure(reason, detail);
            }
            Object payloadValue = bridge.opt("payload");
            if (payloadValue != null && payloadValue != JSONObject.NULL && !(payloadValue instanceof JSONObject)) {
                return typedJsonFallback("data.bridge.payload", payloadValue);
            }
            JSONObject payload = payloadValue instanceof JSONObject ? (JSONObject) payloadValue : null;
            Object eventValue = payload == null ? null : payload.opt("event");
            Object statusValue = payload == null ? null : payload.opt("status");
            if (eventValue != null && eventValue != JSONObject.NULL && !(eventValue instanceof JSONObject)) {
                return typedJsonFallback("data.bridge.payload.event", eventValue);
            }
            if (statusValue != null && statusValue != JSONObject.NULL && !(statusValue instanceof JSONObject)) {
                return typedJsonFallback("data.bridge.payload.status", statusValue);
            }
            JSONObject event = eventValue instanceof JSONObject ? (JSONObject) eventValue : null;
            JSONObject status = statusValue instanceof JSONObject ? (JSONObject) statusValue : null;
            if (event == null || status == null) return failure("invalid_payload", "Verified payload is missing event or status JSON");

            String algorithm = requiredString(bridge, "algorithm", "data.bridge.algorithm");
            String keyId = requiredString(bridge, "key_id", "data.bridge.key_id");
            String digest = requiredString(bridge, "envelope_digest", "data.bridge.envelope_digest");
            Long sequence = requiredLong(bridge, "sequence", "data.bridge.sequence");
            Long issuedAt = requiredLong(bridge, "issued_at", "data.bridge.issued_at");
            if (algorithm == null || keyId == null || digest == null || sequence == null || issuedAt == null) {
                return failure("invalid_machine_output", "Root verifier output used the wrong JSON type for authenticated bridge metadata");
            }
            JSONObject snapshot = new JSONObject();
            snapshot.put("available", true); snapshot.put("event", event); snapshot.put("status", status);
            String snapshotJson = snapshot.toString();
            return new EventBridgeEnvelope(true, "", "", algorithm, keyId, sequence,
                secondsToMillis(issuedAt), digest, snapshotJson, snapshotJson.length());
        } catch (Exception exception) {
            return failure("invalid_machine_output", "Root verifier output could not be parsed: " + clean(exception.getMessage()));
        }
    }

    static EventBridgeEnvelope failure(String code, String detail) {
        return new EventBridgeEnvelope(false, code, detail, "", "", 0L, 0L, "", "", 0);
    }

    private static EventBridgeEnvelope topLevelVerifierFailure(JSONObject root) {
        Object reasonValue = root.opt("reason_code");
        if (reasonValue != null && reasonValue != JSONObject.NULL && !(reasonValue instanceof String)) {
            return typedJsonFallback("reason_code", reasonValue);
        }
        Object errorValue = root.opt("error");
        if (errorValue != null && errorValue != JSONObject.NULL && !(errorValue instanceof String)) {
            return typedJsonFallback("error", errorValue);
        }
        Object messageValue = root.opt("message");
        if (messageValue != null && messageValue != JSONObject.NULL && !(messageValue instanceof String)) {
            return typedJsonFallback("message", messageValue);
        }
        String reason = reasonValue instanceof String ? (String) reasonValue : "verifier_failed";
        String detail = errorValue instanceof String ? (String) errorValue
            : messageValue instanceof String ? (String) messageValue
            : "Root verifier rejected the bridge envelope";
        return looksCliUsage(detail) || root.has("usage") ? failure("verifier_usage", detail) : failure(reason, detail);
    }

    private static String requiredString(JSONObject json, String key, String path) {
        Object value = json == null ? null : json.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static String optionalString(JSONObject json, String key, String fallback) {
        Object value = json == null ? null : json.opt(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static String requiredOptionalString(JSONObject json, String key, String fallback, String path) {
        Object value = json == null ? null : json.opt(key);
        if (value == null || value == JSONObject.NULL) return fallback;
        return value instanceof String ? (String) value : null;
    }

    private static Long requiredLong(JSONObject json, String key, String path) {
        Object value = json == null ? null : json.opt(key);
        if (!(value instanceof Number)) return null;
        String raw = String.valueOf(value);
        if (!raw.matches("-?[0-9]+")) return null;
        return ((Number) value).longValue();
    }

    private static EventBridgeEnvelope typedJsonFallback(String path, Object value) {
        String detail = path + " was " + typeName(value) + ": " + preview(String.valueOf(value));
        return looksCliUsage(String.valueOf(value))
            ? failure("verifier_usage", "Root verifier emitted CLI usage in " + path + " instead of bridge JSON")
            : failure("invalid_machine_output", "Root verifier output used the wrong JSON type; " + detail);
    }

    private static String typeName(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof org.json.JSONArray) return "array";
        return value.getClass().getSimpleName();
    }

    private static long secondsToMillis(long seconds) {
        return seconds <= 0L || seconds > Long.MAX_VALUE / 1000L ? 0L : seconds * 1000L;
    }
    private static boolean looksJsonObject(String value) {
        return value != null && value.trim().startsWith("{");
    }

    private static boolean looksCliUsage(String value) {
        String normalized = clean(value).toLowerCase(java.util.Locale.US);
        return normalized.startsWith("usage") || normalized.contains(" usage:")
            || normalized.contains("unrecognized arguments")
            || normalized.contains("invalid choice")
            || normalized.contains("the following arguments are required");
    }

    private static String preview(String value) {
        return clean(value);
    }

    private static String clean(String value) {
        String normalized = value == null ? "" : value.replace('\r',' ').replace('\n',' ').replace('\t',' ').trim().replaceAll(" +", " ");
        return normalized.length() <= 360 ? normalized : normalized.substring(0, 359) + "…";
    }
}
