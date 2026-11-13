package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Device regression proving Debug Health does not mutate source evidence. */
@RunWith(AndroidJUnit4.class)
public final class DebugHealthReadOnlyInstrumentedTest {
    @Test public void testEvaluationDoesNotMutateHistoryLegacyPayloadTransactionCacheOrDatabase() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Thread.sleep(250L);
        SharedPreferences legacy = app.getSharedPreferences("suite_runs", Context.MODE_PRIVATE);
        String legacyPayload = "[{\"run_id\":\"passive-sentinel\",\"status\":\"running\"}]";
        assertTrue(legacy.edit().putString("runs", legacyPayload).commit());

        SharedPreferences cache = app.getSharedPreferences("xbs_companion_cache", Context.MODE_PRIVATE);
        String transaction = "status=committed\nid=passive-sentinel\n";
        long transactionAt = 123456789L;
        assertTrue(cache.edit()
            .putString("config_transaction_summary_v1", transaction)
            .putLong("config_transaction_summary_v1_at", transactionAt)
            .commit());

        Map<String, ?> legacyBefore = new HashMap<>(legacy.getAll());
        Map<String, byte[]> databaseBefore = databaseSnapshot(app);
        DebugHealthEvaluator.evaluate(app);

        assertEquals(legacyBefore, legacy.getAll());
        assertEquals(transaction, cache.getString("config_transaction_summary_v1", null));
        assertEquals(transactionAt, cache.getLong("config_transaction_summary_v1_at", 0L));
        assertDatabaseSnapshotEquals(databaseBefore, databaseSnapshot(app));
    }

    private static Map<String, byte[]> databaseSnapshot(Context context) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        File main = context.getDatabasePath(XbsHistoryDatabase.NAME);
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            File file = new File(main.getPath() + suffix);
            out.put(suffix, file.isFile() ? read(file) : null);
        }
        return out;
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static void assertDatabaseSnapshotEquals(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String key : expected.keySet()) {
            assertTrue("database file changed: " + key, Arrays.equals(expected.get(key), actual.get(key)));
        }
    }
}
