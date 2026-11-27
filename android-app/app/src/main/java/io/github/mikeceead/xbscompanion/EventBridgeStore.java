package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Persists bounded bridge evidence plus the app-owned durable anti-replay cursor. */
final class EventBridgeStore {
    private static final String CACHE = "event_bridge_report_v1";
    private static final String PREFS = "event_bridge_cursor_v1";
    private static final String KEY_ID = "key_id";
    private static final String SEQUENCE = "sequence";
    private static final String DIGEST = "digest";
    static final int MAX_CACHE_CHARS = 16 * 1024;
    private EventBridgeStore() {}

    static final class Cursor {
        final String keyId; final long sequence; final String digest;
        Cursor(String keyId, long sequence, String digest) {
            this.keyId = normalizedHex(keyId, 16);
            this.sequence = Math.max(0L, sequence);
            this.digest = normalizedHex(digest, 64);
        }
    }

    static EventBridgeReport cached(Context context) {
        String raw = XbsCache.peek(context, CACHE);
        if (raw == null || raw.isEmpty() || raw.length() > MAX_CACHE_CHARS) return EventBridgeReport.unavailable();
        try { return EventBridgeReport.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) { return EventBridgeReport.unavailable(); }
    }

    static void save(Context context, EventBridgeReport report) {
        String encoded = (report == null ? EventBridgeReport.unavailable() : report).toJson().toString();
        if (encoded.length() > MAX_CACHE_CHARS) encoded = EventBridgeReport.unavailable().toJson().toString();
        XbsCache.put(context, CACHE, encoded);
    }

    static Cursor cursor(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Cursor(safeString(prefs, KEY_ID), safeLong(prefs, SEQUENCE), safeString(prefs, DIGEST));
    }

    /** Synchronous commit is required before authenticated data can be returned to callers. */
    static synchronized boolean accept(Context context, String keyId, long sequence, String digest) {
        String safeKey = normalizedHex(keyId, 16);
        String safeDigest = normalizedHex(digest, 64);
        if (safeKey.isEmpty() || safeDigest.isEmpty() || sequence < 1L) return false;
        Cursor previous = cursor(context);
        if (!R05cReplayCursorPolicy.mayCommit(previous.keyId, previous.sequence, previous.digest,
                safeKey, sequence, safeDigest)) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, safeKey)
            .putLong(SEQUENCE, sequence)
            .putString(DIGEST, safeDigest)
            .commit();
    }

    private static String safeString(SharedPreferences prefs, String key) {
        try { return prefs.getString(key, ""); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static long safeLong(SharedPreferences prefs, String key) {
        try { return Math.max(0L, prefs.getLong(key, 0L)); }
        catch (RuntimeException ignored) { return 0L; }
    }

    private static String normalizedHex(String value, int length) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        return normalized.matches("[0-9a-f]{" + length + "}") ? normalized : "";
    }
}
