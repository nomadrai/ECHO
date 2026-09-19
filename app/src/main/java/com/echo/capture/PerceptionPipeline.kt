package com.echo.capture

import com.echo.core.model.Event
import com.echo.core.model.EventRelation
import com.echo.core.model.Observation
import com.echo.fusion.CorrelationEngine
import com.echo.fusion.EventExtractor
import com.echo.fusion.EventIdSequence

/**
 * Wires the capture sources to the event extractor, the M1 correlation
 * engine, persistence and the bus. Kept tiny on purpose: sources stay dumb
 * (measure + submit), fusion stays pure, and the bus is the only shared state.
 *
 * Flow: observation → EventExtractor (single-modality events) →
 * CorrelationEngine (fused INCIDENTs + relation edges). Every event and every
 * relation is persisted *before* it reaches the dashboard.
 *
 * @param onEvent persistence hook — every event (extracted or fused) is passed
 * here before the bus update, so a crash can never lose an event the dashboard
 * already showed
 * @param onRelation persistence hook for relation edges (also fired for
 * edges produced by the end-of-session finalize sweep)
 */
class PerceptionPipeline(
    private val bus: SessionBus,
    private val extractor: EventExtractor = EventExtractor(ids = ids),
    private val onEvent: (Event) -> Unit = {},
    private val onRelation: (EventRelation) -> Unit = {},
) {
    private val correlation = CorrelationEngine(ids = ids)

    /** Index into [CorrelationEngine.relations] of the next unflushed edge. */
    private var flushedRelations = 0

    /** Persist every relation emitted since the last flush (pump or finalize). */
    private fun flushNewRelations() {
        while (flushedRelations < correlation.relations.size) {
            onRelation(correlation.relations[flushedRelations])
            flushedRelations++
        }
    }

    /**
     * @param deviationSigma null while the channel's baseline is still
     * learning — the extractor emits nothing until the baseline is ready.
     */
    @Synchronized
    fun submit(observation: Observation, deviationSigma: Double?) {
        extractor.onObservation(observation, deviationSigma).forEach { event ->
            onEvent(event)
            bus.addEvent(event)
            correlation.onEvent(event)
        }
        // Advance the correlation clock: groups old enough that no future
        // event can join them now close, emitting fused INCIDENTs (+ derived
        // PRE_INCIDENT_DEVIATIONs) in emission order.
        correlation.pump(observation.tMs).forEach { fused ->
            onEvent(fused)
            bus.addEvent(fused)
        }
        // Closed groups emit their CO_OCCURS/PRECEDES edges as they close —
        // persist them now, exactly like events (plan §7).
        flushNewRelations()
    }

    /**
     * Session end: sweep for sustained deviations that led into an incident
     * (PRE_INCIDENT_DEVIATION) and flush the session's relation edges — to
     * persistence *and* to the bus, so the dashboard's edge list is complete.
     * Returns the derived events so the service can persist them in timeline
     * order.
     */
    fun finalize(): List<Event> {
        val derived = correlation.finalizeSession()
        derived.forEach { event ->
            onEvent(event)
            bus.addEvent(event)
        }
        flushNewRelations()
        bus.setRelations(correlation.relations)
        return derived
    }

    companion object {
        /** Shared id counter so extractor + correlation never collide. */
        val ids = EventIdSequence()
    }
}
