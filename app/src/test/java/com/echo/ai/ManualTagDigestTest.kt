package com.echo.ai

import com.echo.core.model.Event
import com.echo.core.model.EventTier
import com.echo.core.model.ManualTag
import com.echo.core.model.Modality
import com.echo.core.model.TagCategory
import com.echo.core.model.TimelineSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual tags must reach the model as their own citable section (T# ids) —
 * separate from EVENTS, never raw data — with near-coincident auto events
 * annotated, so the model can reason about human-vs-detector agreement.
 */
class ManualTagDigestTest {

    private fun event(id: Long, tMs: Long, type: String = "IMPACT_TRANSIENT") = Event(
        id = id,
        tStartMs = tMs,
        tEndMs = tMs,
        type = type,
        modalities = setOf(Modality.AUDIO),
        tier = EventTier.PROBABLE,
        confidence = 0.6,
        salience = 0.5,
        description = "$type detected",
    )

    @Test
    fun `no tags means no tags section`() {
        val digest = DigestBuilder.build(listOf(event(1, 1_000)), "S", 5_000)
        assertFalse(digest.contains("MANUAL TAGS"))
    }

    @Test
    fun `tag lines carry T citations, category, and timestamp`() {
        val digest = DigestBuilder.build(
            events = listOf(event(1, 1_000)),
            sessionLabel = "S",
            durationMs = 5_000,
            tags = listOf(
                ManualTag(
                    id = 4,
                    tMs = 134_000,
                    label = "unusual noise",
                    category = TagCategory.NOISE,
                    source = TimelineSource.USER,
                ),
            ),
        )
        assertTrue(digest.contains("[T4 @ +02:14.000] (NOISE) unusual noise"))
        // Tags are their own section — never folded into the EVENTS list.
        assertTrue(digest.contains("distinct from detected events"))
    }

    @Test
    fun `tag within one second of an event is annotated with the citation`() {
        val digest = DigestBuilder.build(
            events = listOf(event(2, 134_200)),
            sessionLabel = "S",
            durationMs = 5_000,
            tags = listOf(
                ManualTag(id = 1, tMs = 134_000, label = "unusual noise"),
            ),
        )
        assertTrue(digest.contains("near-coincident with E2 (@ +02:14.200, IMPACT_TRANSIENT)"))
    }

    @Test
    fun `tag far from any event has no coincidence annotation`() {
        val digest = DigestBuilder.build(
            events = listOf(event(2, 60_000)),
            sessionLabel = "S",
            durationMs = 5_000,
            tags = listOf(
                ManualTag(id = 1, tMs = 134_000, label = "unusual noise"),
            ),
        )
        assertFalse(digest.contains("near-coincident"))
    }

    @Test
    fun `coincidence picks the nearest event inside the window`() {
        val digest = DigestBuilder.build(
            events = listOf(
                event(2, 133_800),
                event(3, 134_700),
                event(4, 200_000),
            ),
            sessionLabel = "S",
            durationMs = 5_000,
            tags = listOf(
                ManualTag(id = 1, tMs = 134_000, label = "unusual noise"),
            ),
        )
        // E2 is 200 ms away, E3 is 700 ms — both inside the 1 s window; the
        // nearer one must be cited.
        assertTrue(digest.contains("near-coincident with E2 "))
    }
}
