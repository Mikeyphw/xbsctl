package io.github.mikeceead.xbscompanion;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProcessScannerShellInstrumentedTest {
    @Test public void exactProductionScannerExecutesThroughRootTransport() throws Exception {
        Process probe = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
        assertTrue(probe.waitFor(5, TimeUnit.SECONDS));
        Assume.assumeTrue("root unavailable", probe.exitValue() == 0);

        XbsRootApi.CommandOutput output = XbsRootApi.runRootLargeScript(ProcessRepository.buildScanShell(), 45);
        assertEquals("scanner stderr/stdout=" + output.error + " / " + output.stdout, 0, output.exitCode);
        ProcessSampleParser.Sample sample = ProcessSampleParser.parse(output.stdout);
        assertTrue("scanner parse failed: " + sample.error, sample.successful());
        assertTrue("expected visible processes", sample.scanned > 0);
        assertTrue("expected readable processes", sample.readable > 0);
        assertTrue("expected cpu count", sample.cpuCount > 0);
    }
}
