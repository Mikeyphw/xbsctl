package io.github.mikeceead.xbscompanion.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithRequiredBaselineProfile() = measure(
        CompilationMode.Partial(BaselineProfileMode.Require)
    )

    @Test
    fun coldStartupWithoutCompilation() = measure(CompilationMode.None())

    private fun measure(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait() }
        )
    }

    private companion object {
        const val PACKAGE_NAME = "io.github.mikeceead.xbscompanion"
    }
}
