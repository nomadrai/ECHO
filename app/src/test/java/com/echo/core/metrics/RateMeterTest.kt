package com.echo.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateMeterTest {

    @Test
    fun `counts only ticks inside the window`() {
        val meter = RateMeter(windowMs = 1_000)
        repeat(5) { meter.tick(it * 100L) }
        assertEquals(5, meter.count())
        // The tick from t=0 has fallen out of the window by t=1_200.
        assertEquals(4, meter.count())
    }

    @Test
    fun `rate is span independent`() {
        val meter = RateMeter(windowMs = 5_000)
        // 3 fps camera: ticks every ~333 ms.
        repeat(10) { meter.tick(it * 333L) }
        val rate = meter.ratePerSecond(10 * 333L)
        assertEquals(3.0, rate, 0.5)
    }

    @Test
    fun `high rate channels measure their true rate`() {
        val meter = RateMeter(windowMs = 5_000)
        repeat(250) { meter.tick(it * 20L) } // 50 Hz
        assertEquals(50.0, meter.ratePerSecond(250 * 20L), 1.0)
    }

    @Test
    fun `stale meter reads zero`() {
        val meter = RateMeter(windowMs = 1_000)
        meter.tick(0)
        assertTrue(meter.ratePerSecond(10_000) == 0.0)
    }
}
