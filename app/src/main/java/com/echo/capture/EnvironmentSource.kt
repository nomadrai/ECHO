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
 * Environment channel — the zero-permission `SensorManager` sensors that
 * contextualise an incident around the rig:
 *
 *  - `TYPE_MAGNETIC_FIELD`: |B| in µT → MAGNETIC_DISTURBANCE. Motors, relays
 *    and large ferrous objects moving near the phone warp the local field;
 *    a spike means "something big and magnetic moved" — evidence for
 *    actuator/rig-part motion even when the camera cannot see it.
 *  - `TYPE_PRESSURE`: hPa → PRESSURE_TRANSIENT. A door or window opening,
 *    a heavy object dropped nearby, or a fan/AC kicking in all push a
 *    measurable pressure blip through the room — the "invisible witness".
 *  - `TYPE_LIGHT`: lux → LIGHT_CHANGE. Lights switched, flashes, shadows
 *    sweeping the rig — corroborates the vision channel's BRIGHTNESS-type
 *    evidence and explains frame-motion false positives.
 *  - `TYPE_PROXIMITY`: covered/near flag → PROXIMITY_OCCLUSION. Also a
 *    session-health signal: a covered phone is a degraded witness.
 *  - `TYPE_STEP_COUNTER` (if present): cumulative steps → STEP_DETECTED.
 *    Steps while the session runs mean the phone was picked up and carried —
 *    an evidence-integrity event ("was the witness moved?").
 *  - `TYPE_AMBIENT_TEMPERATURE` / `TYPE_RELATIVE_HUMIDITY` (rare hardware):
 *    present-gated; would catch rig overheating or a spill/steam event.
 *    Absent on most phones — the dashboard reports it honestly.
 *
 * Every channel is presence-gated: a missing sensor degrades to a bus flag,
 * never a failure. All baselines use the environment-capable BaselineTracker
 * (relative σ floor — a barometer resting at 101 kPa must not turn gusts
 * into 5σ events).
 */
