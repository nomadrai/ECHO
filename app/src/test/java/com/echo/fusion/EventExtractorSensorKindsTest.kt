package com.echo.fusion

import com.echo.core.model.Modality
import com.echo.core.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction rules for the expanded sensor set: every new observation kind
 * must map to its named event type, and environment channels must stay
 * transient-only (no SUSTAINED_* pairs from a drifting barometer).
 */
class EventExtractorSensorKindsTest {

    private val extractor = EventExtractor()

    private fun obs(
        kind: String,
        tMs: Long,
        modality: Modality = Modality.MOTION,
    ) = Observation(tMs = tMs, modality = modality, kind = kind, value = 1.0, unit = "u")

    private fun warm(kind: String, modality: Modality = Modality.MOTION) {
        repeat(400) { extractor.onObservation(obs(kind, it * 20L, modality), 0.0) }
    }

    @Test
    fun `gyro spikes map to ANGULAR_JOLT`() {
        warm("GYRO_MAG")
        val events = extractor.onObservation(obs("GYRO_MAG", 9_000), 6.0)
        assertEquals(1, events.size)
        assertEquals("ANGULAR_JOLT", events[0].type)
    }

    @Test
    fun `linear acceleration spikes map to ACCEL_JOLT`() {
        warm("ACCEL_LIN")
        val events = extractor.onObservation(obs("ACCEL_LIN", 9_000), 6.0)
        assertEquals(1, events.size)
        assertEquals("ACCEL_JOLT", events[0].type)
    }

    @Test
    fun `tilt deviations map to TILT_CHANGE`() {
        warm("TILT_ANGLE")
        val events = extractor.onObservation(obs("TILT_ANGLE", 9_000), 6.0)
        assertEquals(1, events.size)
        assertEquals("TILT_CHANGE", events[0].type)
    }

    @Test
    fun `magnetic spikes map to MAGNETIC_DISTURBANCE`() {
        warm("MAG_FIELD", Modality.ENVIRONMENT)
        val events = extractor.onObservation(obs("MAG_FIELD", 9_000, Modality.ENVIRONMENT), 6.0)
        assertEquals(1, events.size)
        assertEquals("MAGNETIC_DISTURBANCE", events[0].type)
        assertEquals(Modality.ENVIRONMENT, events[0].modalities.first())
    }

    @Test
    fun `pressure spikes map to PRESSURE_TRANSIENT`() {
        warm("PRESSURE", Modality.ENVIRONMENT)
        val events = extractor.onObservation(obs("PRESSURE", 9_000, Modality.ENVIRONMENT), 6.0)
        assertEquals(1, events.size)
        assertEquals("PRESSURE_TRANSIENT", events[0].type)
    }

    @Test
    fun `light spikes map to LIGHT_CHANGE`() {
        warm("LIGHT_LUX", Modality.ENVIRONMENT)
        val events = extractor.onObservation(obs("LIGHT_LUX", 9_000, Modality.ENVIRONMENT), 6.0)
        assertEquals(1, events.size)
        assertEquals("LIGHT_CHANGE", events[0].type)
    }

    @Test
    fun `pass-through kinds keep their own name`() {
        warm("STEP_DETECTED", Modality.ENVIRONMENT)
        val events = extractor.onObservation(obs("STEP_DETECTED", 9_000, Modality.ENVIRONMENT), 8.0)
        assertEquals(1, events.size)
        assertEquals("STEP_DETECTED", events[0].type)
    }

    @Test
    fun `environment channels never fire sustained events`() {
        // 60 hops × 20 ms = 1.2 s of continuous 3σ elevation — would produce a
        // sustained event for a motion channel, must not for the environment.
        repeat(400) { extractor.onObservation(obs("PRESSURE", it * 20L, Modality.ENVIRONMENT), 0.0) }
        var fired = emptyList<com.echo.core.model.Event>()
        for (hop in 0 until 60) {
            fired = extractor.onObservation(obs("PRESSURE", 9_000L + hop * 20, Modality.ENVIRONMENT), 3.0)
        }
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `motion channels still fire sustained events`() {
        val e = EventExtractor()
        repeat(400) { e.onObservation(obs("ACCEL_MAG", it * 20L), 0.0) }
        // Accumulate: the sustained event fires once mid-window, and a later
        // iteration would overwrite it if we kept only the last return value.
        val fired = ArrayList<com.echo.core.model.Event>()
        for (hop in 0 until 60) {
            fired += e.onObservation(obs("ACCEL_MAG", 9_000L + hop * 20), 3.0)
        }
        assertEquals(1, fired.size)
        assertEquals("VIBRATION_BURST", fired[0].type)
    }
}
