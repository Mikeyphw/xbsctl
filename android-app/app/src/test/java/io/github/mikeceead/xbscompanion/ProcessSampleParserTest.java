package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProcessSampleParserTest {
    @Test public void aggregateCpuExcludesGuestAndGuestNice() {
        assertEquals(576L, ProcessSampleParser.parseCpuTotal("cpu 100 20 30 400 5 6 7 8 90 10"));
    }

    @Test public void aggregateCpuAcceptsShortAndroidKernelVariants() {
        assertEquals(150L, ProcessSampleParser.parseCpuTotal("cpu 50 10 20 70"));
        assertEquals(-1L, ProcessSampleParser.parseCpuTotal("cpu nope 10 20 70"));
    }

    @Test public void parsesSpacesCmdlineIdentityAndExtendedEvidence() {
        String output = "CPU\tcpu 100 10 20 200 4 3 2 1 90 8\n"
            + "PROC\t321\t10123\t2048\t77\t5\t9001\t12\tworker thread\tcom.example.app --sync now\t/system/bin/app_process64\tu:r:untrusted_app:s0:c1,c2\t1\t1\t1\n"
            + "META\t4\t1\t2\t1\t0\t0\t0\t8\n";
        ProcessSampleParser.Sample sample = ProcessSampleParser.parse(output);
        assertTrue(sample.successful());
        assertEquals(340L, sample.totalTicks);
        assertEquals(8, sample.cpuCount);
        assertEquals(1, sample.rows.size());
        ProcessSampleParser.Row row = sample.rows.get(0);
        assertEquals("worker thread", row.processName);
        assertEquals("com.example.app --sync now", row.command);
        assertEquals(12, row.parentPid);
        assertEquals(9001L, row.startTicks);
        assertEquals("321:9001", row.identityKey());
        assertTrue(row.commandLineObserved);
        assertTrue(row.executableObserved);
        assertTrue(row.contextObserved);
    }

    @Test public void emptyCmdlineCanUseCommWithoutClaimingFailure() {
        String output = "CPU\tcpu 10 0 10 80\n"
            + "PROC\t22\t0\t0\t4\t0\t100\t2\tkworker/0:1\tkworker/0:1\t\t\t1\t0\t0\n"
            + "META\t1\t1\t0\t0\t0\t1\t1\t4\n";
        ProcessSampleParser.Sample sample = ProcessSampleParser.parse(output);
        assertTrue(sample.successful());
        assertEquals("kworker/0:1", sample.rows.get(0).command);
        assertTrue(sample.rows.get(0).commandLineObserved);
        assertFalse(sample.rows.get(0).executableObserved);
    }

    @Test public void cpuDeltaRejectsPidReuseAndScalesAcrossCores() {
        assertEquals(0.0, ProcessSampleParser.cpuPercent(140, 2000, 100, 1000, 400, 8), 0.0001);
        assertEquals(80.0, ProcessSampleParser.cpuPercent(140, 1000, 100, 1000, 400, 8), 0.0001);
        assertEquals(0.0, ProcessSampleParser.cpuPercent(90, 1000, 100, 1000, 400, 8), 0.0001);
    }

    @Test public void restrictedProcProducesActionableFailure() {
        ProcessSampleParser.Sample sample = ProcessSampleParser.parse(
            "CPU\tcpu 10 0 10 80\nMETA\t12\t0\t12\t0\t12\t12\t12\t4\n");
        assertFalse(sample.successful());
        assertTrue(sample.error.contains("every visible /proc process entry was restricted"));
    }



    @Test public void rejectsOversizedOrAmbiguousProcRows() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 8200; i++) large.append('x');
        ProcessSampleParser.Sample sample = ProcessSampleParser.parse(
            "CPU\tcpu 10 0 10 80\n"
            + "PROC\t42\t1000\t512\t12\t0\t50\t1\tapp\t" + large + "\t\t\t1\t0\t0\n"
            + "META\t1\t1\t0\t0\t0\t1\t1\t2\n");
        assertFalse(sample.successful());
        assertTrue(sample.error.contains("oversized process field"));
    }

    @Test public void rejectsOversizedScanOutputBeforeParsing() {
        StringBuilder output = new StringBuilder("CPU\tcpu 10 0 10 80\n");
        for (int i = 0; i < 2000100; i++) output.append('x');
        ProcessSampleParser.Sample sample = ProcessSampleParser.parse(output.toString());
        assertFalse(sample.successful());
        assertTrue(sample.error.contains("safety bound"));
    }


    @Test public void rejectsUnsafeProcRowsEvenWhenAnotherRowIsValid() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 8200; i++) large.append('x');
        String good = "PROC\t42\t1000\t512\t12\t0\t50\t1\tapp\tapp\t\t\t1\t0\t0\n";

        ProcessSampleParser.Sample oversized = ProcessSampleParser.parse(
            "CPU\tcpu 10 0 10 80\n"
            + good
            + "PROC\t43\t1000\t512\t12\t0\t51\t1\tapp\t" + large + "\t\t\t1\t0\t0\n"
            + "META\t2\t2\t0\t0\t0\t1\t1\t2\n");
        assertFalse(oversized.successful());
        assertTrue(oversized.error.contains("oversized process field"));

        ProcessSampleParser.Sample ambiguous = ProcessSampleParser.parse(
            "CPU\tcpu 10 0 10 80\n"
            + good
            + "PROC\t43\t1000\t512\t12\t0\t51\t1\tapp\tapp\t\t\t1\t0\t0\textra\n"
            + "META\t2\t2\t0\t0\t0\t1\t1\t2\n");
        assertFalse(ambiguous.successful());
        assertTrue(ambiguous.error.contains("ambiguous process row"));
    }

    @Test public void malformedRowsAreCountedWithoutDiscardingGoodRows() {
        String output = "CPU\tcpu 10 0 10 80\n"
            + "PROC\tbroken\n"
            + "PROC\t42\t1000\t512\t12\t0\t50\t1\tapp\tapp\t\t\t1\t0\t0\n"
            + "META\t2\t1\t0\t0\t0\t1\t1\t2\n";
        ProcessSampleParser.Sample sample = ProcessSampleParser.parse(output);
        assertTrue(sample.successful());
        assertEquals(1, sample.rows.size());
        assertEquals(1, sample.malformed);
    }
}
