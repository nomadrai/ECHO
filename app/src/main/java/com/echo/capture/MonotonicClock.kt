package com.echo.capture

import android.os.SystemClock

/**
 * The one monotonic clock every capture source stamps from (plan §10 risk 10):
 * t0 is fixed when the session starts and [elapsedMs] never goes backwards.
 * Wall-clock is captured separately by the service for the session record.
 */
class MonotonicClock {
    private val t0ElapsedMs = SystemClock.elapsedRealtime()

    fun elapsedMs(): Long = SystemClock.elapsedRealtime() - t0ElapsedMs
}
