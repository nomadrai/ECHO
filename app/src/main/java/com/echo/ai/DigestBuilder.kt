package com.echo.ai

import com.echo.core.model.Event
import com.echo.core.model.EventTier
import com.echo.core.time.SessionClock
import kotlin.math.min

/**
 * Builds the compact, deterministic session digest an LLM investigates over
 * (plan §9). Pure Kotlin. The digest is the *only* session data a model ever
 * sees: no raw audio, no frames, no device identifiers — just the event
 * timeline with citations, so every answer can be checked against it.
 *
 * A cap keeps slow devices predictable: events beyond [maxEvents] are folded
 * into a per-type count line instead of being silently dropped.
 */
object DigestBuilder {

    /**
     * @param sessionLabel human-facing label (id + wall-clock start)
     * @param durationMs sealed session duration, 0 if still open
     */
    fun build(
        events: List<Event>,
        sessionLabel: String,
        durationMs: Long,
        maxEvents: Int = 120,
    ): String {
        val buf = StringBuilder()
        buf.append("SESSION: ").append(sessionLabel).append('\n')
        buf.append("DURATION: ").append(SessionClock.formatOffset(durationMs)).append('\n')
        if (events.isEmpty()) {
            buf.append("EVENTS: none detected in this session.\n")
            return buf.toString()
        }

        val shown = events.take(maxEvents)
        buf.append("EVENTS: ").append(events.size)
        if (shown.size < events.size) {
            buf.append(" (showing first ").append(shown.size)
            buf.append("; later events omitted)")
        }
        buf.append('\n')
        for (e in shown) {
            buf.append("[E").append(e.id).append(" @ ")
                .append(SessionClock.formatOffset(e.tStartMs)).append("] ")
                .append(e.type)
                .append(" (").append(e.modalities.joinToString("+") { it.name }).append(", ")
                .append(e.tier.name).append(", conf ").append(fmt(e.confidence)).append(")")
            if (e.description.isNotBlank()) {
                buf.append(" — ").append(e.description)
            }
            buf.append('\n')
        }

        // Per-type summary over ALL events (not just the shown ones) so the
        // model keeps the big picture even when the list was capped.
        buf.append("SUMMARY: ")
        buf.append(
            events.groupingBy { it.type }.eachCount().entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.value}x ${it.key}" },
        )
        buf.append('\n')

        // Simple temporal relations the model can cite without hallucinating:
        // nearest-neighbour precedence inside a small window.
        buf.append(buildRelations(events, shown))
        return buf.toString()
    }

    /**
     * Nearest-neighbour relations between events of different types within a
     * 1.5 s window — the deterministic "what followed what" scaffolding that
     * keeps causal-sounding claims grounded.
     */
    private fun buildRelations(all: List<Event>, shown: List<Event>): String {
        if (shown.size < 2) return ""
        val windowMs = 1_500L
        val lines = ArrayList<String>()
        var count = 0
        for (i in shown.indices) {
            val a = shown[i]
            for (j in i + 1 until shown.size) {
                val b = shown[j]
                val dt = b.tStartMs - a.tStartMs
                if (dt > windowMs) break
                if (a.type != b.type && count < MAX_RELATIONS) {
                    lines += "E${a.id} PRECEDES E${b.id} by ${dt}ms"
                    count++
                }
            }
        }
        if (lines.isEmpty()) return ""
        return "RELATIONS (co-occurrence, not causality):\n" +
            lines.take(min(lines.size, MAX_RELATIONS)).joinToString("\n") { it } + "\n"
    }

    private const val MAX_RELATIONS = 40

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
