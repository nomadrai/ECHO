package com.echo.fusion

/**
 * One id counter shared by every producer of runtime event ids in a session
 * (the [EventExtractor]'s transients/sustained events and the
 * [CorrelationEngine]'s fused INCIDENTs). Without it, both stages would count
 * from 1 and the dashboard would show two different "E7"s.
 *
 * Thread-safe: extractors run on three capture threads and the correlation
 * engine flushes from its own thread, all through the same pipeline.
 */
class EventIdSequence {
    private var next = 1L

    @Synchronized
    fun nextId(): Long = next++
}
