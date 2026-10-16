package io.github.mikeceead.xbscompanion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Key;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Pure, bounded and deterministic Tasker plug-in configuration policy. */
final class TaskerRequestPolicy {
    static final int VERSION = 2;
    static final int MAX_KEYS = 18;
    static final int MAX_VALUE_CHARS = 8192;
    static final int MAX_FIELD_VALUE_CHARS = 1024;
    static final int MAX_CLI_FIELDS = 24;
    static final int MAX_TOTAL_CHARS = 12_000;

    static final String KEY_VERSION = "protocol_version";
    static final String KEY_HOST_PACKAGE = "host_package";
    static final String KEY_HOST_CERT = "host_cert_sha256";
    static final String KEY_NONCE = "config_nonce";
    static final String KEY_SIGNATURE = "config_signature";
    static final String KEY_ALLOW_DESTRUCTIVE = "allow_destructive";

    private static final Set<String> ACTION_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        KEY_VERSION, KEY_HOST_PACKAGE, KEY_HOST_CERT, KEY_NONCE, KEY_SIGNATURE, KEY_ALLOW_DESTRUCTIVE,
        TaskerProtocol.KEY_MODULE, TaskerProtocol.KEY_ACTION, TaskerProtocol.KEY_ARGUMENT,
        TaskerProtocol.KEY_BACKEND, TaskerProtocol.KEY_WORKDIR, TaskerProtocol.KEY_CLI_COMMAND,
        TaskerProtocol.KEY_CLI_VALUES)));
    private static final Set<String> CONDITION_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        KEY_VERSION, KEY_HOST_PACKAGE, KEY_HOST_CERT, KEY_NONCE, KEY_SIGNATURE,
        TaskerConditionProtocol.KEY_KIND, TaskerConditionProtocol.KEY_FILTER, TaskerConditionProtocol.KEY_STATE)));
    private static final Set<String> EVENT_STATES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "any", "profile_active", "profile_inactive", "safe_mode_active", "safe_mode_inactive",
        "runtime_paused", "runtime_resumed", "runtime_unavailable", "runtime_recovered")));
    private static final Set<String> RUNTIME_STATES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "available", "paused", "safe_mode", "any_active", "event_active")));

    private TaskerRequestPolicy() {}

    static Validation validateAction(Map<String,String> values, Set<String> cliFields, boolean destructive) {
        Validation common = validate(values, ACTION_KEYS);
        if (!common.valid) return common;
        if (!"xbs".equals(value(values, TaskerProtocol.KEY_MODULE))) return Validation.reject("invalid_module");
        if (!"root".equals(value(values, TaskerProtocol.KEY_BACKEND))) return Validation.reject("invalid_backend");
        String allow = value(values, KEY_ALLOW_DESTRUCTIVE);
        if (!"true".equals(allow) && !"false".equals(allow)) return Validation.reject("invalid_destructive_flag");
        String command = value(values, TaskerProtocol.KEY_CLI_COMMAND);
        if (command.isEmpty()) return Validation.reject("missing_command");
        if (destructive && !"true".equals(value(values, KEY_ALLOW_DESTRUCTIVE))) {
            return Validation.reject("destructive_opt_in_required");
        }
        String fields = value(values, TaskerProtocol.KEY_CLI_VALUES);
        if (fields.length() > MAX_VALUE_CHARS) return Validation.reject("cli_values_too_large");
        if (cliFields != null && cliFields.size() > MAX_CLI_FIELDS) return Validation.reject("too_many_cli_fields");
        return Validation.allow();
    }

    static Validation validateCondition(Map<String,String> values) {
        Validation common = validate(values, CONDITION_KEYS);
        if (!common.valid) return common;
        String kind = value(values, TaskerConditionProtocol.KEY_KIND);
        String state = value(values, TaskerConditionProtocol.KEY_STATE);
        String filter = value(values, TaskerConditionProtocol.KEY_FILTER);
        if (filter.length() > 160 || filter.indexOf('\0') >= 0) return Validation.reject("invalid_filter");
        if (TaskerConditionProtocol.KIND_EVENT.equals(kind)) {
            if (!EVENT_STATES.contains(state)) return Validation.reject("invalid_event_state");
        } else if (TaskerConditionProtocol.KIND_STATE.equals(kind)) {
            if (!RUNTIME_STATES.contains(state)) return Validation.reject("invalid_runtime_state");
        } else return Validation.reject("invalid_condition_kind");
        return Validation.allow();
    }

    static Validation validateCliValues(Map<String,String> values, Set<String> allowed) {
        if (values == null) return Validation.reject("missing_cli_values");
        if (values.size() > MAX_CLI_FIELDS) return Validation.reject("too_many_cli_fields");
        int total = 0;
        for (Map.Entry<String,String> entry : values.entrySet()) {
            String key = value(entry.getKey()); String val = value(entry.getValue());
            if (key.isEmpty() || allowed == null || !allowed.contains(key)) return Validation.reject("unknown_cli_field");
            if (val.length() > MAX_FIELD_VALUE_CHARS) return Validation.reject("cli_field_too_large");
            total += key.length() + val.length();
        }
        return total <= MAX_VALUE_CHARS ? Validation.allow() : Validation.reject("cli_values_too_large");
    }

    static Validation verify(Map<String,String> values, Set<String> allowed, byte[] secret) {
        return verify(values, allowed, secret == null ? null : new SecretKeySpec(secret, "HmacSHA256"));
    }

    static Validation verify(Map<String,String> values, Set<String> allowed, Key secret) {
        Validation basic = validate(values, allowed);
        if (!basic.valid) return basic;
        String supplied = value(values, KEY_SIGNATURE);
        if (supplied.length() != 64) return Validation.reject("invalid_signature");
        String expected = sign(values, secret);
        return constantEquals(supplied, expected) ? Validation.allow() : Validation.reject("bad_signature");
    }

    static String sign(Map<String,String> values, byte[] secret) {
        return sign(values, secret == null ? null : new SecretKeySpec(secret, "HmacSHA256"));
    }

    static String sign(Map<String,String> values, Key secret) {
        if (secret == null) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            byte[] digest = mac.doFinal(canonical(values).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception error) { return ""; }
    }

    static String canonical(Map<String,String> values) {
        java.util.TreeMap<String,String> sorted = new java.util.TreeMap<>();
        if (values != null) for (Map.Entry<String,String> entry : values.entrySet()) {
            if (!KEY_SIGNATURE.equals(entry.getKey())) sorted.put(raw(entry.getKey()), raw(entry.getValue()));
        }
        StringBuilder out = new StringBuilder("xbs-tasker-v2\n");
        for (Map.Entry<String,String> entry : sorted.entrySet()) {
            String key = raw(entry.getKey()); String val = raw(entry.getValue());
            out.append(key.length()).append(':').append(key).append('=').append(val.length()).append(':').append(val).append('\n');
        }
        return out.toString();
    }

    static Set<String> actionKeys() { return ACTION_KEYS; }
    static Set<String> conditionKeys() { return CONDITION_KEYS; }

    private static Validation validate(Map<String,String> values, Set<String> allowed) {
        if (values == null) return Validation.reject("missing_config");
        if (values.size() > MAX_KEYS) return Validation.reject("too_many_keys");
        int total = 0;
        for (Map.Entry<String,String> entry : values.entrySet()) {
            String key = value(entry.getKey()); String val = value(entry.getValue());
            if (key.isEmpty() || !allowed.contains(key)) return Validation.reject("unknown_key");
            if (val.length() > MAX_VALUE_CHARS) return Validation.reject("value_too_large");
            total += key.length() + val.length();
        }
        if (total > MAX_TOTAL_CHARS) return Validation.reject("config_too_large");
        if (!String.valueOf(VERSION).equals(value(values, KEY_VERSION))) return Validation.reject("unsupported_version");
        if (!TaskerHostIdentity.supportedPackage(value(values, KEY_HOST_PACKAGE))) return Validation.reject("unsupported_host");
        if (!value(values, KEY_HOST_CERT).matches("[0-9a-fA-F]{64}")) return Validation.reject("invalid_host_certificate");
        if (!value(values, KEY_NONCE).matches("[A-Za-z0-9_-]{16,96}")) return Validation.reject("invalid_nonce");
        return Validation.allow();
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(value(left).getBytes(StandardCharsets.UTF_8), value(right).getBytes(StandardCharsets.UTF_8));
    }
    private static String value(Map<String,String> values, String key) { return values == null ? "" : value(values.get(key)); }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static String raw(String value) { return value == null ? "" : value; }

    static final class Validation {
        final boolean valid; final String code;
        private Validation(boolean valid,String code){this.valid=valid;this.code=code;}
        static Validation allow(){return new Validation(true,"ok");}
        static Validation reject(String code){return new Validation(false,code);}
    }
}
