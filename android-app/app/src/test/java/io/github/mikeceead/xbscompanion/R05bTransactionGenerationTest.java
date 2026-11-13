package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** JVM source-executing, non-root verification of generated transaction commands. */
public final class R05bTransactionGenerationTest {
    private static String hex(char c) {char[] b=new char[64];Arrays.fill(b,c);return new String(b);}
    private static XbsConfigTransaction.Entry oldConfig() {
        return XbsConfigTransaction.Entry.journal(XbsConfigApi.CONFIG,false,"present",hex('b'),hex('a'));
    }
    @Test public void stageReloadRequiresMatchingGenerationAndExplicitAck() {
        XbsConfigTransaction.Plan p=new XbsConfigTransaction.Plan("tx-one",123,true,
            Collections.singletonList(XbsConfigTransaction.Entry.write(XbsConfigApi.CONFIG,"{}")));
        String shell=XbsConfigTransaction.buildExecuteShellForTest(p);
        assertTrue(shell.contains("--expect-config-sha256 "+p.entries.get(0).newDigest));
        assertTrue(shell.contains("--wait --timeout 12"));
        assertTrue(shell.contains("write_status reload_pending"));
        assertTrue(shell.contains("write_status recovery_required"));
        assertTrue(shell.contains("guard_previous"));
        assertTrue(shell.contains("Unresolved prior transaction"));
    }
    @Test public void userRollbackAndRecoveryBindToOldLoadedGeneration() {
        XbsConfigTransaction.Record committed=new XbsConfigTransaction.Record("tx-one","committed",1,2,true,true,1,"ok",
            Collections.singletonList(oldConfig()),"");
        String rollback=XbsConfigTransaction.buildRollbackShellForTest(committed);
        assertTrue(rollback.contains("--expect-config-sha256 "+hex('b')));
        assertTrue(rollback.contains("write_status recovery_required"));
        XbsConfigTransaction.Record pending=new XbsConfigTransaction.Record("tx-one","recovery_required",1,2,true,true,1,"unknown",
            Collections.singletonList(oldConfig()),"");
        assertTrue(pending.needsRecovery());
        String recover=XbsConfigTransaction.buildRecoveryShellForTest(pending);
        assertTrue(recover.contains("--expect-config-sha256 "+hex('b')));
        assertTrue(recover.contains("write_status recovery_required"));
    }
    @Test public void refuseMissingHistoricalGeneration() {
        XbsConfigTransaction.Entry missing=XbsConfigTransaction.Entry.journal(XbsConfigApi.CONFIG,false,"absent","-",hex('a'));
        XbsConfigTransaction.Record rec=new XbsConfigTransaction.Record("tx","committed",1,2,true,true,1,"",
            Collections.singletonList(missing),"");
        try {XbsConfigTransaction.buildRollbackShellForTest(rec);fail("Expected refusal");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("generation"));}
    }
    @Test public void knownStatusRecognizesUncertainRestart() {
        XbsConfigTransaction.Entry e=oldConfig();
        for(String s:new String[]{"reload_pending","recovery_required"}){
            XbsConfigTransaction.Record rec=new XbsConfigTransaction.Record("tx",s,1,2,true,true,1,"",Collections.singletonList(e),"");
            assertTrue(s,rec.needsRecovery());
        }
    }
}
