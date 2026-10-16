package io.github.mikeceead.xbscompanion;

import android.content.Context;

/** Keeps transaction evidence in Room and a cache fallback without another root prompt. */
final class XbsConfigTransactionStore {
    private static final String CACHE_KEY = "config_transaction_summary_v1";
    private static final int MAX_SUPPORT_TEXT_CHARS = ReleaseReadinessPolicy.MAX_TRANSACTION_SUPPORT_CHARS;
    private static final String OVERSIZED_SUPPORT_TEXT =
        "status=invalid_oversized\nmessage=Transaction evidence exceeded support bounds\n";

    private XbsConfigTransactionStore() {}

    static void save(Context context, XbsConfigTransaction.Record record) {
        if (context == null || record == null) return;
        Context app = context.getApplicationContext();
        XbsCache.put(app, CACHE_KEY, boundedSupportText(record.supportText()));
        XbsHistoryStore.recordTransaction(app, record);
    }

    /** Returns only the cache fallback and never opens Room. */
    static String peekSupportText(Context context) {
        if (context == null) return "status=not_cached\nmessage=Open Configuration to inspect the transaction journal\n";
        String cached = XbsCache.peek(context.getApplicationContext(), CACHE_KEY);
        return cached == null || cached.trim().isEmpty()
            ? "status=not_cached\nmessage=Open Configuration to inspect the transaction journal\n"
            : boundedSupportText(cached);
    }

    static long peekCapturedAt(Context context) {
        if (context == null) return 0L;
        long age = XbsCache.age(context.getApplicationContext(), CACHE_KEY);
        if (age == Long.MAX_VALUE) return 0L;
        return Math.max(1L, System.currentTimeMillis() - age);
    }

    static String supportText(Context context) {
        if (context == null) return "status=not_cached\nmessage=Open Configuration to inspect the transaction journal\n";
        Context app = context.getApplicationContext();
        String persisted = XbsHistoryStore.latestTransactionSupport(app);
        if (persisted != null && !persisted.trim().isEmpty()) return boundedSupportText(persisted);
        String cached = XbsCache.peek(app, CACHE_KEY);
        return cached == null || cached.trim().isEmpty()
            ? "status=not_cached\nmessage=Open Configuration to inspect the transaction journal\n"
            : boundedSupportText(cached);
    }

    private static String boundedSupportText(String text) {
        if (text == null) return "";
        return text.length() <= MAX_SUPPORT_TEXT_CHARS ? text : OVERSIZED_SUPPORT_TEXT;
    }
}
