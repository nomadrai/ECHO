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
    private val triggerSigma: Double = 4.0,
    private val sustainedSigma: Double = 2.5,
    private val sustainedMs: Long = 800,
    private val dedupMs: Long = 300,
) {
    private var nextId = 1L
    private val lastTransientAt = HashMap<String, Long>()
    private val sustainedSince = HashMap<String, Long>()
    private val lastSustainedEmitAt = HashMap<String, Long>()

    /**
     * Feed one scored observation. [deviationSigma] == null (baseline not yet
     * learned) never produces events.
     */
    @Synchronized
    fun onObservation(observation: Observation, deviationSigma: Double?): List<Event> {
        val sigma = deviationSigma ?: return emptyList()
        val kind = observation.kind
        val out = ArrayList<Event>(1)

        if (sigma >= triggerSigma) {
            val last = lastTransientAt[kind] ?: Long.MIN_VALUE
            if (observation.tMs - last >= dedupMs) {
                lastTransientAt[kind] = observation.tMs
                out += Event(
                    id = nextId++,
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

        if (sigma >= sustainedSigma) {
            val start = sustainedSince.getOrPut(kind) { observation.tMs }
            val lastEmit = lastSustainedEmitAt[kind] ?: Long.MIN_VALUE
            if (observation.tMs - start >= sustainedMs &&
                observation.tMs - lastEmit >= sustainedMs
            ) {
                lastSustainedEmitAt[kind] = observation.tMs
                out += Event(
                    id = nextId++,
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
        "FRAME_MOTION" -> "RAPID_MOTION"
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
}
