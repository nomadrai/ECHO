package com.echo.analysis

import com.echo.core.model.Modality
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Analyzer mechanics on a plain JVM: rolling-baseline adaptation, the σ
 * floor, the quiet-channel rule, RATE detection, episode building, fusion,
 * confidence hygiene, and deterministic JSON output.
 *
 * All synthetic channels use bounded noise (±2.2σ — below the 2.5σ episode
 * threshold with margin for window-σ variation, see [Fixtures]), so a
 * baseline sample can never open an episode on its own — assertions below
 * are exact about what fires and what cannot.
 */
class SessionAnalyzerTest {

    private val analyzer = SessionAnalyzer()

    /** Bounded pseudo-gaussian noise (±2.2σ) — see [Fixtures]. */
    private fun noise(r: Random, sigma: Double): Double {
        val u = (r.nextDouble() + r.nextDouble() + r.nextDouble()) - 1.5
        return 2.0 * sigma * u.coerceIn(-1.1, 1.1)
    }

    private fun channel(
        kind: String,
        modality: Modality,
        values: List<Double>,
        stepMs: Long = 100,
        detector: DetectorKind = DetectorKind.LEVEL,
    ) = ChannelSeries(
        kind, modality, "u",
        values.mapIndexed { i, v -> ChannelSeries.Sample(i * stepMs, v) },
        detector,
    )

    private fun constant(r: Random, n: Int, mean: Double, sigma: Double): List<Double> =
        List(n) { mean + noise(r, sigma) }

    @Test
    fun `constant stream produces no events and honest stats`() {
        val r = Random(1)
        val series = channel("X", Modality.MOTION, constant(r, 5_000, 0.5, 0.01))
        val timeline = analyzer.analyze(listOf(series))
        assertTrue(timeline.events.isEmpty())
        val s = timeline.channelStats.single()
        assertEquals(5_000, s.samples)
        assertEquals(0.5, s.mean, 0.001)
        assertEquals(0.01, s.sigma, 0.004)
        assertTrue(s.min >= 0.5 - 0.03 && s.max <= 0.5 + 0.03)
        assertEquals(499_900L, timeline.durationMs)
    }

    @Test
    fun `rolling baseline adapts - a mid-session level shift fires exactly once`() {
        val r = Random(2)
        val values = constant(r, 1_000, 1.0, 0.1) + constant(r, 1_000, 3.0, 0.1)
        val timeline = analyzer.analyze(listOf(channel("X", Modality.MOTION, values)))
        // The shift at t=100 s is flagged once; the new level is then absorbed
        // as the learned normal (no re-flagging, no trailing events).
        assertEquals(1, timeline.events.size)
        val e = timeline.events.single()
        assertEquals(SessionAnalyzer.TYPE_EVENT, e.type)
        assertEquals(100_000L, e.tStartMs)
        assertTrue(e.peakSigma > 4.0)
    }

    @Test
    fun `constant machine vibration on a floored baseline never flags`() {
        val r = Random(3)
        // Running motor: 0.42 m/s² mean, 7 Hz vibration ±0.012, sensor noise.
        val values = List(12_000) {
            0.42 + 0.012 * sin(2.0 * Math.PI * 7.0 * it * 20.0 / 1000.0) + noise(r, 0.006)
        }
        val timeline = analyzer.analyze(
            listOf(channel("ACCEL_MAG", Modality.MOTION, values, stepMs = 20)),
        )
        // The relative σ floor (5 % of mean = 0.021) keeps the wobble ≤ ~1.3σ.
        assertTrue(timeline.events.isEmpty())
    }

    @Test
    fun `quiet channel ignores sub-level drift but flags a genuine spike`() {
        val r = Random(4)
        val values = constant(r, 500, 0.001, 0.0002) +
            List(10) { 0.001 + 1.4 * 0.0002 } +      // sustained 1.4σ drift: calibration
            constant(r, 490, 0.001, 0.0002) +
            List(3) { 0.05 }                          // genuine deviation: hundreds of σ
        val timeline = analyzer.analyze(listOf(channel("MIC", Modality.AUDIO, values)))
        assertEquals(1, timeline.events.size)
        assertEquals(100_000L, timeline.events.single().tStartMs) // the spike, not the drift
    }

