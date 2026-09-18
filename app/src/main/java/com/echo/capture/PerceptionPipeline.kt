package com.echo.capture

import com.echo.core.model.Observation
import com.echo.fusion.EventExtractor

/**
 * Wires the three capture sources to the event extractor and the bus.
 * Kept tiny on purpose: sources stay dumb (measure + submit), the extractor
 * stays pure, and the bus is the only shared state.
 */
class PerceptionPipeline(
    private val bus: SessionBus,
    private val extractor: EventExtractor = EventExtractor(),
) {
    /**
     * @param deviationSigma null while the channel's baseline is still
     * learning — the extractor emits nothing until the baseline is ready.
     */
    fun submit(observation: Observation, deviationSigma: Double?) {
        extractor.onObservation(observation, deviationSigma).forEach(bus::addEvent)
    }
}
