package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Single invalidation/publication authority for evidence consumed by health and release views. */
final class EvidenceStateCoordinator {
    static final String ACTION_EVIDENCE_UPDATED = "io.github.mikeceead.xbscompanion.EVIDENCE_UPDATED";
    static final String EXTRA_SOURCE = "source";
    private static final String PREFS = "xbs_evidence_state_v1";
    private static final String GENERATION = "generation";

    private EvidenceStateCoordinator() {}

    static void publish(Context context, String source, long capturedAt) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long next = Math.max(1L, prefs.getLong(GENERATION, 0L) + 1L);
        prefs.edit()
            .putLong(GENERATION, next)
            .putLong("source_" + safe(source), Math.max(0L, capturedAt))
            .apply();
        // Debug health is derived evidence. Never leave a snapshot claiming older source state.
        DebugHealthStore.clear(app);
        Intent update = new Intent(ACTION_EVIDENCE_UPDATED).setPackage(app.getPackageName());
        update.putExtra(EXTRA_SOURCE, safe(source));
        update.putExtra("generation", next);
        app.sendBroadcast(update);
    }

    static long generation(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(GENERATION, 0L);
    }

    static long sourceCapturedAt(Context context, String source) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("source_" + safe(source), 0L);
    }

    private static String safe(String value) {
        String source = value == null ? "unknown" : value.trim().toLowerCase(java.util.Locale.US);
        return source.matches("[a-z0-9_]{1,48}") ? source : "unknown";
    }
}
