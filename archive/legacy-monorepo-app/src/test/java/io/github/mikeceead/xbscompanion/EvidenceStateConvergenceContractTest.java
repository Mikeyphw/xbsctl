package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertTrue;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class EvidenceStateConvergenceContractTest {
    private static String source(String file) throws Exception {
        Path path = Paths.get("src/main/java/io/github/mikeceead/xbscompanion", file);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test public void authoritativeSourcesPublishEvidenceUpdates() throws Exception {
        assertTrue(source("RootDiagnosticsStore.java").contains("publish(context, \"root_diagnostics\""));
        assertTrue(source("XbsCompatibilityStore.java").contains("publish(context, \"compatibility\""));
        assertTrue(source("ProcessRepository.java").contains("publish(context, \"process\""));
        assertTrue(source("XbsStateStore.java").contains("publish(context, \"runtime\""));
    }

    @Test public void derivedDebugSnapshotIsGenerationBound() throws Exception {
        String store = source("DebugHealthStore.java");
        assertTrue(store.contains("EvidenceStateCoordinator.generation(context)"));
        assertTrue(store.contains("GENERATION_KEY"));
        assertTrue(source("EvidenceStateCoordinator.java").contains("DebugHealthStore.clear(app)"));
    }

    @Test public void processFailuresBecomeAuthoritativeEvidence() throws Exception {
        String repository = source("ProcessRepository.java");
        assertTrue(repository.contains("saveEvidence(context, ProcessSnapshot.error"));
        assertTrue(repository.contains("private static ProcessSnapshot saveEvidence"));
    }
}
