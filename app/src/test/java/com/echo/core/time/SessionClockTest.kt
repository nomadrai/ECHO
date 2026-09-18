package com.echo.core.time

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionClockTest {

    @Test
    fun `offsets format as plus-mm-ss-mmm`() {
        assertEquals("+00:00.000", SessionClock.formatOffset(0))
        assertEquals("+00:01.420", SessionClock.formatOffset(1_420))
        assertEquals("+01:01.420", SessionClock.formatOffset(61_420))
        assertEquals("+10:00.000", SessionClock.formatOffset(600_000))
    }

    @Test
    fun `epoch mapping is a constant offset`() {
        val clock = SessionClock(t0EpochMs = 1_000_000)
        assertEquals(1_001_500, clock.epochAtSessionMs(1_500))
        assertEquals(1_000_000, clock.epochAtSessionMs(0))
    }
}
