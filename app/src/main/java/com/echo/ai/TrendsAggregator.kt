package com.echo.ai

import com.echo.core.model.Event
import com.echo.core.model.ManualTag
import com.echo.core.model.TagCategory
import com.echo.core.time.SessionClock
import com.echo.data.SessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cross-session trends (plan §9 local-first reduction, applied across
 * sessions): every number the model reasons over is computed here,
 * deterministically, on-device — the model never re-derives basic math and
 * never sees raw sensor data. Pure Kotlin; JVM-testable.
 *
 * The context is two-layered:
 *  1. CROSS-SESSION STATS — the pre-aggregated numbers (averages, most
 *     common types, trend direction over time, comparisons), so answers can
 *     quote measured figures directly.
 *  2. SESSION SUMMARIES — one compact block per session (date, work, duration,
 *     per-type counts, key events with E# citations, manual tags with T#
 *     citations), condensed from the same persisted timeline the
 *     single-session digest uses.
 */
object TrendsAggregator {

    /** One selected session, condensed. [domain] comes from [WorkContext]. */
    data class SessionSummary(
        val sessionId: Long,
        val startedAtEpochMs: Long,
        val durationMs: Long,
        val goal: String,
        val domain: WorkContext.Domain,
        val eventCount: Int,
        val incidentCount: Int,
        val tagCount: Int,
        /** type → count, descending by count. */
        val typeCounts: Map<String, Int>,
        /** Most salient events, cited as the single-session digest cites them. */
        val keyEvents: List<String>,
        /** Manual tags, rendered "[T4 @ +00:12.000] (NOISE) label". */
        val tagLines: List<String>,
    ) {
        val dateLabel: String =
            SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(startedAtEpochMs))
    }

    /** The deterministic cross-session statistics block. */
    data class CrossStats(
        val sessionCount: Int,
        val totalEvents: Int,
        val avgEventsPerSession: Double,
        /** Top event types across the set, type → total count. */
        val mostCommonTypes: List<Pair<String, Int>>,
        /** Least-squares slope of events-per-session over wall-clock hours. */
        val eventsTrendPerHour: Double,
        /** "rising" | "falling" | "flat" — threshold on the slope. */
        val trendDirection: String,
        val busiestSessionId: Long?,
        val quietestSessionId: Long?,
        val totalTags: Int,
        /** Avg incident share per session, 0..1 (0 when no events at all). */
        val avgIncidentShare: Double,
    )

    /** Session blocks beyond this are folded into "N older sessions omitted". */
    private const val MAX_SESSION_BLOCKS = 20

    /** Key events shown per session block. */
    private const val MAX_KEY_EVENTS = 4

    /**
     * Slope (events/hour) below which the trend reads "flat". 0.005/h ≈ one
     * extra event per 8 days — slower than that is drift, not a trend; the
     * typical day-to-day swing of ±1 event/day (0.042/h) clears it clearly.
     */
    private const val TREND_EPSILON = 0.005

    /**
     * @param records the comparison set (any order; sorted internally)
     * @param events per-session events ([com.echo.data.EchoStore.eventsForSession])
     * @param tags per-session manual tags ([com.echo.data.EchoStore.tagsForSession])
     * @param typeCounts per-session per-type counts
     * ([com.echo.data.EchoStore.eventTypeCountsForSessions]) — falls back to
     * grouping [events] when absent, so JVM tests can skip the store pass.
     */
    fun build(
        records: List<SessionRecord>,
        events: Map<Long, List<Event>>,
        tags: Map<Long, List<ManualTag>>,
        typeCounts: Map<Long, Map<String, Int>> = emptyMap(),
    ): Pair<CrossStats, List<SessionSummary>> {
        val chrono = records.sortedBy { it.id } // id order == creation order
        val summaries = chrono.map { rec ->
            val evs = events[rec.id].orEmpty()
            val tgs = tags[rec.id].orEmpty()
            val counts = typeCounts[rec.id] ?: evs.groupingBy { it.type }.eachCount()
            SessionSummary(
                sessionId = rec.id,
                startedAtEpochMs = rec.startedAtEpochMs,
                durationMs = rec.durationMs,
                goal = rec.goal,
                domain = WorkContext.classify(rec.goal).domain,
                eventCount = rec.eventCount,
                incidentCount = evs.count { it.type == "INCIDENT" },
                tagCount = tgs.size,
                typeCounts = counts.entries.sortedByDescending { it.value }
                    .associate { it.key to it.value },
                keyEvents = evs
                    .sortedWith(
                        compareByDescending<Event> { it.salience }.thenBy { it.tStartMs },
                    )
                    .take(MAX_KEY_EVENTS)
                    .map { e ->
                        "[E${e.id} @ ${SessionClock.formatOffset(e.tStartMs)}] " +
                            "${e.type} (${e.tier.name}, conf ${fmt(e.confidence)})"
                    },
                tagLines = tgs.map { t ->
                    val cat = if (t.category == TagCategory.NOTE) "" else " (${t.category.name}) "
                    "[T${t.id} @ ${SessionClock.formatOffset(t.tMs)}]$cat${t.label}"
                },
            )
        }
        return computeStats(chrono, events, tags) to summaries
    }

    /**
     * The full trends context: stats block + one compact block per session.
     * [groupLabel] names the comparison set's work domain; [crossType] marks
     * an intentional cross-domain comparison so the model treats domain
     * differences as signal instead of noise.
     */
    fun buildContext(
        stats: CrossStats,
        summaries: List<SessionSummary>,
        groupLabel: String,
        crossType: Boolean,
    ): String {
        val buf = StringBuilder()
        buf.append("COMPARISON SET: ${summaries.size} sessions")
        buf.append(if (crossType) " · INTENTIONAL CROSS-TYPE comparison\n" else " · work type: $groupLabel\n")
        buf.append("COMPARISON IS ACROSS SESSIONS: cite sessions by their S# id and date; ")
            .append("events as S<session>#E<event>; tags as S<session>#T<tag>.\n")
        buf.append(buildStatsBlock(stats))
        buf.append('\n')
        buf.append("SESSION SUMMARIES (newest first):\n")
        val shown = summaries.sortedByDescending { it.startedAtEpochMs }.take(MAX_SESSION_BLOCKS)
        for (s in shown) {
            buf.append("\n[S${s.sessionId} · ${s.dateLabel}] ${s.domain.displayName}")
                .append(" · ").append(SessionClock.formatOffset(s.durationMs).removePrefix("+"))
                .append(" duration\n")
            if (s.goal.isNotBlank()) buf.append("  goal: ").append(s.goal.trim()).append('\n')
            buf.append("  events: ${s.eventCount} (incidents ${s.incidentCount}) · tags: ${s.tagCount}\n")
            if (s.typeCounts.isNotEmpty()) {
                buf.append("  types: ")
                    .append(s.typeCounts.entries.joinToString(", ") { "${it.value}x ${it.key}" })
                    .append('\n')
            }
            if (s.keyEvents.isNotEmpty()) {
                buf.append("  key events:\n")
                s.keyEvents.forEach { buf.append("    ").append(it).append('\n') }
            }
            if (s.tagLines.isNotEmpty()) {
                buf.append("  manual tags (user-created):\n")
                s.tagLines.forEach { buf.append("    ").append(it).append('\n') }
            }
        }
        if (summaries.size > shown.size) {
            buf.append("\n(+${summaries.size - shown.size} older sessions omitted from detail; ")
                .append("the STATS block still covers all ${stats.sessionCount}.)\n")
        }
        return buf.toString()
    }

    /** The deterministic numbers block — compact, quotable, pre-computed. */
    fun buildStatsBlock(stats: CrossStats): String {
        val buf = StringBuilder()
        buf.append("CROSS-SESSION STATS (pre-computed; quote these, do not re-derive):\n")
        buf.append("  sessions compared: ${stats.sessionCount}\n")
        buf.append("  total events: ${stats.totalEvents} · avg per session: ${fmt(stats.avgEventsPerSession)}\n")
        if (stats.mostCommonTypes.isNotEmpty()) {
            buf.append("  most common types: ")
                .append(stats.mostCommonTypes.joinToString(", ") { "${it.second}x ${it.first}" })
                .append('\n')
        }
        buf.append("  events-per-session trend over time: ${fmt(stats.eventsTrendPerHour)} events/hour ")
            .append("(${stats.trendDirection})\n")
        stats.busiestSessionId?.let {
            buf.append("  busiest session: S$it\n")
        }
        stats.quietestSessionId?.let {
            buf.append("  quietest session: S$it\n")
        }
        buf.append("  avg incident share: ${fmt(stats.avgIncidentShare)}\n")
        buf.append("  total manual tags: ${stats.totalTags}\n")
        return buf.toString()
    }

    private fun computeStats(
        chrono: List<SessionRecord>,
        events: Map<Long, List<Event>>,
        tags: Map<Long, List<ManualTag>>,
    ): CrossStats {
        val n = chrono.size
        val counts = chrono.map { rec -> events[rec.id].orEmpty().size }
        val total = counts.sum()
        val typeTotals = HashMap<String, Int>()
        events.values.forEach { evs ->
            evs.groupingBy { it.type }.eachCount().forEach { (t, c) ->
                typeTotals[t] = (typeTotals[t] ?: 0) + c
            }
        }
        // Least-squares slope of event count vs wall-clock time (hours): the
        // deterministic trend the model can quote instead of guessing.
        var slope = 0.0
        if (n >= 2) {
            val t0 = chrono.first().startedAtEpochMs
            val xs = chrono.map { (it.startedAtEpochMs - t0) / 3_600_000.0 }
            val meanX = xs.average()
            val meanY = counts.average()
            var num = 0.0
            var den = 0.0
            xs.zip(counts) { x, y ->
                num += (x - meanX) * (y - meanY)
                den += (x - meanX) * (x - meanX)
            }
            if (den > 0) slope = num / den
        }
        val busiest = chrono.maxByOrNull { events[it.id].orEmpty().size }?.id
        val quietest = chrono.minByOrNull { events[it.id].orEmpty().size }?.id
        val incidentShare = events.values.map { evs ->
            if (evs.isEmpty()) 0.0 else evs.count { it.type == "INCIDENT" }.toDouble() / evs.size
        }
        return CrossStats(
            sessionCount = n,
            totalEvents = total,
            avgEventsPerSession = if (n == 0) 0.0 else total.toDouble() / n,
            mostCommonTypes = typeTotals.entries.sortedByDescending { it.value }
                .take(5).map { it.key to it.value },
            eventsTrendPerHour = slope,
            trendDirection = when {
                slope > TREND_EPSILON -> "rising"
                slope < -TREND_EPSILON -> "falling"
                else -> "flat"
            },
            busiestSessionId = busiest,
            quietestSessionId = quietest,
            totalTags = tags.values.sumOf { it.size },
            avgIncidentShare = if (events.values.isEmpty()) 0.0 else incidentShare.average(),
        )
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
