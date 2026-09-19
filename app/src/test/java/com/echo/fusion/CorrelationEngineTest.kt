package com.echo.fusion

import com.echo.core.model.Event
import com.echo.core.model.EventTier
import com.echo.core.model.Modality
import com.echo.core.model.Observation
import com.echo.core.model.RelationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 correlation rules on a plain JVM (plan §7): modality windows, the
 * CONFIRMED tier via fusion groups, relation edges, and the pre-incident
 * deviation derivation (inline + finalize sweep).
 */
class CorrelationEngineTest {

    private val ids = EventIdSequence()

    private fun event(
        id: Long,
        tMs: Long,
        type: String,
        modality: Modality,
        salience: Double = 0.5,
        conf: Double = 0.6,
        tEndMs: Long = tMs,
    ) = Event(
        id = id,
        tStartMs = tMs,
        tEndMs = tEndMs,
        type = type,
        modalities = setOf(modality),
        tier = EventTier.PROBABLE,
        confidence = conf,
        salience = salience,
        description = "$type detected",
    )

    private fun audio(id: Long, tMs: Long) = event(id, tMs, "IMPACT_TRANSIENT", Modality.AUDIO)
    private fun vision(id: Long, tMs: Long) = event(id, tMs, "RAPID_MOTION", Modality.VISION)
    private fun motion(id: Long, tMs: Long) = event(id, tMs, "DEVICE_SHOCK", Modality.MOTION)

    private fun engine() = CorrelationEngine(ids)

    @Test
    fun `audio and vision inside 250ms fuse into one CONFIRMED incident`() {
        val e = engine()
        assertTrue(e.onEvent(audio(1, 10_000)).isEmpty())
        assertTrue(e.onEvent(vision(2, 10_150)).isEmpty())
        val out = e.pump(10_750) // 600 ms past the last member
        assertEquals(1, out.size)
        assertEquals("INCIDENT", out[0].type)
        assertEquals(EventTier.CONFIRMED, out[0].tier)
        assertEquals(setOf(Modality.AUDIO, Modality.VISION), out[0].modalities)
    }

