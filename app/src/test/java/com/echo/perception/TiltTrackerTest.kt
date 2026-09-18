package com.echo.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class TiltTrackerTest {

    private fun gravityVector(pitchDeg: Double, rollDeg: Double): Triple<Double, Double, Double> {
        // Invert the tracker's inclinometer formulas: build a gravity vector
        // with the requested pitch (y component) and roll (x component).
        val y = kotlin.math.sin(Math.toRadians(pitchDeg))
        val x = kotlin.math.sin(Math.toRadians(rollDeg))
        // z sign chosen so the vector points "down" as a real gravity reading.
        val z = -sqrt((1.0 - x * x).coerceIn(0.0, 1.0) * (1.0 - y * y).coerceIn(0.0, 1.0))
        return Triple(x * 9.81, y * 9.81, z * 9.81)
    }

    @Test
    fun `no reference before the settling window fills`() {
        val tracker = TiltTracker(settleSamples = 10)
        val (x, y, z) = gravityVector(0.0, 0.0)
        repeat(9) { tracker.updateGravity(x, y, z) }
        assertFalse(tracker.hasReference)
        assertEquals(0.0, tracker.angleFromGravityReference(x, y, z), 1e-9)
    }

    @Test
    fun `same orientation after settling reads zero`() {
        val tracker = TiltTracker(settleSamples = 10)
        val (x, y, z) = gravityVector(0.0, 0.0)
        repeat(10) { tracker.updateGravity(x, y, z) }
        assertTrue(tracker.hasReference)
        assertEquals(0.0, tracker.angleFromGravityReference(x, y, z), 1e-9)
    }

    @Test
    fun `knocked flat from upright exceeds the deadband`() {
        val tracker = TiltTracker(deadbandDeg = 3.0, settleSamples = 10)
        // Reference: phone flat on the table (gravity through z).
        val flat = Triple(0.0, 0.0, -9.81)
        repeat(10) { tracker.updateGravity(flat.first, flat.second, flat.third) }
        // Knocked: phone stood up on its side edge (gravity through x).
        val stoodUp = Triple(-9.81, 0.0, 0.0)
        val angle = tracker.angleFromGravityReference(stoodUp.first, stoodUp.second, stoodUp.third)
        assertTrue("expected a large angle, got $angle", angle > 45.0)
    }

    @Test
    fun `small wobble inside the deadband reads zero`() {
        val tracker = TiltTracker(deadbandDeg = 3.0, settleSamples = 10)
        val (x, y, z) = gravityVector(0.0, 0.0)
        repeat(10) { tracker.updateGravity(x, y, z) }
        // 2° roll — inside the 3° deadband.
        val (wx, wy, wz) = gravityVector(0.0, 2.0)
        assertEquals(0.0, tracker.angleFromGravityReference(wx, wy, wz), 1e-9)
    }

    @Test
    fun `reset re-arms the settling window`() {
        val tracker = TiltTracker(settleSamples = 5)
        val (x, y, z) = gravityVector(0.0, 0.0)
        repeat(5) { tracker.updateGravity(x, y, z) }
        assertTrue(tracker.hasReference)
        tracker.reset()
        assertFalse(tracker.hasReference)
    }

    @Test
    fun `quaternion path is stable for a fixed orientation`() {
        val tracker = TiltTracker(settleSamples = 10)
        repeat(10) { tracker.updateQuaternion(0.0, 0.0, 0.0, 1.0) }
        assertTrue(tracker.hasReference)
        assertEquals(0.0, tracker.angleFromQuaternionReference(0.0, 0.0, 0.0, 1.0), 1e-9)
        // Yaw-only rotation must not register as tilt: pitch/roll are unchanged
        // by definition, so the angle stays far below a knock-over signal.
        val s = sqrt(2.0) / 2
        val angle = tracker.angleFromQuaternionReference(0.0, 0.0, s, s)
        assertTrue("yaw-only rotation read as $angle° tilt", angle < 30.0)
    }
}
