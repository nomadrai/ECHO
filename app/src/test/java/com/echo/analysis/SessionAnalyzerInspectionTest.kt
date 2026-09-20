package com.echo.analysis

import com.echo.ai.WorkContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Inspection harness — run this to see exactly what the extraction layer
 * would hand to the AI, *before* anything is wired together:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "com.echo.analysis.SessionAnalyzerInspectionTest"
 * ```
 *
 * For each fixture it writes the full pretty-printed JSON timeline to
 * `build/analysis-output/` (gitignored) and prints a human-readable summary:
 * one line per event with its timestamp, σ margin, confidence and member
 * channels, plus per-channel baseline stats. Use it to tune
 * [AnalyzerConfig] values and re-run — no device, no emulator, no network.
 */
class SessionAnalyzerInspectionTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `write inspection artifacts for both fixtures`() {
        val outDir = File(System.getProperty("user.dir") ?: ".").resolve("build/analysis-output")
        outDir.mkdirs()

        val fixtures = listOf(
            "mechanical-rig" to (Fixtures.mechanicalRig() to "DC motor rig failure under load"),
            "sleep-night" to (Fixtures.sleepNight() to "overnight sleep monitoring"),
        )
        for ((name, fixtureAndGoal) in fixtures) {
            val (channels, goal) = fixtureAndGoal
            val timeline = SessionAnalyzer().analyze(
                channels,
                goal = goal,
                workContext = WorkContext.classify(goal).domain.displayName,
            )
            val file = File(outDir, "$name.json")
            file.writeText(json.encodeToString(timeline))
            printSummary(name, timeline)
            println("JSON written: ${file.absolutePath}\n")
            assertTrue("artifact should not be empty", file.length() > 200)
        }
    }

    private fun printSummary(name: String, t: SessionTimeline) {
        println("=== $name — ${t.workContext} ===")
        println("duration: ${t.durationMs} ms, events: ${t.events.size}")
        if (t.events.isEmpty()) {
            println("  (no events — baseline covered everything)")
        }
        for (e in t.events) {
            println(
                "  [E${e.id}] ${e.type} @+${e.tStartMs}ms..+${e.tEndMs}ms " +
                    "σ=${"%.1f".format(e.peakSigma)} conf=${"%.2f".format(e.confidence)} " +
                    "(${e.members.joinToString("+") { it.channel }})",
            )
        }
        println("channel baselines:")
        for (s in t.channelStats) {
            println(
                "  ${s.channel} (${s.modality}, ${s.unit}): n=${s.samples} " +
                    "mean=${"%.4f".format(s.mean)} σ=${"%.4f".format(s.sigma)} " +
                    "min=${"%.4f".format(s.min)} max=${"%.4f".format(s.max)}",
            )
        }
    }
}
