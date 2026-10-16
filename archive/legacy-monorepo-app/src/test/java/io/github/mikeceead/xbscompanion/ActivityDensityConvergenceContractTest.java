package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ActivityDensityConvergenceContractTest {
    private static String source(String file) throws Exception {
        Path path = Paths.get("src/main/java/io/github/mikeceead/xbscompanion", file);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test public void activityUsesOneProgressiveHistoryExperience() throws Exception {
        String main = source("MainActivity.java");
        String history = source("ActivityWorkbenchActivity.java");
        assertTrue(main.contains("Search & manage history"));
        assertFalse(main.contains("Open Activity workbench"));
        assertTrue(history.contains("if (selected.isEmpty())"));
        assertTrue(history.contains("Delete selected finished"));
        assertFalse(history.contains("Select visible finished records"));
        assertFalse(history.contains("Export selected"));
    }

    @Test public void releasePassesAreCollapsedAndDiagnosticRowsAreCompact() throws Exception {
        String release = source("ReleaseReadinessActivity.java");
        String health = source("DebugWorkbenchActivity.java");
        String kit = source("UiKit.java");
        assertTrue(release.contains("addPassedSummary(root)"));
        assertTrue(release.contains("Show passed checks"));
        assertTrue(release.contains("ui.compactSurface()"));
        assertTrue(health.contains("ui.compactSurface()"));
        assertTrue(kit.contains("LinearLayout compactSurface()"));
    }
}
