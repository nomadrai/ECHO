package com.echo.ai

import java.util.Locale

/**
 * What the session was *about*, derived from the user's stated goal (captured
 * at START on the dashboard, persisted with the session row, editable in the
 * chat). The digest carries this to the model so interpretation is calibrated:
 * in mechanical work, vibration and machine noise are the machine *running* —
 * evidence of activity, not automatically an anomaly. Pure Kotlin, JVM-tested.
 *
 * The classifier is deliberately conservative: domain keywords are matched
 * case-insensitively anywhere in the goal; [expectedNormal] is only ever
 * *advisory* context for the model (the detector layer still extracts and
 * tiers every event — nothing is deleted or hidden).
 */
object WorkContext {

    enum class Domain(val displayName: String) {
        MECHANICAL("Mechanical / rig work"),
        ELECTRONICS("Electronics / embedded work"),
        CHEMISTRY("Chemistry / lab work"),
        THERMAL("Thermal / heating experiment"),
        ACOUSTIC("Acoustic / audio experiment"),
        CONSTRUCTION("Construction / structural work"),
        GENERAL("General experiment"),
    }

    data class Profile(
        val domain: Domain,
        /** Sensor signals that are plausibly part of the work itself. */
        val expectedNormal: List<String>,
        /** Calibration sentence injected into the digest + system prompt. */
        val guidance: String,
    )

    private data class Rule(val keywords: List<String>, val profile: Profile)

    private val rules = listOf(
        Rule(
            listOf("motor", "rig", "pump", "fan", "compressor", "drill", "lathe", "gear",
                "bearing", "shaft", "rotor", "engine", "turbine", "servo", "actuator",
                "vibrat", "conveyor", "machine"),
            Profile(
                Domain.MECHANICAL,
                listOf(
                    "SUSTAINED_NOISE (motor hum)",
                    "VIBRATION_BURST (running machinery)",
                    "MOTION_CONTINUOUS (rig movement)",
                    "MAGNETIC_DISTURBANCE (motor windings)",
                    "ACCEL_JOLT / DEVICE_SHOCK from handling the rig",
                ),
                "In mechanical work, sustained vibration, motor hum and machine noise are the " +
                    "machine RUNNING — treat them as normal operating context, not anomalies. " +
                    "What matters: CHANGES in tone/frequency/amplitude (TONE_CHANGE), impacts " +
                    "with a visual counterpart (fused CONFIRMED incidents), and deviations " +
                    "that START mid-session. Do not narrate steady-state vibration unless asked.",
            ),
        ),
        Rule(
            listOf("circuit", "solder", "pcb", "arduino", "raspberry", "esp32", "esp8266",
                "microcontroller", "board", "wire", "wiring", "electronics", "battery",
                "power supply", "relay"),
            Profile(
                Domain.ELECTRONICS,
                listOf(
                    "MAGNETIC_DISTURBANCE (transformers/relays near the phone)",
                    "LIGHT_CHANGE (bench lamp / indicator LEDs)",
                    "DEVICE_SHOCK from placing boards on the bench",
                ),
                "In electronics work, brief magnetic disturbances (relays, transformers), " +
                    "bench-lamp changes and small handling shocks are normal. What matters: " +
                    "fused CONFIRMED incidents (something moved/fell/shorted visibly), " +
                    "TONE_CHANGE, and any deviation that starts mid-session.",
            ),
        ),
        Rule(
            listOf("chemis", "titrat", "solution", "reaction", "reagent", "beaker", "flask",
                "acid", "solvent", "precipitate", "distil", "stir"),
            Profile(
                Domain.CHEMISTRY,
                listOf(
                    "LIGHT_CHANGE (fume-hood glass, lamps)",
                    "SUSTAINED_NOISE (fume hood, stirrer)",
                    "MAGNETIC_DISTURBANCE (stirrer motor)",
                ),
                "In chemistry work, stirrer hum, fume-hood noise and lighting changes are " +
                    "normal. What matters: PRESSURE_TRANSIENT (stopper/drop), impacts " +
                    "(glassware), fused CONFIRMED incidents, and deviations that start " +
                    "mid-session.",
            ),
        ),
        Rule(
            listOf("heat", "thermal", "oven", "furnace", "burner", "boil", "melt", "kiln",
                "soldering iron", "cure", "bake"),
            Profile(
                Domain.THERMAL,
                listOf(
                    "AMBIENT_TEMP drift (the experiment's purpose)",
                    "LIGHT_CHANGE (glow, heating elements)",
                    "SUSTAINED_NOISE (fans, burners)",
                ),
                "In thermal experiments, ambient-temperature drift, glow-related light " +
                    "changes and fan/burner noise are the experiment itself — normal. What " +
                    "matters: abrupt events (impacts, pressure transients, fused CONFIRMED " +
                    "incidents) and deviations that start mid-session.",
            ),
        ),
        Rule(
            listOf("sound", "acoustic", "audio", "noise", "speaker", "microphone", "decibel",
                "frequency", "tone"),
            Profile(
                Domain.ACOUSTIC,
                listOf(
                    "SUSTAINED_NOISE (the sound source under test)",
                    "SPEECH_PRESENT (people near the rig)",
                    "TONE_CHANGE (deliberate test signals)",
                ),
                "In acoustic experiments the sound source itself dominates the audio channel " +
                    "— sustained noise and tone changes may be the stimulus, not a fault. " +
                    "Weight vision and motion evidence for anomalies; treat audio as the " +
                    "stimulus unless the user asks about it.",
            ),
        ),
        Rule(
            listOf("concrete", "structure", "load", "beam", "stress", "strain", "civil",
                "construction", "weld", "drill", "hammer", "saw"),
            Profile(
                Domain.CONSTRUCTION,
                listOf(
                    "SUSTAINED_NOISE (power tools)",
                    "DEVICE_SHOCK / ACCEL_JOLT (drilling, hammering)",
                    "MOTION_CONTINUOUS (work in progress)",
                ),
                "In construction/structural work, tool noise, drilling shocks and continuous " +
                    "motion are the work itself — normal. What matters: impacts with a " +
                    "visual counterpart, TONE_CHANGE (tool changing cut), and deviations " +
                    "that start mid-session.",
            ),
        ),
    )

    /** Match the goal against domain keywords; fall back to a general profile. */
    fun classify(goal: String): Profile {
        val g = goal.lowercase(Locale.US)
        rules.firstOrNull { rule -> rule.keywords.any { it in g } }?.let { return it.profile }
        return Profile(
            Domain.GENERAL,
            listOf(
                "SUSTAINED_NOISE / VIBRATION_BURST (steady environment or work activity)",
                "LIGHT_CHANGE (lighting moved during setup)",
            ),
            "Interpret every event against what the user said they were doing: signals " +
                "consistent with the activity itself are context, not anomalies. What " +
                "matters: fused CONFIRMED incidents, TONE_CHANGE, and deviations that " +
                "start mid-session.",
        )
    }
}
