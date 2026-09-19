package com.echo.ai

import com.echo.core.model.Event
import com.echo.core.model.EventRelation
import com.echo.core.model.EventTier
import com.echo.core.model.Modality
import com.echo.core.model.RelationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digest is the model's entire view of a session — its format is a
 * contract: parseable event lines with citations, a summary over ALL events,
 * and relations labelled as co-occurrence.
 */
class DigestBuilderTest {

    private fun event(
        id: Long,
        tMs: Long,
        type: String,
        modality: Modality = Modality.AUDIO,
        tEndMs: Long = tMs,
    ) = Event(
        id = id,
        tStartMs = tMs,
        tEndMs = tEndMs,
        type = type,
        modalities = setOf(modality),
        tier = EventTier.PROBABLE,
        confidence = 0.6,
        salience = 0.5,
        description = "$type detected",
    )

    @Test
    fun `empty session states so explicitly`() {
        val digest = DigestBuilder.build(emptyList(), "Session #1", 5_000)
        assertTrue(digest.contains("EVENTS: none"))
    }

    @Test
    fun `event lines carry citations and tier`() {
        val digest = DigestBuilder.build(
            listOf(event(1, 24_873, "IMPACT_TRANSIENT")),
            "Session #1",
            30_000,
        )
        assertTrue(digest.contains("[E1 @ +00:24.873] IMPACT_TRANSIENT"))
        assertTrue(digest.contains("(AUDIO, PROBABLE, conf 0.60)"))
        assertTrue(digest.contains("SUMMARY: 1x IMPACT_TRANSIENT"))
    }

    @Test
    fun `summary counts all events even when the list is capped`() {
        val events = (1L..150L).map { event(it, it * 100, "TYPE_${it % 3}") }
        val digest = DigestBuilder.build(events, "S", 15_000, maxEvents = 100)
        // Capped list...
        assertTrue(digest.contains("showing first 100"))
        // ...but the summary still covers all 150 (50 per type).
        assertTrue(digest.contains("50x TYPE_"))
    }

    @Test
    fun `measured relation edges are rendered with kind and delta`() {
        val events = listOf(
            event(1, 1_000, "IMPACT_TRANSIENT"),
            event(2, 1_400, "DEVICE_SHOCK", Modality.MOTION),
        )
        val digest = DigestBuilder.build(
            events = events,
            sessionLabel = "S",
            durationMs = 5_000,
            relations = listOf(
                EventRelation(1, 2, RelationKind.CO_OCCURS, deltaMs = 400, windowMs = 400, confidence = 0.75),
            ),
        )
        assertTrue(digest.contains("E1 CO-OCCURRED WITH E2 (Δt 400 ms"))
        assertTrue(digest.contains("not causality"))
    }

    @Test
    fun `precedes edges render as preceded-by`() {
        val events = listOf(
            event(1, 1_000, "SUSTAINED_NOISE"),
            event(2, 4_000, "INCIDENT", Modality.MOTION),
        )
        val digest = DigestBuilder.build(
            events = events,
            sessionLabel = "S",
            durationMs = 5_000,
            relations = listOf(
                EventRelation(1, 2, RelationKind.PRECEDES, deltaMs = 3_000, windowMs = 5_000, confidence = 0.7),
            ),
        )
        assertTrue(digest.contains("E1 PRECEDES E2 (Δt 3000 ms"))
    }

    @Test
    fun `relations referencing capped-out events are dropped`() {
        val events = listOf(event(1, 1_000, "IMPACT_TRANSIENT"))
        val digest = DigestBuilder.build(
            events = events,
            sessionLabel = "S",
            durationMs = 5_000,
            relations = listOf(
                EventRelation(1, 99, RelationKind.CO_OCCURS, deltaMs = 10, windowMs = 500, confidence = 0.5),
            ),
        )
        assertFalse(digest.contains("RELATIONS"))
    }

    @Test
    fun `work context block carries domain and expected-normal signals`() {
        val digest = DigestBuilder.build(
            events = listOf(event(1, 40_000, "IMPACT_TRANSIENT")),
            sessionLabel = "S",
            durationMs = 60_000,
            goal = "DC motor rig failure under load",
        )
        assertTrue(digest.contains("WORK CONTEXT: Mechanical"))
        assertTrue(digest.contains("EXPECTED-NORMAL"))
        assertTrue(digest.contains("VIBRATION_BURST"))
        assertTrue(digest.contains("USER'S STATED GOAL: DC motor rig"))
    }

    @Test
    fun `events inside the baseline window are tagged`() {
        val digest = DigestBuilder.build(
            events = listOf(
                event(1, 12_000, "SUSTAINED_NOISE"),
                event(2, 45_000, "IMPACT_TRANSIENT"),
            ),
            sessionLabel = "S",
            durationMs = 60_000,
        )
        assertTrue(digest.contains("[E1 @ +00:12.000] SUSTAINED_NOISE"))
        assertTrue(digest.contains("[baseline-calibration]"))
        // The post-baseline event must NOT be tagged.
        val line2 = digest.lineSequence().first { it.startsWith("[E2") }
        assertFalse(line2.contains("baseline-calibration"))
    }

    @Test
    fun `fused incidents get their own summary line with members`() {
        val incident = Event(
            id = 3,
            tStartMs = 41_000,
            tEndMs = 41_200,
            type = "INCIDENT",
            modalities = setOf(Modality.AUDIO, Modality.MOTION),
            tier = EventTier.CONFIRMED,
            confidence = 0.75,
            salience = 0.7,
            description = "Fused incident",
        )
        val digest = DigestBuilder.build(
            events = listOf(
                event(1, 41_000, "IMPACT_TRANSIENT"),
                event(2, 41_100, "DEVICE_SHOCK", Modality.MOTION),
                incident,
            ),
            sessionLabel = "S",
            durationMs = 60_000,
        )
        assertTrue(digest.contains("FUSED INCIDENTS: E3 @ +00:41.000 CONFIRMED (AUDIO+MOTION) from E1+E2"))
    }

    @Test
    fun `digest never contains raw sensor payloads`() {
        // The privacy contract: only event metadata, never media or streams.
        val digest = DigestBuilder.build(
            listOf(event(1, 1_000, "IMPACT_TRANSIENT")),
            "Session #1",
            5_000,
        )
        assertFalse(digest.contains("wav"))
        assertFalse(digest.contains("base64"))
    }
}
