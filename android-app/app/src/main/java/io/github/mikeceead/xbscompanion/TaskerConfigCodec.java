package io.github.mikeceead.xbscompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Strict Android Bundle and JSON adapter around the pure Tasker request policy. */
final class TaskerConfigCodec {
    private static final int INVALID_UID = -1;

    private TaskerConfigCodec() {}

    static boolean validTypes(Bundle bundle, java.util.Set<String> allowed) {
        if (bundle == null || bundle.size() > TaskerRequestPolicy.MAX_KEYS) return false;
        for (String key : bundle.keySet()) {
            if (allowed == null || !allowed.contains(key)) return false;
            Object value = bundle.get(key);
            if (!(value instanceof String) && !(value instanceof Boolean) && !(value instanceof Integer) && !(value instanceof Long)) return false;
        }
        return true;
    }

    static Map<String,String> strings(Bundle bundle) {
        Map<String,String> out = new LinkedHashMap<>();
        if (bundle == null || bundle.size() > TaskerRequestPolicy.MAX_KEYS) return out;
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value instanceof String) out.put(key, (String)value);
            else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) out.put(key, String.valueOf(value));
            else out.put(key, "<invalid-type>");
        }
        return out;
    }

    static ParseResult parseCliValues(String json, CliCommand command) {
        Map<String,String> values = new LinkedHashMap<>();
        Set<String> allowed = new LinkedHashSet<>();
        if (command != null) for (CliField field : command.fields) allowed.add(field.name);
        if (json == null || json.length() > TaskerRequestPolicy.MAX_VALUE_CHARS) return ParseResult.reject("cli_values_too_large");
        try {
            JSONObject object = new JSONObject(json.trim().isEmpty() ? "{}" : json);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next(); Object raw = object.opt(key);
                if (!(raw instanceof String) && raw != null && raw != JSONObject.NULL) return ParseResult.reject("nested_or_non_string_cli_value");
                values.put(key, raw == null || raw == JSONObject.NULL ? "" : (String)raw);
            }
        } catch (Exception error) { return ParseResult.reject("invalid_cli_json"); }
        TaskerRequestPolicy.Validation validation = TaskerRequestPolicy.validateCliValues(values, allowed);
        return validation.valid ? ParseResult.allow(values) : ParseResult.reject(validation.code);
    }

    static String sentFromPackage(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT < 34 || receiver == null) return "";
        try { String value = receiver.getSentFromPackage(); return value == null ? "" : value; }
        catch (RuntimeException ignored) { return ""; }
    }

    static int sentFromUid(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT < 34 || receiver == null) return INVALID_UID;
        try { return receiver.getSentFromUid(); } catch (RuntimeException ignored) { return INVALID_UID; }
    }

    static boolean senderMatches(Context context, String sender, int senderUid, String expectedPackage) {
        boolean identityUnavailable = (sender == null || sender.isEmpty()) && senderUid == INVALID_UID;
        // Android only exposes BroadcastReceiver sender identity from API 34. On older
        // releases the request has already passed the app-owned HMAC check and the
        // installed signer check in TaskerAuthorization.verify(). Require the persisted
        // host pin as the API 26-33 identity binding instead of rejecting every request.
        if (identityUnavailable) {
            return legacySenderPolicy(Build.VERSION.SDK_INT, TaskerAuthorization.trustedHost(context, expectedPackage));
        }
        if (sender == null || !sender.equals(expectedPackage)) return false;
        if (senderUid == INVALID_UID) return true;
        try { return context.getPackageManager().getPackageUid(expectedPackage, 0) == senderUid; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { return false; }
    }

    static boolean legacySenderPolicy(int apiLevel, boolean trustedPinnedHost) {
        return apiLevel >= 26 && apiLevel < 34 && trustedPinnedHost;
    }

    static final class ParseResult {
        final boolean valid; final String code; final Map<String,String> values;
        private ParseResult(boolean valid,String code,Map<String,String> values){this.valid=valid;this.code=code;this.values=values;}
        static ParseResult allow(Map<String,String> values){return new ParseResult(true,"ok",values);}
        static ParseResult reject(String code){return new ParseResult(false,code,java.util.Collections.emptyMap());}
    }
}
