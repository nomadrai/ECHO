package com.echo.capture

import com.echo.core.model.Event
import com.echo.core.model.Modality
import com.echo.core.model.Observation
import com.echo.core.model.RelationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end observation → extraction → correlation on a plain JVM — the
 * exact flow the capture service drives. The plan §11 demo impact (audio
 * transient + frame motion + device shock within ~1 s) must produce its three
 * single-mode events plus ONE CONFIRMED INCIDENT, with the bus and the
 * persistence hooks seeing everything in the right order.
 */
class PerceptionPipelineTest {

    private class Harness {
        val bus = SessionBus()
        val events = ArrayList<Event>()
        val relations = ArrayList<com.echo.core.model.EventRelation>()
        val pipeline = PerceptionPipeline(
            bus = bus,
            onEvent = { events.add(it) },
            onRelation = { relations.add(it) },
        )

        /** One 20 ms hop of a channel; sigma null = baseline still learning. */
        fun hop(kind: String, tMs: Long, sigma: Double?, modality: Modality) {
            pipeline.submit(
                Observation(
                    tMs = tMs,
                    modality = modality,
                    kind = kind,
                    value = 1.0,
                    unit = "u",
                ),
                sigma,
            )
        }

        /** Mimic the sources: 400 warm hops, then a spike. */
        fun warm(kind: String, modality: Modality, untilMs: Long) {
            var t = 0L
            while (t < untilMs) {
                hop(kind, t, 0.0, modality)
                t += 20
            }
        }
    }

    @Test
    fun `three-modal impact fuses into ONE CONFIRMED incident end to end`() {
        val h = Harness()
        // Baseline learning per channel (separate kinds, staggered like the
        // real sources' threads would interleave them).
        h.warm("AUDIO_RMS", Modality.AUDIO, 8_000)
        h.warm("FRAME_MOTION", Modality.VISION, 8_000)
        h.warm("ACCEL_MAG", Modality.MOTION, 8_000)

        // The physical event: impact, frame motion and shock inside ~200 ms.
        h.hop("AUDIO_RMS", 9_000, 6.0, Modality.AUDIO)      // → IMPACT_TRANSIENT
        h.hop("FRAME_MOTION", 9_080, 6.0, Modality.VISION)  // → RAPID_MOTION
        h.hop("ACCEL_MAG", 9_160, 6.0, Modality.MOTION)     // → DEVICE_SHOCK
        // Benign hop ≥600 ms later: pumps the clock, closing the group.
        h.hop("ACCEL_MAG", 10_700, 0.0, Modality.MOTION)

        val incidents = h.events.filter { it.type == "INCIDENT" }
        assertEquals(1, incidents.size)
        assertTrue(incidents[0].tier.name == "CONFIRMED")
        assertEquals(
            setOf(Modality.AUDIO, Modality.VISION, Modality.MOTION),
            incidents[0].modalities,
        )
        // Bus saw everything, in order: 3 singles then the incident.
        assertEquals(h.events, h.bus.state.value.events.takeLast(h.events.size))
        assertEquals(4, h.bus.state.value.eventCount)
        assertEquals(1, h.bus.state.value.incidentCount)
    }

    @Test
    fun `pre-incident deviation is derived and persisted after its incident`() {
        val h = Harness()
        h.warm("AUDIO_RMS", Modality.AUDIO, 8_000)
        h.warm("ACCEL_MAG", Modality.MOTION, 8_000)

        // Sustained vibration shift: 2.5σ for 1.2 s (≥ sustainedMs), ends
        // 1.0 s before the impact group opens.
        var t = 8_200L
        while (t < 9_400) {
            h.hop("ACCEL_MAG", t, 3.0, Modality.MOTION)
            t += 20
        }
        h.hop("AUDIO_RMS", 10_400, 6.0, Modality.AUDIO)
        h.hop("ACCEL_MAG", 10_500, 6.0, Modality.MOTION)
        h.hop("ACCEL_MAG", 11_200, 0.0, Modality.MOTION) // closes the group

        val incidents = h.events.filter { it.type == "INCIDENT" }
        assertEquals(1, incidents.size)
        val deviations = h.events.filter { it.type == "PRE_INCIDENT_DEVIATION" }
        assertEquals(1, deviations.size)
        // Derived events persist after the incident they explain.
        assertTrue(
            h.events.indexOf(incidents[0]) < h.events.indexOf(deviations[0]),
        )
        val precedes = h.relations.single { it.kind == RelationKind.PRECEDES }
        assertEquals(deviations[0].tStartMs, incidents[0].tStartMs - precedes.deltaMs)
    }

    @Test
    fun `quiet baseline session emits nothing`() {
        val h = Harness()
        h.warm("AUDIO_RMS", Modality.AUDIO, 8_000)
        h.warm("FRAME_MOTION", Modality.VISION, 8_000)
        h.hop("AUDIO_RMS", 9_000, 1.0, Modality.AUDIO) // normal variation
        h.hop("FRAME_MOTION", 9_000, 1.5, Modality.VISION)
        assertTrue(h.events.isEmpty())
        assertEquals(0, h.bus.state.value.eventCount)
        assertEquals(0, h.bus.state.value.incidentCount)
    }

    @Test
    fun `null sigma during baseline learning emits nothing`() {
        val h = Harness()
        repeat(100) { h.hop("AUDIO_RMS", it * 20L, null, Modality.AUDIO) }
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `finalize flushes remaining relations through the hook`() {
        val h = Harness()
        h.warm("AUDIO_RMS", Modality.AUDIO, 8_000)
        h.hop("AUDIO_RMS", 9_000, 6.0, Modality.AUDIO) // single, expires alone
        val derived = h.pipeline.finalize()
        assertTrue(derived.isEmpty())
        // Sustained edges (if any) flush once, through the same hook.
        h.relations.forEach { assertTrue(it.windowMs > 0) }
    }

    @Test
    fun `relations are emitted exactly once at finalize`() {
        val h = Harness()
        h.warm("AUDIO_RMS", Modality.AUDIO, 8_000)
        h.warm("ACCEL_MAG", Modality.MOTION, 8_000)
        h.hop("AUDIO_RMS", 9_000, 6.0, Modality.AUDIO)
        h.hop("ACCEL_MAG", 9_100, 6.0, Modality.MOTION)
        h.hop("ACCEL_MAG", 9_800, 0.0, Modality.MOTION) // closes the group
        assertEquals(1, h.events.count { it.type == "INCIDENT" })
        val before = h.relations.size
        h.pipeline.finalize()
        assertEquals(before, h.relations.size) // no duplicates from finalize
    }
}
