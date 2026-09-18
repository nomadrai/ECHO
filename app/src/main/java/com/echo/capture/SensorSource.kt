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
import kotlin.math.sqrt

/**
 * Motion channel: accelerometer at ~50 Hz (SENSOR_DELAY_GAME). The phone *is*
 * the sensor — it measures device motion, which is why the demo mounts the
 * phone on the rig. No gyroscope (Galaxy M12) degrades to accel-only and the
 * dashboard says so. Zero dependencies, deterministic peak/variance logic.
 */
class SensorSource(
    private val clock: MonotonicClock,
    private val bus: SessionBus,
    private val pipeline: PerceptionPipeline,
) : SensorEventListener {

    private var manager: SensorManager? = null
    private var gyroAvailable = false
    private val baseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.2)
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
        gyroAvailable = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        bus.update {
            it.copy(
                motion = it.motion.copy(gyroAvailable = gyroAvailable),
                health = it.health.copy(sensorRunning = true),
            )
        }
        val registered = sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) {
            fail("registerListener returned false")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val tMs = clock.elapsedMs()
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        baseline.update(magnitude)
        val sigma = if (baseline.ready) baseline.deviationSigma(magnitude) else null
        rateMeter.tick(tMs)

        pipeline.submit(
            Observation(
                tMs = tMs,
                modality = Modality.MOTION,
                kind = "ACCEL_MAG",
                value = magnitude,
                unit = "m/s²",
                baselineValue = baseline.baselineMean,
                deviationSigma = sigma,
            ),
            sigma,
        )
        bus.update {
            it.copy(
                motion = it.motion.copy(
                    accelMagnitude = magnitude,
                    baselineAccel = baseline.baselineMean,
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
