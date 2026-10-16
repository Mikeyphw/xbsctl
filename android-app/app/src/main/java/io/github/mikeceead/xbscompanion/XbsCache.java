package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;

final class XbsCache {
    private static final String PREFS = "xbs_companion_cache";
    private XbsCache() {}

    static String get(Context context, String key, long maxAgeMs) {
        SharedPreferences prefs = prefs(context);
        long savedAt = prefs.getLong(key + "_at", 0L);
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > maxAgeMs) return null;
        return prefs.getString(key, null);
    }

    static String peek(Context context, String key) {
        return prefs(context).getString(key, null);
    }

    static long age(Context context, String key) {
        long savedAt = prefs(context).getLong(key + "_at", 0L);
        return savedAt <= 0L ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - savedAt);
    }

    static void put(Context context, String key, String value) {
        prefs(context).edit().putString(key, value == null ? "" : value)
            .putLong(key + "_at", System.currentTimeMillis()).apply();
    }

    static void invalidate(Context context, String... keys) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String key : keys) editor.remove(key).remove(key + "_at");
        editor.apply();
    }

    static void clear(Context context) { prefs(context).edit().clear().apply(); }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
