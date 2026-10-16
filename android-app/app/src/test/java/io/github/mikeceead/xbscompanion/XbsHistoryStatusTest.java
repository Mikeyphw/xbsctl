package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public final class XbsHistoryStatusTest {
    @Test public void completeHistoryEvidenceIsReleaseReady() {
        XbsHistoryStatus status = new XbsHistoryStatus(true, true, 4, 1, 8, 2, 4096L, "");
        assertTrue(status.readyForRelease());
        assertTrue(status.supportText().contains("database_ready=true migration_complete=true"));
        assertTrue(status.supportText().contains("legacy_imported=4 legacy_skipped=1"));
    }

    @Test public void databaseFailureIsBoundedAndSingleLine() {
        XbsHistoryStatus status = new XbsHistoryStatus(false, true, 2, 0, 2, 1, 0L, "SQLite\nfailed");
        assertFalse(status.readyForRelease());
        assertTrue(status.supportText().contains("error=SQLite failed"));
        assertFalse(status.supportText().contains("error=SQLite\nfailed"));
    }

    @Test public void errorEvidenceIsCappedAtCacheLimit() {
        String oversized = "x".repeat(XbsHistoryStatus.MAX_ERROR_CHARS + 50);
        XbsHistoryStatus status = new XbsHistoryStatus(false, true, 0, 0, 0, 0, 0L, oversized);
        assertEquals(XbsHistoryStatus.MAX_ERROR_CHARS, status.error.length());
        assertEquals(status.error, XbsHistoryStatus.fromJson(status.toJson()).error);
    }

    @Test public void negativeCountersAreClamped() {
        XbsHistoryStatus status = new XbsHistoryStatus(true, true, -1, -2, -3, -4, -5L, "");
        assertTrue(status.supportText().contains("runs=0 active_runs=0 transactions=0 database_bytes=0"));
        assertTrue(status.supportText().contains("legacy_imported=0 legacy_skipped=0"));
    }

    @Test public void cachedRoundTripPreservesPassiveEvidence() {
        XbsHistoryStatus status = new XbsHistoryStatus(true, true, 4, 1, 8, 2, 3, 4096L, 1234L, "");
        XbsHistoryStatus restored = XbsHistoryStatus.fromJson(status.toJson());
        assertTrue(restored.cached());
        assertEquals(2, restored.activeRunCount);
        assertTrue(restored.capturedAt == 1234L);
        assertTrue(restored.readyForRelease());
    }
}
