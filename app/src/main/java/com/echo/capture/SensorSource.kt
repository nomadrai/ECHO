package com.echo.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.echo.core.model.Modality
import com.echo.core.model.Observation
import com.echo.perception.BaselineTracker
import com.echo.perception.TiltTracker
import kotlin.math.sqrt

/**
 * Motion channel — the phone *is* the sensor, which is why the demo mounts it
 * on the rig. Streams four `SensorManager` channels on one listener:
 *
 *  - `TYPE_ACCELEROMETER` (~50 Hz): |a| magnitude → DEVICE_SHOCK /
 *    VIBRATION_BURST. Includes gravity, so a shock and a re-orientation look
 *    similar here — the tilt channel below disambiguates.
 *  - `TYPE_GYROSCOPE` (if present): |ω| → ANGULAR_JOLT. A table bump or a
 *    knocked-over rig rotates the phone faster than resting noise ever does.
 *    Absent on the Galaxy M12 — degrades to accel-only, the dashboard says so.
 *  - `TYPE_LINEAR_ACCELERATION` (if present): gravity-free |a| → ACCEL_JOLT.
 *    A cleaner shock number than raw |a|; also the channel that separates
 *    "phone was re-oriented" from "phone was hit".
 *  - `TYPE_GRAVITY` (or `TYPE_ROTATION_VECTOR` fallback): static tilt →
 *    TILT_CHANGE when the phone/rig is knocked over or the mount slips.
 *
 * All DSP is deterministic and the zero-permission sensor set. No gyroscope
 * (M12) degrades gracefully; the dashboard reports which channels are live.
 */
class SensorSource(
    private val clock: MonotonicClock,
    private val bus: SessionBus,
    private val pipeline: PerceptionPipeline,
    private val enabled: Set<SensorChannel> = SensorChannel.ALL,
) : SensorEventListener {

    private var manager: SensorManager? = null
    private var gyroAvailable = false
    private var linearAccelAvailable = false
    private var tiltAvailable = false

    private val accelBaseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.2)
    private val gyroBaseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.02)
    private val linearBaseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.1)
    private val tiltTracker = TiltTracker(deadbandDeg = 3.0)

    private var lastRatePublishMs = 0L
    private val rateMeter = com.echo.core.metrics.RateMeter()

    fun start(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            fail("no SensorManager on this device")
            return
        }
        manager = sm
        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            fail("no accelerometer — impact detection impossible on this device")
            return
        }
        // Per-session selection: a sub-sensor the user disabled is never
        // registered — no listener, no callbacks, no battery cost.
        gyroAvailable = SensorChannel.GYROSCOPE in enabled &&
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        linearAccelAvailable = SensorChannel.LINEAR_ACCEL in enabled &&
            sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
        // Tilt from the gravity sensor; on gyro-less devices the rotation
        // vector is the fused alternative that still gives a gravity vector.
        val gravitySensor = if (SensorChannel.TILT in enabled) {
            sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        } else null
        tiltAvailable = gravitySensor != null

        bus.update {
            it.copy(
                motion = it.motion.copy(
                    gyroAvailable = gyroAvailable,
                    linearAccelAvailable = linearAccelAvailable,
                    tiltAvailable = tiltAvailable,
                ),
                health = it.health.copy(sensorRunning = true),
            )
        }

        val registered = sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        if (gyroAvailable) {
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
        }
        if (linearAccelAvailable) {
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), SensorManager.SENSOR_DELAY_GAME)
        }
        if (tiltAvailable) {
            sm.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_UI)
        }
        if (!registered) {
            fail("registerListener returned false")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(event)
            Sensor.TYPE_GYROSCOPE -> onGyro(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> onLinearAccel(event)
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ROTATION_VECTOR -> onTiltReference(event)
        }
    }

    private fun onAccel(event: SensorEvent) {
        val tMs = clock.elapsedMs()
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)

        accelBaseline.update(magnitude)
        val sigma = if (accelBaseline.ready) accelBaseline.deviationSigma(magnitude) else null
        rateMeter.tick(tMs)

        pipeline.submit(
            Observation(
                tMs = tMs,
                modality = Modality.MOTION,
                kind = "ACCEL_MAG",
                value = magnitude,
                unit = "m/s²",
                baselineValue = accelBaseline.baselineMean,
                deviationSigma = sigma,
            ),
            sigma,
        )
        publishRate(tMs)
    }

    private fun onGyro(event: SensorEvent) {
        val tMs = clock.elapsedMs()
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        gyroBaseline.update(magnitude)
        val sigma = if (gyroBaseline.ready) gyroBaseline.deviationSigma(magnitude) else null
        bus.update { it.copy(motion = it.motion.copy(gyroMagnitude = magnitude)) }
        pipeline.submit(
            Observation(
                tMs = tMs,
                modality = Modality.MOTION,
                kind = "GYRO_MAG",
                value = magnitude,
                unit = "rad/s",
                baselineValue = gyroBaseline.baselineMean,
                deviationSigma = sigma,
            ),
            sigma,
        )
    }

    private fun onLinearAccel(event: SensorEvent) {
        val tMs = clock.elapsedMs()
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        linearBaseline.update(magnitude)
        val sigma = if (linearBaseline.ready) linearBaseline.deviationSigma(magnitude) else null
        bus.update { it.copy(motion = it.motion.copy(linearAccelMagnitude = magnitude)) }
        pipeline.submit(
            Observation(
                tMs = tMs,
                modality = Modality.MOTION,
                kind = "ACCEL_LIN",
                value = magnitude,
                unit = "m/s²",
                baselineValue = linearBaseline.baselineMean,
                deviationSigma = sigma,
            ),
            sigma,
        )
    }

    /** Gravity / rotation-vector events feed the tilt reference. */
    private fun onTiltReference(event: SensorEvent) {
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val tMs = clock.elapsedMs()

        val angle: Double
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            // Quaternion vector part + scalar: Android orders values [x, y, z, w].
            val w = event.values[3].toDouble()
            tiltTracker.updateQuaternion(x, y, z, w)
            angle = tiltTracker.angleFromQuaternionReference(x, y, z, w)
        } else {
            tiltTracker.updateGravity(x, y, z)
            angle = tiltTracker.angleFromGravityReference(x, y, z)
        }
        bus.update {
            it.copy(motion = it.motion.copy(tiltAngleDeg = angle))
        }
        if (angle > 0.0) {
            // Transient-scored only (see EventExtractor.transientOnlyKinds).
            pipeline.submit(
                Observation(
                    tMs = tMs,
                    modality = Modality.MOTION,
                    kind = "TILT_ANGLE",
                    value = angle,
                    unit = "deg",
                    baselineValue = 0.0,
                    deviationSigma = angle / 3.0, // 1σ per 3° beyond the deadband
                ),
                angle / 3.0,
            )
        }
    }

    private fun publishRate(tMs: Long) {
        bus.update {
            it.copy(
                motion = it.motion.copy(
                    accelMagnitude = accelBaseline.lastValue,
                    baselineAccel = accelBaseline.baselineMean,
                ),
            )
        }
        if (tMs - lastRatePublishMs >= 1_000) {
            lastRatePublishMs = tMs
            bus.update {
                it.copy(rates = it.rates.copy(sensorHz = rateMeter.ratePerSecond(tMs)))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun stop() {
        runCatching { manager?.unregisterListener(this) }
        manager = null
    }

    private fun fail(message: String) {
        Log.e(TAG, "sensor source failed: $message")
        bus.update { it.copy(health = it.health.copy(sensorError = message)) }
    }

    companion object {
        private const val TAG = "EchoSensor"
    }
}
