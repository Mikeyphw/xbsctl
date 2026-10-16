package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessScannerTransportContractTest {
    @Test public void scannerStillContainsRequiredProcEvidence() {
        String scanner = ProcessRepository.buildScanShell();
        assertTrue(scanner.contains("/proc/stat"));
        assertTrue(scanner.contains("/proc/[0-9]*"));
        assertTrue(scanner.contains("$D/cmdline"));
        assertTrue(scanner.contains("$D/exe"));
        assertTrue(scanner.contains("$D/attr/current"));
        assertTrue(scanner.contains("META\t"));
    }
}
