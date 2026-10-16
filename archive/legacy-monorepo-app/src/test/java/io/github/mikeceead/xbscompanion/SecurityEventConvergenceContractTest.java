package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public final class SecurityEventConvergenceContractTest {
    @Test public void profileInstallerReceiverIsAnExplicitKnownGeneratedSurface() {
        Set<String> activities = new LinkedHashSet<>(), receivers = new LinkedHashSet<>();
        for (String n : SecuritySealPolicy.ALLOWED_EXPORTED) {
            if (n.endsWith("Receiver")) receivers.add(n); else activities.add(n);
        }
        receivers.add(SecuritySealPolicy.PROFILE_INSTALL_RECEIVER);
        SecuritySealReport report = SecuritySealPolicy.evaluate(1L, activities, receivers,
            Collections.emptySet(), Collections.emptySet(), false, false, true, "pin", true, true, "fresh");
        assertEquals(0, report.blockerCount());
    }

    @Test public void unknownExportStillFailsClosed() {
        Set<String> activities = new LinkedHashSet<>(), receivers = new LinkedHashSet<>();
        for (String n : SecuritySealPolicy.ALLOWED_EXPORTED) {
            if (n.endsWith("Receiver")) receivers.add(n); else activities.add(n);
        }
        receivers.add("example.UnexpectedReceiver");
        SecuritySealReport report = SecuritySealPolicy.evaluate(1L, activities, receivers,
            Collections.emptySet(), Collections.emptySet(), false, false, true, "pin", true, true, "fresh");
        assertTrue(report.blockerCount() > 0);
    }

    @Test public void fallbackCopyIsSemanticNotTelemetryDump() {
        EventBridgeReport report = EventBridgeReport.fallback(10L, "missing", "missing bridge input", 0L, 0L, "", false, true);
        assertEquals("Using polling for XBS events", report.headline());
        assertTrue(report.userSummary().contains("polling XBS"));
        assertFalse(report.userSummary().contains("sequence="));
        assertTrue(report.compactSummary().contains("sequence="));
    }
}
