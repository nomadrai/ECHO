package com.echo.ai

import com.echo.core.model.Event
import com.echo.core.model.EventTier
import com.echo.core.model.ManualTag
import com.echo.core.model.Modality
import com.echo.core.model.TagCategory
import com.echo.data.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trends context is the model's entire cross-session view — its format is
 * a contract: pre-computed stats the model quotes (never re-derives), compact
 * per-session summaries with S# citations, and user tags present as S#T#.
 */
class TrendsAggregatorTest {

    private fun event(id: Long, tMs: Long, type: String, salience: Double = 0.5) = Event(
        id = id,
        tStartMs = tMs,
        tEndMs = tMs,
        type = type,
        modalities = setOf(Modality.AUDIO),
        tier = EventTier.PROBABLE,
        confidence = 0.6,
        salience = salience,
        description = "$type detected",
    )

    private fun record(
        id: Long,
        startedAtEpochMs: Long,
        durationMs: Long,
        goal: String,
        eventCount: Int,
    ) = SessionRecord(
        id = id,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = startedAtEpochMs + durationMs,
        durationMs = durationMs,
        deviceMeta = "",
        eventCount = eventCount,
        eventTypes = "",
        goal = goal,
    )

    @Test
    fun `stats compute averages, most common types, and busiest or quietest sessions`() {
        val records = listOf(
            record(1, 0L, 3_600_000, "motor rig test", 2),
            record(2, 86_400_000L, 3_600_000, "motor rig again", 4),
        )
        val events = mapOf(
            1L to listOf(event(1, 1_000, "IMPACT_TRANSIENT"), event(2, 2_000, "IMPACT_TRANSIENT")),
            2L to (1L..4L).map { event(it, it * 1_000L, "DEVICE_SHOCK") },
        )
        val (stats, summaries) = TrendsAggregator.build(records, events, emptyMap())

        assertEquals(2, stats.sessionCount)
        assertEquals(6, stats.totalEvents)
        assertEquals(3.0, stats.avgEventsPerSession, 1e-9)
        // 4x DEVICE_SHOCK beats 2x IMPACT_TRANSIENT across the set.
        assertEquals("DEVICE_SHOCK", stats.mostCommonTypes.first().first)
        assertEquals(2L, stats.busiestSessionId)
        assertEquals(1L, stats.quietestSessionId)
        assertEquals(2, summaries.size)
    }

    @Test
    fun `rising event counts produce a rising trend direction`() {
        // 1, 2, 3 events on consecutive days → positive slope.
        val records = listOf(
            record(1, 0L, 1_800_000, "sleep", 1),
            record(2, 86_400_000L, 1_800_000, "sleep", 2),
            record(3, 172_800_000L, 1_800_000, "sleep", 3),
        )
        val events = mapOf(
            1L to listOf(event(1, 1_000, "SUSTAINED_NOISE")),
            2L to listOf(event(1, 1_000, "SUSTAINED_NOISE"), event(2, 2_000, "SUSTAINED_NOISE")),
            3L to (1L..3L).map { event(it, it * 1_000L, "SUSTAINED_NOISE") },
        )
        val (stats, _) = TrendsAggregator.build(records, events, emptyMap())
        assertTrue("expected rising, got ${stats.trendDirection}", stats.eventsTrendPerHour > 0)
        assertEquals("rising", stats.trendDirection)
    }

    @Test
    fun `flat series stays flat and single session has no slope`() {
        val flat = listOf(
            record(1, 0L, 1_800_000, "sleep", 2),
            record(2, 86_400_000L, 1_800_000, "sleep", 2),
            record(3, 172_800_000L, 1_800_000, "sleep", 2),
        )
        val events = flat.associate {
            it.id to listOf(event(1, 1_000, "SUSTAINED_NOISE"), event(2, 2_000, "SUSTAINED_NOISE"))
        }
        val (stats, _) = TrendsAggregator.build(flat, events, emptyMap())
        assertEquals("flat", stats.trendDirection)

        val (single, _) = TrendsAggregator.build(
            flat.take(1),
            mapOf(1L to events.getValue(1L)),
            emptyMap(),
        )
        assertEquals(0.0, single.eventsTrendPerHour, 1e-9)
        assertEquals("flat", single.trendDirection)
    }

