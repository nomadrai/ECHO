package com.echo.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionDspTest {

    @Test
    fun `downscale to the same size is a copy`() {
        val src = ByteArray(16) { it.toByte() }
        val dst = ByteArray(16)
        VisionDsp.downscale(src, 4, 4, 4, 4, dst)
        src.forEachIndexed { i, byte -> assertEquals(byte, dst[i]) }
    }

    @Test
    fun `downscale averages blocks`() {
        // 4×4 of 200s → 2×2 of 200s.
        val src = ByteArray(16) { 200.toByte() }
        val dst = ByteArray(4)
        VisionDsp.downscale(src, 4, 4, 2, 2, dst)
        dst.forEach { assertEquals(200.toByte(), it) }
    }

    @Test
    fun `identical frames have no change`() {
        val frame = ByteArray(64) { (100 + it).toByte() }
        val stats = VisionDsp.diff(frame, frame, 64)
        assertEquals(0.0, stats.changedFraction, 1e-9)
        assertEquals(0.0, stats.meanAbsDiff, 1e-9)
    }

    @Test
    fun `half the pixels moving registers as half changed`() {
        val reference = ByteArray(64) { 100.toByte() }
        val current = ByteArray(64) { if (it < 32) 200.toByte() else 100.toByte() }
        val stats = VisionDsp.diff(current, reference, 64)
        assertEquals(0.5, stats.changedFraction, 0.01)
        assertEquals(50.0, stats.meanAbsDiff, 0.01)
        assertEquals(150.0, stats.meanLuma, 0.01)
    }

    @Test
    fun `sub threshold jitter counts as unchanged`() {
        val reference = ByteArray(64) { 100.toByte() }
        val current = ByteArray(64) { 110.toByte() } // exactly at the threshold
        val stats = VisionDsp.diff(current, reference, 64, threshold = 18)
        assertEquals(0.0, stats.changedFraction, 1e-9)
        assertEquals(10.0, stats.meanAbsDiff, 1e-9)
    }
}
