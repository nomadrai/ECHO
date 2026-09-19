package com.echo.fusion

import com.echo.core.model.Event
import com.echo.core.model.EventRelation
import com.echo.core.model.EventTier
import com.echo.core.model.Modality
import com.echo.core.model.RelationKind
import com.echo.core.time.SessionClock

/**
 * M1 correlation engine (plan §7): fuses co-occurring single-modality events
 * into CONFIRMED INCIDENTs and derives the relation edges the dashboard and
 * the M3 investigator cite. Pure Kotlin, deterministic, thread-safe.
 *
 * Fusion model — open groups, not pairwise pairs:
 *  - every fusible transient either joins an open cross-modal group or opens
 *    a new one, so impact + rapid motion + device shock within their windows
 *    become ONE INCIDENT with 3 modalities (the plan §11 demo);
 *  - a group closes [GROUP_CLOSE_MS] after its latest member ([pump], called
 *    with every observation timestamp) or at session end ([finalizeSession]).
 *    600 ms > every pair window, and the pipeline joins members *before*
 *    pumping, so a Δt exactly at the window boundary still fuses;
 *  - groups with a single member are discarded — the transient was already
 *    persisted and shown on its own.
 *
 * Tiers (plan §7): CONFIRMED needs ≥2 modalities in the group; a same-modality
 * cluster (rare — the extractor dedups per kind) stays PROBABLE.
 *
 * Relations — always co-occurrence, never causality:
 *  - CO_OCCURS from the first member to each later member, with Δt and the
 *    pair's window;
 *  - SUSTAINED_WITH when a sustained event covers a recent transient;
 *  - PRECEDES + a derived PRE_INCIDENT_DEVIATION event when a sustained shift
 *    began 0.5–5 s before an INCIDENT — the "why" the investigator cites.
 *
 * @param ids shared with the [EventExtractor] so runtime ids stay unique
 * @param enableDeviationWatch set false to disable PRE_INCIDENT_DEVIATION
 */
