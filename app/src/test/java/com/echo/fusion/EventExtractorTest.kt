package com.echo.fusion

import com.echo.core.model.Modality
import com.echo.core.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic extraction rules on a plain JVM (plan §13: `fusion` has no
 * Android imports). The correlation/tier engine in M1 extends these tests.
 */
class EventExtractorTest {

    private val extractor = EventExtractor()

    private fun obs(kind: String, tMs: Long, value: Double = 1.0) = Observation(
        tMs = tMs,
        modality = when (kind) {
            "AUDIO_RMS" -> Modality.AUDIO
            "FRAME_MOTION" -> Modality.VISION
            else -> Modality.MOTION
        },
        kind = kind,
        value = value,
        unit = "u",
    )

    private fun warmUp(kind: String) {
        repeat(400) { extractor.onObservation(obs(kind, it * 20L), 0.0) }
    }

    @Test
    fun `null sigma never produces events`() {
        assertTrue(extractor.onObservation(obs("AUDIO_RMS", 1_000), null).isEmpty())
    }

    @Test
    fun `big spike fires a transient with the mapped type`() {
        warmUp("AUDIO_RMS")
        val events = extractor.onObservation(obs("AUDIO_RMS", 9_000), 6.0)
        assertEquals(1, events.size)
        assertEquals("IMPACT_TRANSIENT", events[0].type)
        assertEquals(9_000, events[0].tStartMs)
        assertEquals("PROBABLE", events[0].tier.name)
    }

    @Test
    fun `kind maps to the right event types`() {
        warmUp("ACCEL_MAG")
        val motion = extractor.onObservation(obs("ACCEL_MAG", 9_000), 6.0)
        assertEquals("DEVICE_SHOCK", motion[0].type)
        warmUp("FRAME_MOTION")
        val vision = extractor.onObservation(obs("FRAME_MOTION", 20_000), 6.0)
        assertEquals("RAPID_MOTION", vision[0].type)
    }

    @Test
    fun `duplicate spikes inside the dedup window collapse into one event`() {
        warmUp("AUDIO_RMS")
        val first = extractor.onObservation(obs("AUDIO_RMS", 9_000), 6.0)
        val second = extractor.onObservation(obs("AUDIO_RMS", 9_100), 7.0)
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `moderate elevation sustained long enough fires a sustained event`() {
        val e = EventExtractor(sustainedSigma = 2.5, sustainedMs = 800, triggerSigma = 4.0)
        // Warm the baseline far from the elevated level.
        repeat(400) { e.onObservation(obs("AUDIO_RMS", it * 20L), 0.0) }
        var fired = emptyList<com.echo.core.model.Event>()
        // 40 hops × 20 ms = 800 ms of sustained elevation.
        for (hop in 0 until 60) {
            fired = e.onObservation(obs("AUDIO_RMS", 9_000L + hop * 20), 3.0)
        }
        assertEquals(1, fired.size)
        assertEquals("SUSTAINED_NOISE", fired[0].type)
        assertEquals(9_000, fired[0].tStartMs)
    }

    @Test
    fun `moderate elevation below the sustained duration fires nothing`() {
        val e = EventExtractor(sustainedSigma = 2.5, sustainedMs = 800, triggerSigma = 4.0)
        repeat(400) { e.onObservation(obs("AUDIO_RMS", it * 20L), 0.0) }
        var fired = emptyList<com.echo.core.model.Event>()
        for (hop in 0 until 20) { // only 400 ms of elevation
            fired = e.onObservation(obs("AUDIO_RMS", 9_000L + hop * 20), 3.0)
        }
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `event ids increase monotonically`() {
        warmUp("AUDIO_RMS")
        val a = extractor.onObservation(obs("AUDIO_RMS", 9_000), 6.0)
        warmUp("ACCEL_MAG")
        val b = extractor.onObservation(obs("ACCEL_MAG", 10_000), 6.0)
        assertTrue(b[0].id > a[0].id)
    }
}