    @Test
    fun `audio and motion fuse up to 400ms apart`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(motion(2, 10_400)) // exactly at the A/M window
        val out = e.pump(11_000)
        assertEquals(1, out.size)
        assertEquals(EventTier.CONFIRMED, out[0].tier)
    }

    @Test
    fun `audio and motion 500ms apart do not fuse`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(motion(2, 10_500))
        assertTrue(e.pump(11_100).isEmpty()) // both expire as singles
    }

    @Test
    fun `same-modality events never fuse`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(audio(2, 10_100))
        assertTrue(e.pump(10_700).isEmpty())
    }

    @Test
    fun `three modalities become ONE incident with three witnesses`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(vision(2, 10_100))
        e.onEvent(motion(3, 10_200))
        val out = e.pump(10_800)
        assertEquals(1, out.size)
        assertEquals(setOf(Modality.AUDIO, Modality.VISION, Modality.MOTION), out[0].modalities)
        // One CO_OCCURS edge per later member, all anchored on the first.
        val co = e.relations.filter { it.kind == RelationKind.CO_OCCURS }
        assertEquals(2, co.size)
        assertEquals(listOf(1L, 1L), co.map { it.fromEventId })
        assertEquals(listOf(2L, 3L), co.map { it.toEventId })
    }

    @Test
    fun `environment-only events never fuse`() {
        val e = engine()
        e.onEvent(event(1, 10_000, "PRESSURE_TRANSIENT", Modality.ENVIRONMENT))
        e.onEvent(event(2, 10_050, "LIGHT_CHANGE", Modality.ENVIRONMENT))
        assertTrue(e.pump(10_700).isEmpty())
    }

    @Test
    fun `environment events corroborate but the incident stays audio-vision`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(event(2, 10_050, "MAGNETIC_DISTURBANCE", Modality.ENVIRONMENT))
        e.onEvent(vision(3, 10_100))
        val out = e.pump(10_700)
        assertEquals(1, out.size)
        assertEquals(setOf(Modality.AUDIO, Modality.VISION), out[0].modalities)
    }

    @Test
    fun `sustained events do not fuse but keep SUSTAINED_WITH edges`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(event(2, 10_500, "SUSTAINED_NOISE", Modality.AUDIO, tEndMs = 12_000))
        assertTrue(e.pump(12_600).isEmpty())
        val edge = e.relations.single { it.kind == RelationKind.SUSTAINED_WITH }
        assertEquals(1L, edge.fromEventId)
        assertEquals(2L, edge.toEventId)
    }

    @Test
    fun `sustained shift 1s before an incident derives PRE_INCIDENT_DEVIATION inline`() {
        val e = engine()
        // Vibration burst starts 1.0 s before the impact's group opens.
        e.onEvent(event(1, 9_000, "VIBRATION_BURST", Modality.MOTION, tEndMs = 9_500))
        e.onEvent(audio(2, 10_000))
        e.onEvent(vision(3, 10_100))
        val out = e.pump(10_700)
        assertEquals(2, out.size)
        assertEquals("INCIDENT", out[0].type)
        assertEquals("PRE_INCIDENT_DEVIATION", out[1].type)
        assertEquals(EventTier.PROBABLE, out[1].tier)
        val precedes = e.relations.single { it.kind == RelationKind.PRECEDES }
        assertEquals(1L, precedes.fromEventId)
        assertEquals(out[0].id, precedes.toEventId)
        assertEquals(1_000L, precedes.deltaMs)
    }

    @Test
    fun `sustained shift arriving after the incident is caught by finalize`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(vision(2, 10_100))
        assertTrue(e.pump(10_700).size == 1) // incident closed, watch still empty
        // The audio channel's sustained episode lands late (thread lag).
        e.onEvent(event(3, 9_500, "SUSTAINED_NOISE", Modality.AUDIO, tEndMs = 9_900))
        val out = e.finalizeSession()
        assertEquals(1, out.size)
        assertEquals("PRE_INCIDENT_DEVIATION", out[0].type)
        // The edge runs FROM the late-arriving sustained event (id 3).
        assertEquals(3L, e.relations.single { it.kind == RelationKind.PRECEDES }.fromEventId)
    }

    @Test
    fun `deviation outside the 05-5s lead window is ignored`() {
        val e = engine()
        e.onEvent(event(1, 4_000, "VIBRATION_BURST", Modality.MOTION, tEndMs = 4_500))
        e.onEvent(audio(2, 10_000))
        e.onEvent(vision(3, 10_100))
        val out = e.pump(10_700) // lead 6.0 s > 5 s
        assertEquals(1, out.size) // incident only
        assertTrue(e.relations.none { it.kind == RelationKind.PRECEDES })
    }

    @Test
    fun `deviation closer than 500ms is ignored`() {
        val e = engine()
        e.onEvent(event(1, 9_600, "VIBRATION_BURST", Modality.MOTION, tEndMs = 9_800))
        e.onEvent(audio(2, 10_000))
        e.onEvent(vision(3, 10_100))
        val out = e.pump(10_700) // lead 0.4 s < 0.5 s
        assertEquals(1, out.size)
        assertTrue(e.relations.none { it.kind == RelationKind.PRECEDES })
    }

    @Test
    fun `each incident derives at most one deviation`() {
        val e = engine()
        e.onEvent(event(1, 9_000, "SUSTAINED_NOISE", Modality.AUDIO, tEndMs = 9_200))
        e.onEvent(event(2, 9_300, "VIBRATION_BURST", Modality.MOTION, tEndMs = 9_500))
        e.onEvent(audio(3, 10_000))
        e.onEvent(vision(4, 10_100))
        val out = e.pump(10_700)
        assertEquals(1, out.count { it.type == "PRE_INCIDENT_DEVIATION" })
        // The latest qualifying shift wins.
        assertEquals(9_300L, out.first { it.type == "PRE_INCIDENT_DEVIATION" }.tStartMs)
    }

    @Test
    fun `relations carry delta and the pair window`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(vision(2, 10_100))
        e.pump(10_700)
        val edge = e.relations.single { it.kind == RelationKind.CO_OCCURS }
        assertEquals(100L, edge.deltaMs)
        assertEquals(250L, edge.windowMs)
    }

    @Test
    fun `confidence and salience reward modality count`() {
        val e = engine()
        e.onEvent(audio(1, 10_000))
        e.onEvent(vision(2, 10_100))
        val incident = e.pump(10_700).single()
        // max(0.6, 0.6) + 0.15 × (2−1) = 0.75
        assertEquals(0.75, incident.confidence, 1e-9)
        // mean(0.5, 0.5) + 0.10 × (2−1) = 0.60
        assertEquals(0.60, incident.salience, 1e-9)
    }

    @Test
    fun `speech and steps are never fused`() {
        val e = engine()
        e.onEvent(event(1, 10_000, "SPEECH_PRESENT", Modality.AUDIO))
        e.onEvent(event(2, 10_050, "STEP_DETECTED", Modality.ENVIRONMENT))
        e.onEvent(vision(3, 10_100))
        // Speech can't anchor or join; vision expires alone.
        assertTrue(e.pump(10_700).isEmpty())
    }

    @Test
    fun `extractor and correlation share one id sequence without collisions`() {
        val shared = EventIdSequence()
        val extractor = EventExtractor(ids = shared)
        val corr = CorrelationEngine(shared)
        repeat(400) {
            extractor.onObservation(
                Observation(
                    tMs = it * 20L,
                    modality = Modality.AUDIO,
                    kind = "AUDIO_RMS",
                    value = 1.0,
                    unit = "u",
                ),
                0.0,
            )
        }
        val spike = extractor.onObservation(
            Observation(
                tMs = 9_000,
                modality = Modality.AUDIO,
                kind = "AUDIO_RMS",
                value = 5.0,
                unit = "u",
            ),
            6.0,
        ).single()
        assertEquals(1L, spike.id) // extractor's first event is E1
        corr.onEvent(spike)
        corr.onEvent(vision(shared.nextId(), 9_100)) // vision's own id comes from the same sequence
        val incident = corr.pump(9_700).single()
        // spike=1, vision=2, fused incident=3: the sequence continues, no
        // second E1 anywhere in the session.
        assertEquals(3L, incident.id)
    }
}
