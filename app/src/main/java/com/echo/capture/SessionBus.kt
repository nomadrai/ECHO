package com.echo.capture

import com.echo.core.model.Event
import com.echo.core.model.EventRelation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SessionPhase { IDLE, PREPARING, RECORDING, STOPPING, STOPPED }

data class DetectorRates(
    val framesPerSecond: Double = 0.0,
    val audioHopsPerSecond: Double = 0.0,
    val sensorHz: Double = 0.0,
    val environmentHz: Double = 0.0,
)

data class DetectorHealth(
    val cameraRunning: Boolean = false,
    val audioRunning: Boolean = false,
    val sensorRunning: Boolean = false,
    val cameraError: String? = null,
    val audioError: String? = null,
    val sensorError: String? = null,
) {
    /** A session continues in degraded mode when one channel dies (plan §8). */
    val degraded: Boolean get() = cameraError != null || audioError != null || sensorError != null
}

data class AudioLevels(
    val rms: Double = 0.0,
    val baselineRms: Double = 0.0,
    val zcr: Double = 0.0,
    val lowBand: Double = 0.0,
    val highBand: Double = 0.0,
    val classifierReady: Boolean = false,
    val classifierError: String? = null,
    val classifyLabels: List<Pair<String, Float>> = emptyList(),
)

data class VisionLevels(
    val changedFraction: Double = 0.0,
    val meanAbsDiff: Double = 0.0,
    val meanLuma: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
)

data class MotionLevels(
    val accelMagnitude: Double = 0.0,
    val baselineAccel: Double = 0.0,
    val gyroMagnitude: Double = 0.0,
    val linearAccelMagnitude: Double = 0.0,
    val tiltAngleDeg: Double = 0.0,
    val gyroAvailable: Boolean = false,
    val linearAccelAvailable: Boolean = false,
    val tiltAvailable: Boolean = false,
)

data class EnvironmentLevels(
    val magneticFieldUt: Double = 0.0,
    val pressureHpa: Double = 0.0,
    val lightLux: Double = 0.0,
    val proximityCm: Double = -1.0,
    val proximityCovered: Boolean = false,
    val temperatureC: Double = Double.NaN,
    val humidityPct: Double = Double.NaN,
    val stepCount: Int = -1,
    val magnetometerAvailable: Boolean = false,
    val barometerAvailable: Boolean = false,
    val lightSensorAvailable: Boolean = false,
    val proximityAvailable: Boolean = false,
    val temperatureAvailable: Boolean = false,
    val humidityAvailable: Boolean = false,
    val stepCounterAvailable: Boolean = false,
)

data class SessionBusState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val startedAtEpochMs: Long? = null,
    val sessionMs: Long = 0,
    val rates: DetectorRates = DetectorRates(),
    val health: DetectorHealth = DetectorHealth(),
    val audio: AudioLevels = AudioLevels(),
    val vision: VisionLevels = VisionLevels(),
    val motion: MotionLevels = MotionLevels(),
    val environment: EnvironmentLevels = EnvironmentLevels(),
    val events: List<Event> = emptyList(),
    val eventCount: Int = 0,
    val relations: List<EventRelation> = emptyList(),
    val incidentCount: Int = 0,
)

/**
 * The capture service's live state, published as one immutable snapshot.
 *
 * The dashboard renders *this* — never a ViewModel copy — so what the user
 * sees is exactly what the real pipeline is doing, including per-channel
 * failures. The bus outlives the service (companion-held) so the dashboard
 * can show the sealed session after END.
 */
class SessionBus {

    private val _state = MutableStateFlow(SessionBusState())
    val state = _state.asStateFlow()

    fun update(transform: (SessionBusState) -> SessionBusState) = _state.update(transform)

    fun addEvent(event: Event) = _state.update {
        it.copy(
            events = (it.events + event).takeLast(MAX_EVENTS),
            eventCount = it.eventCount + 1,
            incidentCount = if (event.type == "INCIDENT") it.incidentCount + 1 else it.incidentCount,
        )
    }

    /** Replaces the relation edges (flushed once at session finalize). */
    fun setRelations(relations: List<EventRelation>) = _state.update {
        it.copy(relations = relations.takeLast(MAX_RELATIONS))
    }

    fun reset() {
        _state.value = SessionBusState()
    }

    companion object {
        private const val MAX_EVENTS = 200
        private const val MAX_RELATIONS = 120
    }
}
