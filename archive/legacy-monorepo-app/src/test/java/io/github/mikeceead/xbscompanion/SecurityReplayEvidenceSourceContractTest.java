package io.github.mikeceead.xbscompanion;
import static org.junit.Assert.*;
import java.nio.file.*;
import org.junit.Test;
public final class SecurityReplayEvidenceSourceContractTest {
 @Test public void durableReplayProofIsRequiredBySecurityReadiness() throws Exception {
   String s = Files.readString(Paths.get("src/main/java/io/github/mikeceead/xbscompanion/RuntimeNotificationStore.java"));
   assertTrue(s.contains("replay_rejection_proven"));
   assertTrue(s.contains("replay_rejection_at"));
   assertTrue(s.contains("present && fresh && replayFresh"));
 }
}
