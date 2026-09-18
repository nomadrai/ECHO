package com.echo.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTrackerTest {

    @Test
    fun `not ready before minSamples`() {
        val tracker = BaselineTracker(minSamples = 100)
        repeat(99) { tracker.update(0.1) }
        assertFalse(tracker.ready)
        tracker.update(0.1)
        assertTrue(tracker.ready)
    }

    @Test
    fun `constant signal converges to the value with a sigma floor`() {
        val tracker = BaselineTracker(minSamples = 10, sigmaFloor = 0.01)
        repeat(200) { tracker.update(0.5) }
        assertEquals(0.5, tracker.baselineMean, 0.01)
        // A constant input has zero real variance; the floor keeps σ usable.
        assertEquals(0.01, tracker.baselineSigma, 1e-9)
        assertEquals(0.0, tracker.deviationSigma(0.5), 1e-9)
        assertEquals(1.0, tracker.deviationSigma(0.51), 1e-6)
    }

    @Test
    fun `adaptive mean follows a genuine level shift`() {
        val tracker = BaselineTracker(minSamples = 50, alpha = 0.05)
        repeat(400) { tracker.update(0.1) }
        repeat(400) { tracker.update(0.3) }
        // The baseline re-learns; the old level is now a big positive deviation.
        assertTrue(tracker.deviationSigma(0.1) < -2.0)
        assertTrue(tracker.deviationSigma(0.3) < 1.0)
    }

    @Test
    fun `sigma floor also scales with the mean`() {
        val tracker = BaselineTracker(minSamples = 10, sigmaFloor = 0.0)
        repeat(100) { tracker.update(100.0) }
        // max(0, 100 × 0.02) = 2.0
        assertEquals(2.0, tracker.baselineSigma, 1e-9)
    }

    @Test
    fun `relative sigma floor covers large-magnitude channels`() {
        // A barometer resting at ~101 kPa: the absolute floor is irrelevant,
        // the relative one must dominate.
        val tracker = BaselineTracker(minSamples = 10, sigmaFloor = 1.0, relativeSigmaFloor = 0.0002)
        repeat(100) { tracker.update(101_325.0) }
        assertEquals(101_325.0 * 0.0002, tracker.baselineSigma, 1e-6)
    }

    @Test
    fun `relative floor never shrinks the absolute floor`() {
        val tracker = BaselineTracker(minSamples = 10, sigmaFloor = 5.0, relativeSigmaFloor = 0.001)
        repeat(100) { tracker.update(10.0) }
        // max(5.0, 10 × 0.001) = 5.0
        assertEquals(5.0, tracker.baselineSigma, 1e-9)
    }
}
