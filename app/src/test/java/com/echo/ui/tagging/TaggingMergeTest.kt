package com.echo.ui.tagging

import com.echo.core.model.Event
import com.echo.core.model.EventTier
import com.echo.core.model.ManualTag
import com.echo.core.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merged timeline is the display contract: one chronological list, two
 * clearly distinguished sources (AUTO_DETECTED vs USER) that never collapse
 * into a single object.
 */
class TaggingMergeTest {

    private fun event(id: Long, tMs: Long) = Event(
        id = id,
        tStartMs = tMs,
        tEndMs = tMs,
        type = "IMPACT_TRANSIENT",
        modalities = setOf(Modality.AUDIO),
        tier = EventTier.PROBABLE,
        confidence = 0.6,
        salience = 0.5,
        description = "x",
    )

    @Test
    fun `events and tags interleave chronologically`() {
        val merged = mergeTimeline(
            events = listOf(event(1, 5_000), event(2, 1_000)),
            tags = listOf(ManualTag(id = 1, tMs = 3_000, label = "mid")),
        )
        assertEquals(listOf(1_000L, 3_000L, 5_000L), merged.map { it.tMs })
    }

    @Test
    fun `sources stay distinct`() {
        val merged = mergeTimeline(
            events = listOf(event(1, 1_000)),
            tags = listOf(ManualTag(id = 1, tMs = 1_000, label = "same moment")),
        )
        val sources = merged.map { it.source.name }
        assertTrue(sources.containsAll(listOf("AUTO_DETECTED", "USER")))
        // No row is both; no row is neither.
        assertTrue(merged.all { it.source == com.echo.core.model.TimelineSource.AUTO_DETECTED || it.source == com.echo.core.model.TimelineSource.USER })
    }

    @Test
    fun `citations use E for events and T for tags`() {
        val merged = mergeTimeline(
            events = listOf(event(7, 1_000)),
            tags = listOf(ManualTag(id = 3, tMs = 2_000, label = "x")),
        )
        assertEquals("E7", merged[0].citation)
        assertEquals("T3", merged[1].citation)
    }

    @Test
    fun `ties put the user tag first at equal timestamps`() {
        val merged = mergeTimeline(
            events = listOf(event(1, 1_000)),
            tags = listOf(ManualTag(id = 1, tMs = 1_000, label = "tie")),
        )
        // Deliberate: at the same instant the human's note reads first.
        assertTrue(merged.first() is TimelineItem.TagItem)
        assertTrue(merged.last() is TimelineItem.EventItem)
    }
}
