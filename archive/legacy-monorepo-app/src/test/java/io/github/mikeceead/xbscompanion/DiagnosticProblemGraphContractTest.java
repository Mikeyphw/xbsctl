package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import java.util.Collections;

public class DiagnosticProblemGraphContractTest {
    @Test public void processShellParseFailureIsTypedAsInternalError() {
        ProcessSnapshot p = ProcessSnapshot.error("sh: no closing quote");
        assertEquals(DiagnosticProblemGraph.FailureType.PROCESS_INTERNAL_ERROR,
            DiagnosticProblemGraph.processFailure(p));
    }

    @Test public void neverSampledProcessIsNotAnInternalFailure() {
        ProcessSnapshot p = new ProcessSnapshot(false, 0L, 0L, Collections.emptyList(),
            "No process sample cached yet", ProcessScanDiagnostics.empty());
        assertEquals(DiagnosticProblemGraph.FailureType.PROCESS_NOT_SAMPLED,
            DiagnosticProblemGraph.processFailure(p));
    }

    @Test public void missingCanonicalConfigHasSpecificRemediationCopy() {
        assertTrue(DiagnosticProblemGraph.storageDetail(DiagnosticProblemGraph.FailureType.CONFIG_MISSING)
            .contains("XtremeBS.json"));
    }
}