    @Test
    fun `rate channel ignores slow drift and flags fast change`() {
        val r = Random(5)
        // 2 h of slow barometric drift (0.4 hPa sine over 2 h) + walk noise.
        val n = 7_200
        var walk = 0.0
        val values = ArrayList<Double>(n)
        for (i in 0 until n) {
            walk = (walk + (if (r.nextBoolean()) 0.001 else -0.001)).coerceIn(-0.05, 0.05)
            values += 1013.2 + 0.4 * sin(2.0 * Math.PI * i * 1_000.0 / 7_200_000.0) + walk
        }
        // Fast change at t=100 min: a 4 hPa drop over 4 steps (1 hPa/step),
        // then the new level persists.
        for (i in 6_000 until n) values[i] -= min(4.0, 1.0 * (i - 6_000 + 1))

        val series = channel(
            "PRESSURE", Modality.ENVIRONMENT, values,
            stepMs = 1_000, detector = DetectorKind.RATE,
        )
        val strict = analyzer.analyze(listOf(series))
        // Environment-only deviation is real but weak: confidence ≈ 0.23
        // (σ margin × the environment trust weight) is below minConfidence.
        assertTrue(strict.events.isEmpty())

        val permissive = SessionAnalyzer(AnalyzerConfig.DEFAULT.copy(minConfidence = 0.0))
            .analyze(listOf(series))
        assertEquals(1, permissive.events.size)
        assertTrue(permissive.events.single().description.contains("PRESSURE"))
        assertTrue(permissive.events.single().peakSigma > 4.0)
    }

    @Test
    fun `spikes within the merge window build one episode - far apart stay two`() {
        val r = Random(6)
        val close = mutableListOf<Double>()
        close += constant(r, 500, 0.5, 0.01)
        close += List(1) { 20.0 }
        close += constant(r, 7, 0.5, 0.01) // 700 ms of normal between the spikes
        close += List(1) { 20.0 }
        close += constant(r, 500, 0.5, 0.01)
        val closeTimeline = analyzer.analyze(listOf(channel("X", Modality.MOTION, close)))
        assertEquals(1, closeTimeline.events.size)
        assertEquals(50_800L, closeTimeline.events.single().tEndMs) // merged into one episode

        val far = mutableListOf<Double>()
        far += constant(r, 500, 0.5, 0.01)
        far += List(1) { 20.0 }
        far += constant(r, 60, 0.5, 0.01) // 6 s gap > episodeMergeMs
        far += List(1) { 20.0 }
        far += constant(r, 500, 0.5, 0.01)
        val farTimeline = analyzer.analyze(listOf(channel("X", Modality.MOTION, far)))
        assertEquals(2, farTimeline.events.size)
    }

    @Test
    fun `minEpisodeMs drops short sub-level episodes and keeps sustained ones`() {
        val r = Random(7)
        // Non-quiet channel (σ = 0.3 ≥ minNoiseSigma): sustained path at 2.5σ.
        // A noise-free plateau at 3.5σ: elevated (≥2.5σ) on every sample,
        // never a 4σ trigger — only minEpisodeMs decides. (3σ is too marginal:
        // the plateau's own samples inflate the rolling window's mixture σ.)
        fun run(biasSamples: Int): List<Double> =
            constant(r, 500, 1.0, 0.3) +
                List(biasSamples) { 1.0 + 3.5 * 0.3 } +
                constant(r, 500, 1.0, 0.3)

        val short = analyzer.analyze(listOf(channel("X", Modality.MOTION, run(10)))) // 1 s
        assertTrue(short.events.isEmpty()) // below minEpisodeMs, never triggered

        val long = analyzer.analyze(listOf(channel("X", Modality.MOTION, run(30)))) // 3 s
        assertEquals(1, long.events.size)
        assertTrue(long.events.single().description.contains("sustained"))
    }

    @Test
    fun `cross-channel episodes within the fusion window become one ANOMALY`() {
        val r = Random(8)
        val base = constant(r, 500, 1.0, 0.05)

        val near = listOf(
            channel(
                "ACCEL_MAG", Modality.MOTION,
                base + List(1) { 30.0 } + constant(r, 500, 1.0, 0.05),
            ),
            channel(
                "AUDIO_RMS", Modality.AUDIO,
                base + List(4) { 1.0 } + List(1) { 30.0 } + constant(r, 496, 1.0, 0.05),
            ),
        )
        val fused = analyzer.analyze(near)
        assertEquals(1, fused.events.size)
        val e = fused.events.single()
        assertEquals(SessionAnalyzer.TYPE_ANOMALY, e.type)
        assertEquals(2, e.members.size)
        assertEquals(listOf(Modality.MOTION, Modality.AUDIO), e.modalities)
        assertTrue(e.confidence > 0.6)

        val apart = listOf(
            channel(
                "ACCEL_MAG", Modality.MOTION,
                base + List(1) { 30.0 } + constant(r, 500, 1.0, 0.05),
            ),
            channel(
                "AUDIO_RMS", Modality.AUDIO,
                base + List(19) { 1.0 } + List(1) { 30.0 } + constant(r, 481, 1.0, 0.05),
            ),
        )
        val separate = analyzer.analyze(apart)
        assertEquals(2, separate.events.size)
        assertTrue(separate.events.all { it.type == SessionAnalyzer.TYPE_EVENT })
    }

