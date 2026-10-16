package io.github.mikeceead.xbscompanion;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Applies the real release script and executes its dependency-graph assertion. */
public final class Phase31ReleaseSealGradleTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void actualReleaseScriptVerifiesTheExecutableTwoPassGraph() throws Exception {
        Path root = temporary.newFolder("fixture").toPath();
        String script = escaped(System.getProperty("phase31.scriptUnderTest"));
        Files.writeString(root.resolve("settings.gradle"),
            "rootProject.name='fixture'\ninclude ':app', ':benchmark', ':phase31-testkit'\n",
            StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("benchmark"));
        Files.createDirectories(root.resolve("phase31-testkit"));
        Files.writeString(root.resolve("app/build.gradle"), tasks(
            "testReleaseUnitTest", "lintRelease", "assembleRelease",
            "connectedReleaseAndroidTest", "generateReleaseBaselineProfile"), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("benchmark/build.gradle"), tasks("connectedCheck"), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("phase31-testkit/build.gradle"), tasks("test"), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("build.gradle"),
            "plugins { id 'base' }\napply from: file('" + script + "')\n",
            StandardCharsets.UTF_8);

        BuildResult result = GradleRunner.create()
            .withProjectDir(root.toFile())
            .withArguments("phase31AssertGraph", "--stacktrace")
            .build();

        assertNotNull(result.task(":phase31AssertGraph"));
        assertEquals(SUCCESS, result.task(":phase31AssertGraph").getOutcome());
        assertTrue(result.getOutput().contains("PHASE31_GRAPH_OK"));
        assertTrue(result.getOutput().contains("candidate->signed-receipt->host-evidence"));
    }

    private static String tasks(String... names) {
        StringBuilder source = new StringBuilder();
        for (String name : names) source.append("tasks.register('").append(name).append("')\n");
        return source.toString();
    }

    private static String escaped(String value) {
        return Path.of(value).toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'");
    }
}
