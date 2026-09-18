package com.echo.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioDspTest {

    @Test
    fun `silence has zero energy`() {
        val samples = ShortArray(320)
        assertEquals(0.0, AudioDsp.rms(samples, samples.size), 1e-9)
        assertEquals(0.0, AudioDsp.zeroCrossingRate(samples, samples.size), 1e-9)
    }

    @Test
    fun `constant amplitude has the expected rms`() {
        val samples = ShortArray(320) { 16_384 } // 0.5 of full scale
        assertEquals(0.5, AudioDsp.rms(samples, samples.size), 0.001)
    }

    @Test
    fun `sine tone has rms near amplitude over sqrt2`() {
        val amplitude = 0.8
        val samples = ShortArray(1_600) {
            (amplitude * 32_767 * sin(2 * PI * 220 * it / 16_000.0)).toInt().toShort()
        }
        assertEquals(amplitude / Math.sqrt(2.0), AudioDsp.rms(samples, samples.size), 0.01)
    }

    @Test
    fun `alternating signal maximises zero crossings`() {
        val samples = ShortArray(320) { if (it % 2 == 0) 8_000 else -8_000 }
        assertEquals(1.0, AudioDsp.zeroCrossingRate(samples, samples.size), 1e-9)
    }

    @Test
    fun `high frequency tone lands in the high band`() {
        val samples = ShortArray(1_600) {
            (0.7 * 32_767 * sin(2 * PI * 5_000 * it / 16_000.0)).toInt().toShort()
        }
        val (low, high) = AudioDsp.bandEnergies(samples, samples.size)
        assertTrue("high $high should exceed low $low", high > low * 2)
    }

    @Test
    fun `low frequency tone lands in the low band`() {
        val samples = ShortArray(1_600) {
            (0.7 * 32_767 * sin(2 * PI * 100 * it / 16_000.0)).toInt().toShort()
        }
        val (low, high) = AudioDsp.bandEnergies(samples, samples.size)
        assertTrue("low $low should exceed high $high", low > high * 2)
    }
}
