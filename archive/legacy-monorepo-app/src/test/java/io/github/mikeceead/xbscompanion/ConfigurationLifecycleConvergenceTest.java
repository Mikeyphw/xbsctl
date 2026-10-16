package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Regression coverage for the canonical configuration bootstrap contract. */
public final class ConfigurationLifecycleConvergenceTest {
    @Test public void canonicalEnvironmentPinsEveryManagedCliPath() {
        String configDir = XbsConfigApi.CONFIG.substring(0, XbsConfigApi.CONFIG.lastIndexOf('/'));
        String shell = RootDiagnosticsProbe.canonicalConfigEnvironmentShell(configDir);
        assertTrue(shell.contains("XBS_EXTERNAL_ROOT=" + XbsConfigApi.q(configDir)));
        assertTrue(shell.contains("XBS_EXTERNAL_CONFIG=" + XbsConfigApi.q(XbsConfigApi.CONFIG)));
        assertTrue(shell.contains("XBS_EXTERNAL_LIST_DIR=" + XbsConfigApi.q(XbsConfigApi.LIST_DIR)));
        assertTrue(shell.contains("XBS_EXTERNAL_LOG_DIR=" + XbsConfigApi.q(configDir + "/logs")));
    }
}
