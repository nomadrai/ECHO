package com.echo.ai

import com.echo.core.model.Event
import com.echo.core.model.EventRelation
import com.echo.core.model.RelationKind
import com.echo.core.time.SessionClock
import kotlin.math.min

/**
 * Builds the compact, deterministic session digest an LLM investigates over
 * (plan §9). Pure Kotlin. The digest is the *only* session data a model ever
 * sees: no raw audio, no frames, no device identifiers — just the event
 * timeline with citations, the M1 relation edges, and the work context, so
 * every answer can be checked against it.
 *
 * A cap keeps slow devices predictable: events beyond [maxEvents] are folded
 * into a per-type count line instead of being silently dropped.
 */
object DigestBuilder {

    /**
     * @param sessionLabel human-facing label (id + wall-clock start)
     * @param durationMs sealed session duration, 0 if still open
     * @param goal what the user said they were building; calibrates what
     * counts as "normal" for this session ([WorkContext])
     * @param relations persisted correlation edges (CO_OCCURS / SUSTAINED_WITH
     * / PRECEDES) — the model cites these instead of guessing adjacency
     */
    fun build(
        events: List<Event>,
        sessionLabel: String,
        durationMs: Long,
        maxEvents: Int = 120,
        goal: String = "",
        relations: List<EventRelation> = emptyList(),
    ): String {
        val buf = StringBuilder()
        buf.append("SESSION: ").append(sessionLabel).append('\n')
        buf.append("DURATION: ").append(SessionClock.formatOffset(durationMs)).append('\n')
        buf.append(buildWorkContext(goal))
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
            if (e.tStartMs < BASELINE_MS) buf.append(" [baseline-calibration]")
            if (e.description.isNotBlank()) {
                buf.append(" — ").append(e.description)
            }
            buf.append('\n')
        }

        // Fused incidents first-class: the demo's "what went wrong" targets.
        buf.append(buildIncidentSummary(events))

        // Per-type summary over ALL events (not just the shown ones) so the
        // model keeps the big picture even when the list was capped.
        buf.append("SUMMARY: ")
        buf.append(
            events.groupingBy { it.type }.eachCount().entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.value}x ${it.key}" },
        )
        buf.append('\n')

        // Correlation edges from the M1 engine (authoritative): relations the
        // model may cite instead of inferring adjacency from timestamps.
        buf.append(buildRelations(relations, shown))
        return buf.toString()
    }

    /** The goal + what signals are expected-normal for this kind of work. */
    private fun buildWorkContext(goal: String): String {
        val buf = StringBuilder()
        if (goal.isNotBlank()) {
            buf.append("USER'S STATED GOAL: ").append(goal.trim()).append('\n')
        }
        val profile = WorkContext.classify(goal)
        buf.append("WORK CONTEXT: ").append(profile.domain.displayName).append('\n')
        buf.append("EXPECTED-NORMAL FOR THIS WORK: ")
            .append(profile.expectedNormal.joinToString("; ")).append('\n')
        buf.append("INTERPRETATION GUIDANCE: ").append(profile.guidance).append('\n')
        return buf.toString()
    }

    /** One line per fused incident: tier, modalities, member events. */
    private fun buildIncidentSummary(events: List<Event>): String {
        val incidents = events.filter { it.type == "INCIDENT" }
        if (incidents.isEmpty()) return ""
        val lines = incidents.joinToString("; ") { inc ->
            // Members are the single-mode events in the incident's window.
            val members = events.filter {
                it.id != inc.id && it.type != "INCIDENT" &&
                    it.type != "PRE_INCIDENT_DEVIATION" &&
                    it.tStartMs >= inc.tStartMs && it.tStartMs <= inc.tEndMs
            }
            "E${inc.id} @ ${SessionClock.formatOffset(inc.tStartMs)} " +
                "${inc.tier} (${inc.modalities.joinToString("+") { m -> m.name }})" +
                if (members.isEmpty()) "" else
                    " from " + members.joinToString("+") { "E${it.id}" }
        }
        return "FUSED INCIDENTS: $lines\n"
    }

    /**
     * Relation edges measured by the correlation engine. All three kinds are
     * rendered; labels stay co-occurrence, never causality.
     */
    private fun buildRelations(relations: List<EventRelation>, shown: List<Event>): String {
        if (relations.isEmpty() || shown.size < 2) return ""
        val ids = shown.map { it.id }.toHashSet()
        val lines = relations
            .filter { it.fromEventId in ids && it.toEventId in ids }
            .take(MAX_RELATIONS)
            .map { r ->
                // Direction follows the engine: from = the earlier event.
                val kind = when (r.kind) {
                    RelationKind.PRECEDES -> "PRECEDES"
                    RelationKind.SUSTAINED_WITH -> "SUSTAINED WITH"
                    RelationKind.CO_OCCURS -> "CO-OCCURRED WITH"
                }
                "E${r.fromEventId} $kind E${r.toEventId} (Δt ${r.deltaMs} ms, conf ${fmt(r.confidence)})"
            }
        if (lines.isEmpty()) return ""
        return "RELATIONS (measured co-occurrence, not causality):\n" +
            lines.joinToString("\n") + "\n"
    }

    /**
     * The baseline window: observations here calibrate "normal" — events
     * inside it are the device learning the room, not incidents.
     */
    const val BASELINE_MS = 30_000L

    private const val MAX_RELATIONS = 40

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