class EnvironmentSource(
    private val clock: MonotonicClock,
    private val bus: SessionBus,
    private val pipeline: PerceptionPipeline,
    private val enabled: Set<SensorChannel> = SensorChannel.ALL,
) : SensorEventListener {

    private var manager: SensorManager? = null

    private val magBaseline = BaselineTracker(
        minSamples = 200, sigmaFloor = 1.0, relativeSigmaFloor = 0.03,
    )
    private val pressureBaseline = BaselineTracker(
        minSamples = 200, sigmaFloor = 1.0, relativeSigmaFloor = 0.0002,
    )
    private val lightBaseline = BaselineTracker(
        minSamples = 100, sigmaFloor = 2.0, relativeSigmaFloor = 0.05,
    )

    private var lastRatePublishMs = 0L
    private val rateMeter = com.echo.core.metrics.RateMeter()

    fun start(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            Log.w(TAG, "no SensorManager — environment channel offline")
            return
        }
        manager = sm

        // Presence AND selection: a sensor that exists but was disabled for
        // this session is never registered — and reports as unavailable.
        val magnetic = if (SensorChannel.MAGNETOMETER in enabled) sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null
        val pressure = if (SensorChannel.BAROMETER in enabled) sm.getDefaultSensor(Sensor.TYPE_PRESSURE) else null
        val light = if (SensorChannel.LIGHT in enabled) sm.getDefaultSensor(Sensor.TYPE_LIGHT) else null
        val proximity = if (SensorChannel.PROXIMITY in enabled) sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) else null
        val stepCounter = if (SensorChannel.STEPS in enabled) sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) else null
        val climateEnabled = SensorChannel.CLIMATE in enabled
        val temperature = if (climateEnabled) sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE) else null
        val humidity = if (climateEnabled) sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY) else null

        bus.update {
            it.copy(
                environment = it.environment.copy(
                    magnetometerAvailable = magnetic != null,
                    barometerAvailable = pressure != null,
                    lightSensorAvailable = light != null,
                    proximityAvailable = proximity != null,
                    stepCounterAvailable = stepCounter != null,
                    temperatureAvailable = temperature != null,
                    humidityAvailable = humidity != null,
                ),
            )
        }

        // Normal rate for slow-drifting channels; the binary/gated sensors
        // (proximity, step) deliver only on change.
        var anyRegistered = false
        magnetic?.let { anyRegistered = anyRegistered or sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        pressure?.let { anyRegistered = anyRegistered or sm.registerListener(this, it, SENSOR_DELAY_SLOWISH) }
        light?.let { anyRegistered = anyRegistered or sm.registerListener(this, it, SENSOR_DELAY_SLOWISH) }
        proximity?.let { anyRegistered = anyRegistered or sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        stepCounter?.let { anyRegistered = anyRegistered or sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        temperature?.let { anyRegistered = anyRegistered or sm.registerListener(this, it, SENSOR_DELAY_SLOWISH) }
        humidity?.let { anyRegistered = anyRegistered or sm.registerListener(this, it, SENSOR_DELAY_SLOWISH) }

        if (!anyRegistered) {
            Log.w(TAG, "no enabled environment sensors present on this device")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val tMs = clock.elapsedMs()
        rateMeter.tick(tMs)
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> onMagnetic(tMs, event)
            Sensor.TYPE_PRESSURE -> onPressure(tMs, event)
            Sensor.TYPE_LIGHT -> onLight(tMs, event)
            Sensor.TYPE_PROXIMITY -> onProximity(tMs, event)
            Sensor.TYPE_STEP_COUNTER -> onStep(tMs, event)
            Sensor.TYPE_AMBIENT_TEMPERATURE -> onTemperature(tMs, event)
            Sensor.TYPE_RELATIVE_HUMIDITY -> onHumidity(tMs, event)
        }
        publishRate(tMs)
    }

    private fun onMagnetic(tMs: Long, event: SensorEvent) {
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        magBaseline.update(magnitude)
        val sigma = if (magBaseline.ready) magBaseline.deviationSigma(magnitude) else null
        submit(
            tMs, "MAG_FIELD", magnitude, "µT", magBaseline.baselineMean, sigma,
        )
        bus.update { it.copy(environment = it.environment.copy(magneticFieldUt = magnitude)) }
    }

    private fun onPressure(tMs: Long, event: SensorEvent) {
        val hpa = event.values[0].toDouble()
        pressureBaseline.update(hpa)
        val sigma = if (pressureBaseline.ready) pressureBaseline.deviationSigma(hpa) else null
        submit(tMs, "PRESSURE", hpa, "hPa", pressureBaseline.baselineMean, sigma)
        bus.update { it.copy(environment = it.environment.copy(pressureHpa = hpa)) }
    }

    private fun onLight(tMs: Long, event: SensorEvent) {
        val lux = event.values[0].toDouble()
        lightBaseline.update(lux)
        val sigma = if (lightBaseline.ready) lightBaseline.deviationSigma(lux) else null
        submit(tMs, "LIGHT_LUX", lux, "lx", lightBaseline.baselineMean, sigma)
        bus.update { it.copy(environment = it.environment.copy(lightLux = lux)) }
    }

    private fun onProximity(tMs: Long, event: SensorEvent) {
        val raw = event.values[0].toDouble()
        val covered = raw < event.sensor.maximumRange * COVERED_FRACTION
        bus.update {
            it.copy(
                environment = it.environment.copy(
                    proximityCm = raw,
                    proximityCovered = covered,
                ),
            )
        }
        // Binary occlusion: submit only on state change, scored as a fixed
        // strong transient (no baseline applies to a Boolean).
        if (covered != lastCovered) {
            lastCovered = covered
            val sigma = 6.0
            pipeline.submit(
                Observation(
                    tMs = tMs,
                    modality = Modality.ENVIRONMENT,
                    kind = "PROXIMITY_OCCLUSION",
                    value = if (covered) 1.0 else 0.0,
                    unit = "flag",
                    baselineValue = if (covered) 0.0 else 1.0,
                    deviationSigma = sigma,
                ),
                sigma,
            )
        }
    }

    private fun onStep(tMs: Long, event: SensorEvent) {
        val cumulative = event.values[0].toInt()
        if (lastStepCount < 0) {
            lastStepCount = cumulative // anchor at session start; only deltas count
            return
        }
        val delta = cumulative - lastStepCount
        if (delta > 0) {
            lastStepCount = cumulative
            val sigma = 8.0
            pipeline.submit(
                Observation(
                    tMs = tMs,
                    modality = Modality.ENVIRONMENT,
                    kind = "STEP_DETECTED",
                    value = delta.toDouble(),
                    unit = "steps",
                    baselineValue = 0.0,
                    deviationSigma = sigma,
                ),
                sigma,
            )
            bus.update { it.copy(environment = it.environment.copy(stepCount = cumulative)) }
        }
    }

    private fun onTemperature(tMs: Long, event: SensorEvent) {
        val c = event.values[0].toDouble()
        bus.update { it.copy(environment = it.environment.copy(temperatureC = c)) }
        temperatureBaseline.update(c)
        val sigma = if (temperatureBaseline.ready) temperatureBaseline.deviationSigma(c) else null
        submit(tMs, "AMBIENT_TEMP", c, "°C", temperatureBaseline.baselineMean, sigma)
    }

    private fun onHumidity(tMs: Long, event: SensorEvent) {
        val pct = event.values[0].toDouble()
        bus.update { it.copy(environment = it.environment.copy(humidityPct = pct)) }
        humidityBaseline.update(pct)
        val sigma = if (humidityBaseline.ready) humidityBaseline.deviationSigma(pct) else null
        submit(tMs, "HUMIDITY", pct, "%", humidityBaseline.baselineMean, sigma)
    }

    private val temperatureBaseline = BaselineTracker(
        minSamples = 100, sigmaFloor = 0.1, relativeSigmaFloor = 0.01,
    )
    private val humidityBaseline = BaselineTracker(
        minSamples = 100, sigmaFloor = 1.0, relativeSigmaFloor = 0.02,
    )
    private var lastCovered = false
    private var lastStepCount = -1

    private fun submit(
        tMs: Long,
        kind: String,
        value: Double,
        unit: String,
        baseline: Double,
        sigma: Double?,
    ) {
        pipeline.submit(
            Observation(
                tMs = tMs,
                modality = Modality.ENVIRONMENT,
                kind = kind,
                value = value,
                unit = unit,
                baselineValue = baseline,
                deviationSigma = sigma,
            ),
            sigma,
        )
    }

    private fun publishRate(tMs: Long) {
        if (tMs - lastRatePublishMs >= 1_000) {
            lastRatePublishMs = tMs
            bus.update {
                it.copy(rates = it.rates.copy(environmentHz = rateMeter.ratePerSecond(tMs)))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun stop() {
        runCatching { manager?.unregisterListener(this) }
        manager = null
    }

    companion object {
        private const val TAG = "EchoEnvironment"

        /** Slower than NORMAL — these channels drift, no need to burn cycles. */
        private const val SENSOR_DELAY_SLOWISH = 200_000 // µs ≈ 5 Hz

        /** Fraction of maximumRange below which a proximity sensor counts covered. */
        private const val COVERED_FRACTION = 0.5f
    }
}