class CorrelationEngine(
    private val ids: EventIdSequence,
    private val enableDeviationWatch: Boolean = true,
) {
    /** An open cross-modal fusion group; closed after [GROUP_CLOSE_MS]. */
    private class FuseGroup(first: Event) {
        val members = ArrayList<Event>().apply { add(first) }
        val modalities = HashSet<Modality>().apply { addAll(first.modalities) }
        var latest: Event = first
        var latestT: Long = first.tStartMs
    }

    private val groups = ArrayList<FuseGroup>()
    private val incidents = ArrayList<Event>()
    private val deviationDoneFor = HashSet<Long>()
    private val deviationWatch = ArrayDeque<Event>()
    private val recent = ArrayDeque<Event>()

    /** Relation edges for the current session, in emission order. */
    val relations = ArrayList<EventRelation>()

    /**
     * Feed one extracted event. Returns fused events only when the incoming
     * event closes a group itself (rare — closing normally happens in
     * [pump]); usually empty. The pipeline calls this before [pump] so a
     * member at the exact window boundary joins before the group closes.
     */
    @Synchronized
    fun onEvent(event: Event): List<Event> {
        val sustained = isSustainedType(event.type)

        if (sustained) {
            // The sustained episode was ongoing over the last transient.
            recent.lastOrNull {
                !isSustainedType(it.type) && it.type != "INCIDENT" &&
                    event.tStartMs - it.tStartMs in 0..SUSTAINED_COVER_MS
            }?.let { covered ->
                relations += EventRelation(
                    fromEventId = covered.id,
                    toEventId = event.id,
                    kind = RelationKind.SUSTAINED_WITH,
                    deltaMs = event.tStartMs - covered.tStartMs,
                    windowMs = SUSTAINED_COVER_MS,
                    confidence = minOf(covered.confidence, event.confidence),
                )
            }
            if (enableDeviationWatch) {
                deviationWatch.addLast(event)
                while (deviationWatch.size > DEVIATION_KEEP) deviationWatch.removeFirst()
            }
        }

        // Environment channels are transient-only context: they corroborate
        // but never fuse (a barometer blip alone must not create an incident).
        val modality = event.modalities.firstOrNull()
        val fusible = !sustained && event.type != "INCIDENT" &&
            event.type !in NOT_FUSIBLE && modality != Modality.ENVIRONMENT

        if (fusible && modality != null) {
            var target: FuseGroup? = null
            for (g in groups) {
                if (modality in g.modalities) continue // one witness per modality
                if (event.tStartMs - g.latestT > windowFor(g.latest, event)) continue
                if (target == null || g.latestT > target.latestT) target = g
            }
            if (target != null) {
                target.members += event
                target.modalities += event.modalities
                target.latest = event
                target.latestT = event.tStartMs
            } else {
                groups += FuseGroup(event)
            }
        }

        recent.addLast(event)
        while (recent.size > MAX_RECENT) recent.removeFirst()
        return emptyList()
    }

    /**
     * Advance the clock to [nowMs]: close every group old enough that no
     * future event can join it (600 ms > every pair window). Called with each
     * observation's timestamp by the pipeline, so fusion latency stays well
     * under a second on live channels. Returns emitted [INCIDENT]s and any
     * derived deviations, in emission order.
     */
    @Synchronized
    fun pump(nowMs: Long): List<Event> = flushGroups { g -> nowMs - g.latestT >= GROUP_CLOSE_MS }

    /**
     * Session end: close remaining groups, then sweep for incidents whose
     * pre-incident sustained shift only arrived after they closed. Returns
     * all derived events (incidents + deviations) in emission order.
     */
    @Synchronized
    fun finalizeSession(): List<Event> {
        val out = ArrayList(flushGroups { _ -> true })
        for (incident in incidents) {
            deviationFor(incident)?.let { out += it }
        }
        return out
    }

    // --- internals ----------------------------------------------------------

    private fun flushGroups(ready: (FuseGroup) -> Boolean): List<Event> {
        val out = ArrayList<Event>(2)
        val iterator = groups.iterator()
        while (iterator.hasNext()) {
            val g = iterator.next()
            if (!ready(g)) continue
            iterator.remove()
            if (g.members.size >= 2) out += closeGroup(g)
            // Single-member groups expire silently: the transient was
            // already persisted and shown on its own.
        }
        return out
    }

    private fun closeGroup(g: FuseGroup): List<Event> {
        val first = g.members.first()
        val spanEnd = g.members.maxOf { it.tEndMs }
        val confidence = (
            g.members.maxOf { it.confidence } +
                CONFIDENCE_PER_MODALITY * (g.modalities.size - 1)
            ).coerceIn(0.0, 0.95)
        val salience = (
            g.members.sumOf { it.salience } / g.members.size +
                SALIENCE_PER_MODALITY * (g.modalities.size - 1)
            ).coerceIn(0.1, 1.0)
        val incident = Event(
            id = ids.nextId(),
            sessionId = first.sessionId,
            tStartMs = first.tStartMs,
            tEndMs = spanEnd,
            type = "INCIDENT",
            modalities = g.modalities.toSet(),
            tier = if (g.modalities.size >= 2) EventTier.CONFIRMED else EventTier.PROBABLE,
            confidence = confidence,
            salience = salience,
            description = "Fused incident: " +
                g.members.joinToString(" + ") { "${it.type} @+${it.tStartMs - first.tStartMs} ms" } +
                " — " + g.modalities.joinToString("+") { it.name } +
                ", Δt ${g.latestT - first.tStartMs} ms",
            observationIds = g.members.flatMap { it.observationIds },
            evidenceIds = g.members.flatMap { it.evidenceIds },
        )
        incidents += incident
        for (member in g.members.drop(1)) {
            relations += EventRelation(
                fromEventId = first.id,
                toEventId = member.id,
                kind = RelationKind.CO_OCCURS,
                deltaMs = member.tStartMs - first.tStartMs,
                windowMs = windowFor(first, member),
                confidence = confidence,
            )
        }
        val out = ArrayList<Event>(2)
        out += incident
        deviationFor(incident)?.let { out += it }
        return out
    }

    /**
     * The sustained shift that began 0.5–5 s before [incident], emitted once
     * per incident as a PRECEDES edge + a PRE_INCIDENT_DEVIATION event.
     */
    private fun deviationFor(incident: Event): Event? {
        if (!enableDeviationWatch || incident.id in deviationDoneFor) return null
        var best: Event? = null
        for (shift in deviationWatch) {
            val lead = incident.tStartMs - shift.tStartMs
            if (lead in DEVIATION_MIN_LEAD_MS..DEVIATION_MAX_LEAD_MS &&
                (best == null || shift.tStartMs > best.tStartMs)
            ) {
                best = shift
            }
        }
        best ?: return null
        deviationDoneFor += incident.id
        val confidence = minOf(best.confidence, DEVIATION_CONFIDENCE_CAP)
        relations += EventRelation(
            fromEventId = best.id,
            toEventId = incident.id,
            kind = RelationKind.PRECEDES,
            deltaMs = incident.tStartMs - best.tStartMs,
            windowMs = DEVIATION_MAX_LEAD_MS,
            confidence = confidence,
        )
        return Event(
            id = ids.nextId(),
            sessionId = incident.sessionId,
            tStartMs = best.tStartMs,
            tEndMs = best.tEndMs,
            type = "PRE_INCIDENT_DEVIATION",
            modalities = best.modalities,
            tier = EventTier.PROBABLE,
            confidence = confidence,
            salience = (best.salience * 0.9).coerceIn(0.1, 1.0),
            description = best.description +
                " — sustained shift preceding the incident at " +
                SessionClock.formatOffset(incident.tStartMs),
        )
    }

    /** Pair window by modality (plan §7: A/V 250 · A/M 400 · V/M 500 ms). */
    private fun windowFor(a: Event, b: Event): Long {
        val am = a.modalities.firstOrNull() ?: return DEFAULT_WINDOW_MS
        val bm = b.modalities.firstOrNull() ?: return DEFAULT_WINDOW_MS
        return when (setOf(am, bm)) {
            setOf(Modality.AUDIO, Modality.VISION) -> 250L
            setOf(Modality.AUDIO, Modality.MOTION) -> 400L
            setOf(Modality.VISION, Modality.MOTION) -> 500L
            else -> DEFAULT_WINDOW_MS
        }
    }

    private fun isSustainedType(type: String) = type.startsWith("SUSTAINED_") ||
        type == "MOTION_CONTINUOUS" || type == "VIBRATION_BURST"

    companion object {
        /** > every pair window (500 ms) so no joinable member is lost. */
        private const val GROUP_CLOSE_MS = 600L
        private const val DEFAULT_WINDOW_MS = 500L
        private const val SUSTAINED_COVER_MS = 3_000L
        private const val CONFIDENCE_PER_MODALITY = 0.15
        private const val SALIENCE_PER_MODALITY = 0.10
        private const val DEVIATION_CONFIDENCE_CAP = 0.8

        /** Lead window for a deviation to count as pre-incident (plan §7). */
        const val DEVIATION_MIN_LEAD_MS = 500L
        const val DEVIATION_MAX_LEAD_MS = 5_000L
        private const val DEVIATION_KEEP = 40
        private const val MAX_RECENT = 50

        /** Types that never fuse (plan §7 taxonomy notes). */
        private val NOT_FUSIBLE = setOf("SPEECH_PRESENT", "STEP_DETECTED", "PROXIMITY_OCCLUSION")
    }
}
