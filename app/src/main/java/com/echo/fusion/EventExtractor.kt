package com.echo.fusion

import com.echo.core.model.Event
import com.echo.core.model.EventTier
import com.echo.core.model.Observation
import java.util.Locale

/**
 * Deterministic event extraction from scored observations (plan §7).
 * Pure Kotlin and thread-safe (sources submit from three different threads).
 *
 * M0 scope: single-modality events are PROBABLE. Cross-modal correlation and
 * the CONFIRMED tier arrive with the CorrelationEngine in M1; sustained types
 * are simplified to ongoing events (START/STOP pairs come with M1 too).
 *
 * @param triggerSigma single-hop deviation that fires a transient event
 * @param sustainedSigma deviation that counts as "elevated" for sustained types
 * @param sustainedMs elevation must persist this long before a sustained event
 * @param dedupMs minimum spacing between transients of the same kind
 */
class EventExtractor(
    private val ids: EventIdSequence = EventIdSequence(),
    private val triggerSigma: Double = 4.0,
    private val sustainedSigma: Double = 2.5,
    private val sustainedMs: Long = 800,
    private val dedupMs: Long = 300,
    /**
     * Observations of these kinds are scored for transients only. The
     * environment channel's EMA baselines move with slow drifts (weather for
     * the barometer, lights warming up for the light sensor), so a "sustained
     * elevation" there would fire long after the incident it describes.
     */
    private val transientOnlyKinds: Set<String> = DEFAULT_TRANSIENT_ONLY,
) {
    private val lastTransientAt = HashMap<String, Long>()
    private val sustainedSince = HashMap<String, Long>()
    private val lastSustainedEmitAt = HashMap<String, Long>()

    /** Feed one scored observation. [deviationSigma] == null (baseline not yet
     * learned) never produces events. */
    @Synchronized
    fun onObservation(observation: Observation, deviationSigma: Double?): List<Event> {
        val sigma = deviationSigma ?: return emptyList()
        val kind = observation.kind
        val out = ArrayList<Event>(1)

        if (sigma >= triggerSigma) {
            // Null-safe dedup: a Long.MIN_VALUE sentinel would overflow
            // (tMs - MIN_VALUE wraps negative) and silently suppress the
            // first — and then every — transient of the kind.
            val passesDedup = lastTransientAt[kind]?.let { observation.tMs - it >= dedupMs } ?: true
            if (passesDedup) {
                lastTransientAt[kind] = observation.tMs
                out += Event(
                    id = ids.nextId(),
                    sessionId = observation.sessionId,
                    tStartMs = observation.tMs,
                    tEndMs = observation.tMs,
                    type = transientType(kind),
                    modalities = setOf(observation.modality),
                    tier = EventTier.PROBABLE,
                    confidence = confidenceFor(sigma),
                    salience = salienceFor(sigma),
                    description = "$kind spiked to ${fmt(observation.value)} ${observation.unit} " +
                        "(${fmt(sigma)}σ above baseline)",
                )
            }
        }

        if (sigma >= sustainedSigma && kind !in transientOnlyKinds) {
            val start = sustainedSince.getOrPut(kind) { observation.tMs }
            val emitOk = lastSustainedEmitAt[kind]?.let { observation.tMs - it >= sustainedMs } ?: true
            if (observation.tMs - start >= sustainedMs && emitOk) {
                lastSustainedEmitAt[kind] = observation.tMs
                out += Event(
                    id = ids.nextId(),
                    sessionId = observation.sessionId,
                    tStartMs = start,
                    tEndMs = observation.tMs,
                    type = sustainedType(kind),
                    modalities = setOf(observation.modality),
                    tier = EventTier.PROBABLE,
                    confidence = confidenceFor(sigma),
                    salience = salienceFor(sigma),
                    description = "$kind sustained at ${fmt(observation.value)} ${observation.unit} " +
                        "(${fmt(sigma)}σ) for ${observation.tMs - start} ms",
                )
            }
        } else {
            sustainedSince.remove(kind)
        }
        return out
    }

    private fun transientType(kind: String) = when (kind) {
        "AUDIO_RMS" -> "IMPACT_TRANSIENT"
        "ACCEL_MAG" -> "DEVICE_SHOCK"
        "ACCEL_LIN" -> "ACCEL_JOLT"
        "GYRO_MAG" -> "ANGULAR_JOLT"
        "FRAME_MOTION" -> "RAPID_MOTION"
        "TILT_ANGLE" -> "TILT_CHANGE"
        "MAG_FIELD" -> "MAGNETIC_DISTURBANCE"
        "PRESSURE" -> "PRESSURE_TRANSIENT"
        "LIGHT_LUX" -> "LIGHT_CHANGE"
        else -> kind
    }

    private fun sustainedType(kind: String) = when (kind) {
        "AUDIO_RMS" -> "SUSTAINED_NOISE"
        "ACCEL_MAG" -> "VIBRATION_BURST"
        "FRAME_MOTION" -> "MOTION_CONTINUOUS"
        else -> kind
    }

    private fun confidenceFor(sigma: Double) = (0.5 + 0.02 * sigma).coerceAtMost(0.85)

    private fun salienceFor(sigma: Double) = (sigma / 10.0).coerceIn(0.1, 1.0)

    private fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)

    companion object {
        /**
         * Environment-channel kinds (barometer, light, magnetometer) plus the
         * tilt/step/occlusion events the environment source emits directly:
         * all transient-scored, never sustained.
         */
        val DEFAULT_TRANSIENT_ONLY = setOf(
            "PRESSURE", "LIGHT_LUX", "MAG_FIELD", "TILT_ANGLE",
            "STEP_DETECTED", "PROXIMITY_OCCLUSION",
        )
    }
}
