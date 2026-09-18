package com.echo.core.metrics

import kotlin.collections.ArrayDeque

/**
 * Sliding-window throughput meter for the live dashboard counters. Pure Kotlin.
 *
 * The measured rate is (ticks inside the window) × 1000 / window — deliberately
 * span-independent so a low-rate channel (a 3 fps camera) still reads a stable
 * number instead of flickering between 0 and 3.
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
        return stamps.size * 1000.0 / windowMs
    }

    @Synchronized
    fun count(): Int = stamps.size

    private fun trim(nowMs: Long) {
        while (stamps.isNotEmpty() && nowMs - stamps.first() > windowMs) {
            stamps.removeFirst()
        }
    }
}
