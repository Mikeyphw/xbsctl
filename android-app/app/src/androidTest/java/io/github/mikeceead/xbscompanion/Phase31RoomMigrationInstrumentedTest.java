package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real on-device Room v1-to-v2 migration with row and index preservation checks. */
@RunWith(AndroidJUnit4.class)
public final class Phase31RoomMigrationInstrumentedTest {
    private static final String DATABASE = "phase31-migration.db";

    @Rule public final MigrationTestHelper helper = new MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        XbsHistoryDatabase.class.getCanonicalName(),
        new FrameworkSQLiteOpenHelperFactory()
    );

    @After public void cleanUp() {
        InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(DATABASE);
    }

    @Test public void migratesVersionOneToVersionTwoWithoutLosingRunHistory() throws Exception {
        SupportSQLiteDatabase versionOne = helper.createDatabase(DATABASE, 1);
        versionOne.execSQL("INSERT INTO run_history "
            + "(run_id,module,action_id,title,status,command,backend,workdir,source,mutation_class,"
            + "timeout_seconds,stdout,stderr,error,message,exit_code,started_at,finished_at) VALUES "
            + "('phase31-run','core','status','Status','succeeded','xbs status','root','~/','app',"
            + "'read',30,'ok','','','done',0,10,20)");
        versionOne.close();

        SupportSQLiteDatabase versionTwo = helper.runMigrationsAndValidate(
            DATABASE, 2, true, XbsHistoryDatabase.MIGRATION_1_2);

        try (Cursor row = versionTwo.query(
            "SELECT run_id,status,pinned,finished_at FROM run_history WHERE run_id='phase31-run'")) {
            assertTrue(row.moveToFirst());
            assertEquals("phase31-run", row.getString(0));
            assertEquals("succeeded", row.getString(1));
            assertEquals(0, row.getInt(2));
            assertEquals(20L, row.getLong(3));
        }

        boolean indexFound = false;
        try (Cursor indices = versionTwo.query("PRAGMA index_list('run_history')")) {
            int name = indices.getColumnIndex("name");
            while (indices.moveToNext()) {
                if ("index_run_history_pinned_finished_at".equals(indices.getString(name))) {
                    indexFound = true;
                }
            }
        }
        assertTrue("v2 pinned/finished index missing", indexFound);
        versionTwo.close();
    }
}
