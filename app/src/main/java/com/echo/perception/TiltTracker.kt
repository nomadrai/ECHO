package com.echo.perception

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tilt state for the motion channel: the phone's static orientation, derived
 * either from the gravity sensor (m/s² vector) or from the rotation vector
 * (quaternion) on gyro-less devices like the Galaxy M12.
 *
 * The demo scenario is a phone physically mounted on the rig — so a change in
 * the phone's static tilt *is* an incident signal: the rig was knocked over,
 * the mount slipped, or the phone was picked up. [TiltState.angleTo] measures
 * how far the phone has rotated from its learned resting orientation.
 *
 * Pure Kotlin — JVM-testable, no Android imports.
 */
class TiltTracker(
    /**
     * Angular deadband in degrees. Below this the device counts as resting:
     * cheap IMUs wobble a fraction of a degree and the gravity sensor on a
     * low-tier device can be noisy.
     */
    private val deadbandDeg: Double = 3.0,
    /** Gravity readings absorbed before the reference orientation freezes. */
    private val settleSamples: Int = 50,
) {
    data class TiltState(
        val pitchDeg: Double,
        val rollDeg: Double,
    ) {
        /** Angle between two orientations, in degrees (max-norm blend). */
        fun angleTo(other: TiltState): Double {
            val dp = pitchDeg - other.pitchDeg
            val dr = rollDeg - other.rollDeg
            // max-norm with a small cross term keeps the metric continuous in
            // both axes while staying cheap and monotonic in each.
            return max(abs(dp), abs(dr)) + 0.21 * min(abs(dp), abs(dr))
        }
    }

    private var reference: TiltState? = null
    private var settled = false
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var n = 0

    /** Reference orientation exists once the settling window has been absorbed. */
    val hasReference: Boolean get() = settled

    /**
     * Feed one gravity vector (m/s²). After [settleSamples] updates the
     * reference orientation freezes; later vectors are scored against it.
     */
    fun updateGravity(x: Double, y: Double, z: Double) {
        if (!settled) {
            sumX += x; sumY += y; sumZ += z; n++
            if (n >= settleSamples) {
                reference = tiltFromGravity(sumX / n, sumY / n, sumZ / n)
                settled = true
            }
        }
    }

    /**
     * Feed one rotation-vector sample (x, y, z quaternion vector part, w
     * scalar — Android's ordering). Converts to pitch/roll directly; used when
     * the device has no dedicated gravity sensor.
     */
    fun updateQuaternion(x: Double, y: Double, z: Double, w: Double) {
        if (!settled) {
            // Average quaternions via the vector part only — good enough for a
            // coarse deadband reference, and sign-flip safe because asin/atan2
            // see the doubled-angle terms.
            sumX += x; sumY += y; sumZ += z; n++
            if (n >= settleSamples) {
                // Reconstruct a unit-ish w from the averaged vector part.
                val vsq = 1.0 - (sumX * sumX + sumY * sumY + sumZ * sumZ) / (n * n)
                val wAvg = sqrt(vsq.coerceIn(0.0, 1.0))
                reference = tiltFromQuaternion(sumX / n, sumY / n, sumZ / n, wAvg)
                settled = true
            }
        }
    }

    /** Discard the current reference (a confirmed TILT_CHANGE re-arms). */
    fun reset() {
        reference = null
        settled = false
        sumX = 0.0; sumY = 0.0; sumZ = 0.0; n = 0
    }

    /**
     * Tilt state for one gravity vector, for scoring without updating state.
     */
    fun tiltOfGravity(x: Double, y: Double, z: Double): TiltState = tiltFromGravity(x, y, z)

    /**
     * Angle in degrees between the orientation given by this gravity vector
     * and the learned reference, or 0.0 while still settling. Values within
     * the deadband return 0.0 ("not moved").
     */
    fun angleFromGravityReference(x: Double, y: Double, z: Double): Double =
        angleFromReference(tiltFromGravity(x, y, z))

    /** Same, for a rotation-vector sample (x, y, z, w). */
    fun angleFromQuaternionReference(x: Double, y: Double, z: Double, w: Double): Double =
        angleFromReference(tiltFromQuaternion(x, y, z, w))

    private fun angleFromReference(state: TiltState): Double {
        val ref = reference ?: return 0.0
        val angle = state.angleTo(ref)
        return if (angle <= deadbandDeg) 0.0 else angle
    }

    private fun tiltFromGravity(x: Double, y: Double, z: Double): TiltState {
        val norm = sqrt(x * x + y * y + z * z)
        if (norm == 0.0) return TiltState(0.0, 0.0)
        // Standard inclinometer formulas: pitch from the Y component (front–
        // back lean), roll from the X component (side lean).
        val pitch = Math.toDegrees(asin(clamp(y / norm)))
        val roll = Math.toDegrees(asin(clamp(x / norm)))
        return TiltState(pitch, roll)
    }

    private fun tiltFromQuaternion(x: Double, y: Double, z: Double, w: Double): TiltState {
        // Standard aerospace-convention conversions (Android rotation vector
        // is q = [x, y, z, w] with the device's Y axis "up" convention).
        val roll = Math.toDegrees(
            atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y)),
        )
        val pitchSin = (2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0)
        val pitch = Math.toDegrees(asin(pitchSin))
        return TiltState(pitch, roll)
    }

    private fun clamp(v: Double): Double = v.coerceIn(-1.0, 1.0)
}
