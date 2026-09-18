package com.echo.core.time

import java.util.Locale

/**
 * Session time base: every timestamp in ECHO is a session-relative offset from
 * t0 (one monotonic clock, plan §10 risk 10). Wall-clock is derived once via
 * [epochAtSessionMs] and never used for correlation. Pure Kotlin.
 */
class SessionClock(private val t0EpochMs: Long) {

    fun epochAtSessionMs(sessionMs: Long): Long = t0EpochMs + sessionMs

    companion object {
        /** Canonical on-screen timestamp: +mm:ss.mmm from session start. */
        fun formatOffset(sessionMs: Long): String {
            val minutes = sessionMs / 60_000
            val seconds = (sessionMs % 60_000) / 1000
            val millis = sessionMs % 1000
            return "+%02d:%02d.%03d".format(Locale.US, minutes, seconds, millis)
        }
    }
}
