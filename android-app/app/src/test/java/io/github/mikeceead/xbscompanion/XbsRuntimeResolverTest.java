package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public final class XbsRuntimeResolverTest {
    @Test public void xbsResolverPrefersRepoModuleBeforeSystemMount() {
        String shell = XbsRuntimeResolver.resolveXbs("XBS_BIN");
        assertBeforeSystemFallback(shell, "'/data/adb/modules/XtremeBS/system/bin/xbs'");
        assertBeforeSystemFallback(shell, "'/data/adb/modules_update/XtremeBS/system/bin/xbs'");
        assertBeforeSystemFallback(shell, "'/data/adb/ksu/modules/XtremeBS/system/bin/xbs'");
        assertBeforeSystemFallback(shell, "'/data/adb/ksu/modules_update/XtremeBS/system/bin/xbs'");
    }

    @Test public void goNativeResolversCoverKsuAndModuleUpdateRoots() {
        String ctl = XbsRuntimeResolver.resolveXbsCtl("CTL");
        assertTrue(ctl.contains("/data/adb/modules_update/XtremeBS/system/bin/XBSctl"));
        assertTrue(ctl.contains("/data/adb/ksu/modules/XtremeBS/system/bin/XBSctl"));
        assertTrue(ctl.contains("/data/adb/ksu/modules_update/XtremeBS/system/bin/XBSctl"));
        String daemon = XbsRuntimeResolver.resolveXtremeBSd("DAEMON");
        assertTrue(daemon.contains("/data/adb/modules/XtremeBS/system/bin/XtremeBSd"));
        assertTrue(daemon.contains("/data/adb/ksu/modules_update/XtremeBS/system/bin/XtremeBSd"));
        assertFalse(daemon.contains("bin/yq"));
        assertFalse(daemon.contains("command -v yq"));
    }

    @Test public void generatedCommandReturnsJsonDetectErrorInsteadOfBareUsage() {
        String shell = XbsRuntimeResolver.xbsJsonError("--json capabilities", "xbs.capabilities",
            "xbs executable not found", "Install or enable the XtremeBS module");
        assertTrue(shell.contains("\"ok\":false"));
        assertTrue(shell.contains("\"action\":\"xbs.capabilities\""));
        assertTrue(shell.contains("Install or enable the XtremeBS module"));
    }

    private static void assertBeforeSystemFallback(String shell, String moduleCandidate) {
        String systemCandidate = "'/system/bin/xbs'";
        int moduleIndex = shell.indexOf(moduleCandidate);
        int systemIndex = shell.lastIndexOf(systemCandidate);
        assertTrue("missing module candidate " + moduleCandidate, moduleIndex >= 0);
        assertTrue("missing explicit system fallback", systemIndex >= 0);
        assertTrue("module candidate should be probed before explicit system fallback", moduleIndex < systemIndex);
    }
}
