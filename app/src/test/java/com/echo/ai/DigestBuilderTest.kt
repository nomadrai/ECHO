package com.echo.ai

import com.echo.core.model.Event
import com.echo.core.model.EventTier
import com.echo.core.model.Modality
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
    ) = Event(
        id = id,
        tStartMs = tMs,
        tEndMs = tMs,
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
    fun `cross-type near neighbours produce PRECEDES relations`() {
        val events = listOf(
            event(1, 1_000, "IMPACT_TRANSIENT"),
            event(2, 1_400, "DEVICE_SHOCK", Modality.MOTION),
        )
        val digest = DigestBuilder.build(events, "S", 5_000)
        assertTrue(digest.contains("E1 PRECEDES E2 by 400ms"))
        assertTrue(digest.contains("not causality"))
    }

    @Test
    fun `same-type events do not produce relations`() {
        val events = listOf(
            event(1, 1_000, "IMPACT_TRANSIENT"),
            event(2, 1_200, "IMPACT_TRANSIENT"),
        )
        val digest = DigestBuilder.build(events, "S", 5_000)
        assertFalse(digest.contains("PRECEDES"))
    }

    @Test
    fun `events outside the relation window are unrelated`() {
        val events = listOf(
            event(1, 1_000, "IMPACT_TRANSIENT"),
            event(2, 5_000, "DEVICE_SHOCK", Modality.MOTION),
        )
        val digest = DigestBuilder.build(events, "S", 6_000)
        assertFalse(digest.contains("PRECEDES"))
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
