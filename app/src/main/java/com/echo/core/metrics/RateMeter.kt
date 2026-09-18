package com.echo.core.metrics

import kotlin.collections.ArrayDeque

/**
 * Sliding-window throughput meter for the live dashboard counters. Pure Kotlin.
 *
 * The measured rate is span-based: (ticks inside the window − 1) × 1000 /
 * (newest − oldest tick). This stays accurate for slow channels from the very
 * first pair of ticks — a count-based rate would read a 3 fps camera as 2/s
 * for seconds while the window is still filling.
 */
class RateMeter(private val windowMs: Long = 5_000L) {

    private val stamps = ArrayDeque<Long>()

    @Synchronized
    fun tick(nowMs: Long) {
        stamps.addLast(nowMs)
        trim(nowMs)
    }

    @Synchronized
    fun ratePerSecond(nowMs: Long): Double {
        trim(nowMs)
        if (stamps.size < 2) return 0.0
        val span = stamps.last() - stamps.first()
        if (span <= 0) return 0.0
        return (stamps.size - 1) * 1000.0 / span
    }

    @Synchronized
    fun count(): Int = stamps.size

    /** Tick count as of [nowMs], trimming first — for tests and telemetry. */
    @Synchronized
    fun count(nowMs: Long): Int {
        trim(nowMs)
        return stamps.size
    }

    private fun trim(nowMs: Long) {
        while (stamps.isNotEmpty() && nowMs - stamps.first() > windowMs) {
            stamps.removeFirst()
        }
    }
}