    @Test
    fun `weak single-channel events are dropped - fused ones are exempt`() {
        val r = Random(9)
        // A ~4.9σ environment blip alone: confidence ≈ 0.22 < 0.35 → dropped.
        val envChannel = channel(
            "MAG_FIELD", Modality.ENVIRONMENT,
            constant(r, 600, 1.0, 0.05) + List(1) { 1.25 } + constant(r, 100, 1.0, 0.05),
        )
        val alone = analyzer.analyze(listOf(envChannel))
        assertTrue(alone.events.isEmpty())

        val corroborated = analyzer.analyze(
            listOf(
                envChannel,
                channel(
                    "ACCEL_MAG", Modality.MOTION,
                    constant(r, 600, 1.0, 0.05) + List(1) { 30.0 } + constant(r, 100, 1.0, 0.05),
                ),
            ),
        )
        assertEquals(1, corroborated.events.size)
        assertEquals(SessionAnalyzer.TYPE_ANOMALY, corroborated.events.single().type)
        assertEquals(2, corroborated.events.single().members.size)
    }

    @Test
    fun `members carry evidence references - segment stats, not raw data`() {
        val r = Random(10)
        val values = constant(r, 600, 0.08, 0.006) + List(5) { 0.9 } + constant(r, 100, 0.08, 0.006)
        val timeline = analyzer.analyze(listOf(channel("AUDIO_RMS", Modality.AUDIO, values)))
        val e = timeline.events.single()
        val m = e.members.single()
        assertEquals("AUDIO_RMS", m.channel)
        assertEquals(60_000L, m.tStartMs)
        assertEquals(60_400L, m.tEndMs)
        assertEquals(0.9, m.peak, 1e-9) // top raw amplitude of the segment
        assertEquals(0.9, m.mean, 1e-9) // the window covers only the spike samples
        assertTrue(m.sigma > 4.0)
        // The timeline carries no raw sample arrays — only per-event statistics.
        val json = Json.encodeToString(timeline)
        assertTrue(json.length < 4_000)
    }

    @Test
    fun `analysis is deterministic and serializes to valid JSON`() {
        val a = Json.encodeToString(SessionAnalyzer().analyze(Fixtures.mechanicalRig()))
        val b = Json.encodeToString(SessionAnalyzer().analyze(Fixtures.mechanicalRig()))
        assertEquals(a, b)
        assertTrue(a.contains("ANOMALY"))
        val parsed = Json.decodeFromString<SessionTimeline>(a)
        assertEquals(
            SessionAnalyzer().analyze(Fixtures.mechanicalRig()).events.size,
            parsed.events.size,
        )
    }

    @Test
    fun `empty and tiny channels degrade to stats-only`() {
        val timeline = analyzer.analyze(
            listOf(
                ChannelSeries("EMPTY", Modality.ENVIRONMENT, "u", emptyList()),
                channel("X", Modality.MOTION, listOf(1.0, 2.0, 3.0)),
            ),
        )
        assertTrue(timeline.events.isEmpty())
        assertEquals(2, timeline.channelStats.size)
        assertEquals(0, timeline.channelStats[0].samples)
        assertEquals(200L, timeline.durationMs)
    }

    @Test
    fun `negative direction deviations are flagged with below-baseline evidence`() {
        val r = Random(11)
        // Machine stops humming: audio RMS collapses to zero mid-session.
        val values = constant(r, 600, 0.4, 0.01) + List(300) { 0.001 }
        val timeline = analyzer.analyze(listOf(channel("AUDIO_RMS", Modality.AUDIO, values)))
        assertEquals(1, timeline.events.size)
        val e = timeline.events.single()
        val m = e.members.single()
        assertTrue(e.description.contains("below"))
        assertTrue(abs(m.peak) < 0.01) // peak reads the segment floor
        assertTrue(m.sigma > 4.0)
    }
}
