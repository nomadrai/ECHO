package com.echo.capture

import com.echo.core.model.Modality

/**
 * One toggleable capture channel for a session. Sensor-level granularity:
 * each entry maps to exactly one source start or one `SensorManager`
 * registration, so disabling it removes real capture work (no thread, no
 * listener, no camera bind) — a battery/storage saving, not a display filter.
 */
enum class SensorChannel(
    val id: String,
    val displayName: String,
    val modality: Modality,
    /** One line for the selection UI: what this channel buys the session. */
    val purpose: String,
) {
    CAMERA("camera", "Camera", Modality.VISION, "Motion on screen, falls, scene changes"),
    MICROPHONE("mic", "Microphone", Modality.AUDIO, "Impacts, motor tone, abnormal sounds"),
    ACCELEROMETER("accel", "Accelerometer", Modality.MOTION, "Shocks and vibration (the rig witness)"),
    GYROSCOPE("gyro", "Gyroscope", Modality.MOTION, "Rotational jolts (table bump, knocked over)"),
    LINEAR_ACCEL("linaccel", "Linear acceleration", Modality.MOTION, "Gravity-free shock (cleaner hit number)"),
    TILT("tilt", "Tilt", Modality.MOTION, "Rig knocked over, mount slipped, phone picked up"),
    MAGNETOMETER("mag", "Magnetometer", Modality.ENVIRONMENT, "Motors/relays/ferrous parts moving nearby"),
    BAROMETER("baro", "Barometer", Modality.ENVIRONMENT, "Doors, drops, HVAC — the invisible witness"),
    LIGHT("light", "Light sensor", Modality.ENVIRONMENT, "Lights switched, shadows across the rig"),
    PROXIMITY("prox", "Proximity", Modality.ENVIRONMENT, "Covered/occluded phone — witness integrity"),
    STEPS("steps", "Step counter", Modality.ENVIRONMENT, "Phone carried away — evidence integrity"),
    CLIMATE("climate", "Temp + humidity", Modality.ENVIRONMENT, "Overheating, spills (rare hardware)"),
    ;

    companion object {
        fun fromId(id: String): SensorChannel? = entries.firstOrNull { it.id == id }

        /** The full sensor set — what pre-selection sessions captured. */
        val ALL: Set<SensorChannel> = entries.toSet()
    }
}

/**
 * Rule-based capture suggestions per nature of work (instant, offline, no AI):
 * keyword → preset channel set. The first rule whose keyword matches the
 * lowercased goal wins; unknown work types get [DEFAULT_SUGGESTION]. Extend
 * by adding a rule — nothing else needs to change.
 */
object WorkTypeSuggestions {

    /** Sensible defaults for an unmatched work type: the classic incident rig. */
    val DEFAULT_SUGGESTION: Set<SensorChannel> = setOf(
        SensorChannel.CAMERA, SensorChannel.MICROPHONE, SensorChannel.ACCELEROMETER,
        SensorChannel.GYROSCOPE, SensorChannel.MAGNETOMETER,
    )

    private val rules: List<Pair<List<String>, Set<SensorChannel>>> = listOf(
        // Sleep / overnight: quiet room, no camera, minimal power draw.
        listOf("sleep", "nap", "overnight", "night", "insomnia") to setOf(
            SensorChannel.MICROPHONE, SensorChannel.ACCELEROMETER, SensorChannel.BAROMETER,
        ),
        // Desk work: motion-light, mic for context, no camera.
        listOf("study", "studying", "read", "writing", "desk", "office", "homework") to setOf(
            SensorChannel.MICROPHONE, SensorChannel.ACCELEROMETER,
        ),
        // Mechanical rig work: the full multimodal demo set.
        listOf("motor", "rig", "machine", "mechanic", "pump", "engine", "drill", "lathe",
            "vibrat", "gear", "bearing", "rotor", "turbine", "compressor", "conveyor") to setOf(
            SensorChannel.CAMERA, SensorChannel.MICROPHONE, SensorChannel.ACCELEROMETER,
            SensorChannel.GYROSCOPE, SensorChannel.MAGNETOMETER,
        ),
        // Construction/structural: loud tools, heavy shocks, camera useful.
        listOf("concret", "construction", "hammer", "saw", "weld", "drill press", "beam",
            "load test", "structur") to setOf(
            SensorChannel.CAMERA, SensorChannel.MICROPHONE, SensorChannel.ACCELEROMETER,
            SensorChannel.GYROSCOPE,
        ),
        // Electronics bench: small magnetic + light signals, handling shocks.
        listOf("circuit", "solder", "pcb", "arduino", "raspberry", "esp32", "wiring",
            "electronics", "breadboard") to setOf(
            SensorChannel.CAMERA, SensorChannel.MICROPHONE, SensorChannel.ACCELEROMETER,
            SensorChannel.MAGNETOMETER, SensorChannel.LIGHT,
        ),
        // Chemistry/lab: glassware impacts, fume hood, stopper pops.
        listOf("chemis", "titrat", "solution", "reagent", "beaker", "flask", "acid",
            "distil", "stir") to setOf(
            SensorChannel.CAMERA, SensorChannel.MICROPHONE, SensorChannel.ACCELEROMETER,
            SensorChannel.BAROMETER, SensorChannel.LIGHT,
        ),
        // Thermal: the experiment IS a slow drift — temp/humidity matter.
        listOf("heat", "thermal", "oven", "furnace", "burner", "boil", "melt", "bake") to setOf(
            SensorChannel.CAMERA, SensorChannel.MICROPHONE, SensorChannel.ACCELEROMETER,
            SensorChannel.CLIMATE, SensorChannel.LIGHT,
        ),
        // Acoustic experiments: the stimulus is sound — vision corroborates.
        listOf("sound", "acoustic", "audio", "noise", "speaker", "microphone test") to setOf(
            SensorChannel.MICROPHONE, SensorChannel.CAMERA, SensorChannel.ACCELEROMETER,
        ),
    )

    /** Suggest a channel set for the typed nature of work. */
    fun suggest(goal: String): Set<SensorChannel> {
        val g = goal.lowercase()
        rules.firstOrNull { (keywords, _) -> keywords.any { it in g } }
            ?.let { return it.second }
        return DEFAULT_SUGGESTION
    }
}

/**
 * Encode/decode a channel selection for the session-start intent extra.
 * Comma-joined channel ids, order-independent. Empty set = capture nothing,
 * which the service treats as invalid (a session with zero sensors is a
 * user error the dialog prevents).
 */
object SensorChannelCodec {
    fun encode(channels: Set<SensorChannel>): String =
        channels.joinToString(",") { it.id }

    fun parse(raw: String?): Set<SensorChannel> =
        raw?.split(',')?.mapNotNull { SensorChannel.fromId(it.trim()) }?.toSet() ?: emptySet()
}