    @Test
    fun `context blocks carry work type, stats, and per-session summaries`() {
        val records = listOf(
            record(7, 1_000_000L, 28_800_000, "sleeping through the night", 1),
            record(8, 90_000_000L, 28_800_000, "sleeping again", 2),
        )
        val events = mapOf(
            7L to listOf(event(3, 11_520_000, "IMPACT_TRANSIENT")),
            8L to listOf(event(1, 5_000, "SUSTAINED_NOISE"), event(2, 6_000, "SUSTAINED_NOISE")),
        )
        val tags = mapOf(
            7L to listOf(ManualTag(id = 2, tMs = 11_500_000, label = "door slam", category = TagCategory.NOISE)),
        )
        val (stats, summaries) = TrendsAggregator.build(records, events, tags)
        val context = TrendsAggregator.buildContext(stats, summaries, "General experiment", crossType = false)

        assertTrue(context.contains("work type:"))
        assertTrue(context.contains("CROSS-SESSION STATS"))
        assertTrue(context.contains("avg per session: 1.50"))
        assertTrue(context.contains("[S7 ·"))
        assertTrue(context.contains("[S8 ·"))
        // Sessions are newest-first in the summaries.
        assertTrue(context.indexOf("[S8") < context.indexOf("[S7"))
        // Manual tags ride per-session, citable as S#T#-addressable T lines.
        assertTrue(context.contains("(NOISE) door slam"))
        assertTrue(context.contains("S<session>#T<tag>"))
    }

    @Test
    fun `cross-type comparison is marked in the context`() {
        val records = listOf(record(1, 0L, 3_600_000, "motor rig", 0))
        val (stats, summaries) = TrendsAggregator.build(records, emptyMap(), emptyMap())
        val context = TrendsAggregator.buildContext(stats, summaries, "mixed", crossType = true)
        assertTrue(context.contains("INTENTIONAL CROSS-TYPE"))
    }

    @Test
    fun `work domains are derived per session from the goal`() {
        val records = listOf(
            record(1, 0L, 1_000, "DC motor rig under load", 0),
            record(2, 1_000L, 1_000, "sleeping", 0),
        )
        val (_, summaries) = TrendsAggregator.build(records, emptyMap(), emptyMap())
        assertEquals(WorkContext.Domain.MECHANICAL, summaries[0].domain)
        assertEquals(WorkContext.Domain.GENERAL, summaries[1].domain)
        // Grouping key used by the UI: same domain → same group.
        assertEquals(
            summaries[0].domain.displayName,
            TrendsAggregator.build(records, emptyMap(), emptyMap()).second[0].domain.displayName,
        )
    }

    @Test
    fun `key events are the most salient and carry citations`() {
        val events = listOf(
            event(1, 1_000, "SUSTAINED_NOISE", salience = 0.2),
            event(2, 2_000, "INCIDENT", salience = 0.9),
            event(3, 3_000, "DEVICE_SHOCK", salience = 0.8),
            event(4, 4_000, "TONE_CHANGE", salience = 0.1),
            event(5, 5_000, "LIGHT_CHANGE", salience = 0.05),
        )
        val records = listOf(record(1, 0L, 3_600_000, "motor rig", 5))
        val (_, summaries) = TrendsAggregator.build(records, mapOf(1L to events), emptyMap())
        val keys = summaries.single().keyEvents
        assertEquals(4, keys.size)
        assertTrue(keys.first().contains("[E2 @ +00:02.000] INCIDENT"))
        // Capped at 4 — the fifth event never appears.
        assertFalse(keys.any { it.contains("LIGHT_CHANGE") })
    }

    @Test
    fun `stats block states the numbers without raw data`() {
        val stats = TrendsAggregator.CrossStats(
            sessionCount = 3,
            totalEvents = 9,
            avgEventsPerSession = 3.0,
            mostCommonTypes = listOf("IMPACT_TRANSIENT" to 5),
            eventsTrendPerHour = 0.2,
            trendDirection = "rising",
            busiestSessionId = 3,
            quietestSessionId = 1,
            totalTags = 4,
            avgIncidentShare = 0.25,
        )
        val block = TrendsAggregator.buildStatsBlock(stats)
        assertTrue(block.contains("sessions compared: 3"))
        assertTrue(block.contains("avg per session: 3.00"))
        assertTrue(block.contains("rising"))
        assertFalse(block.lowercase().contains("wav"))
        assertFalse(block.lowercase().contains("base64"))
    }
}
