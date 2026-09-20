package com.echo.analysis

import com.echo.core.model.Modality
import kotlin.random.Random

/**
 * Deterministic synthetic sessions for validating the [SessionAnalyzer] —
 * the two fixtures [AnalyzerConfig] was designed around. The same analyzer
 * defaults must pass both: the baseline adapts per session, thresholds don't.
 *
 * Noise model: bounded pseudo-gaussian — the Irwin–Hall sum of three uniforms
 * (σ ≈ 0.5) scaled to the target σ and clamped to ±2.2σ — deliberately below
 * the 2.5σ episode threshold with margin for rolling-window σ sampling
 * variation (±4 % over 400 samples), so no noise draw can ever open an
 * episode: every flagged event in these fixtures comes from a scripted
 * disturbance. Sensor noise is physically bounded anyway (quantization).
 * Pressure (a RATE channel) uses a bounded random walk instead — barometer
 * noise is slow and correlated, and a walk keeps step-to-step rates small.
 */
object Fixtures {

    /** Bounded noise: pseudo-gaussian texture, hard ±2.2σ tails — below the
     *  2.5σ episode threshold even against window-σ sampling variation. */
    private fun noise(r: Random, sigma: Double): Double {
        val u = (r.nextDouble() + r.nextDouble() + r.nextDouble()) - 1.5
        return 2.0 * sigma * u.coerceIn(-1.1, 1.1)
    }

    /**
     * **Mechanical rig** — 12 min of DC-motor rig work on a bench, phone
     * mounted on the base plate:
     *  - 0–9 min: constant running machine — motor vibration on the
     *    accelerometer, steady motor hum, idle frame motion. This stretch is
     *    *expected normal* and must produce zero events.
     *  - +6 min: scripted impact — a part falls: audio spike, accelerometer
     *    spike, frame-motion burst within ~400 ms → one fused anomaly.
     *  - +9 min: machine switched off — vibration, hum and motion all drop
     *    to near zero; a genuine change-point the baseline must flag.
     */
    fun mechanicalRig(): List<ChannelSeries> {
        val r = Random(42)
        val impactT = 360_000L
        val stopT = 540_000L
        val endT = 720_000L

        val accel = ArrayList<ChannelSeries.Sample>(36_001)
        val audio = ArrayList<ChannelSeries.Sample>(36_001)

        var t = 0L
        while (t <= endT) {
            val impactAccel = if (t in impactT until impactT + 120) 2.1 * (1.0 - (t - impactT) / 120.0) else 0.0
            val impactAudio = if (t in impactT until impactT + 200) 0.8 * (1.0 - (t - impactT) / 200.0) else 0.0
            val running = t < stopT

            accel += ChannelSeries.Sample(
                t,
                if (running) 0.42 + 0.012 * kotlin.math.sin(2.0 * Math.PI * 7.0 * t / 1000.0) +
                    noise(r, 0.006) + impactAccel
                else 0.02 + noise(r, 0.001),
            )
            audio += ChannelSeries.Sample(
                t,
                if (running) 0.08 + noise(r, 0.006) + impactAudio
                else 0.005 + noise(r, 0.001),
            )
            t += 20
        }

        // Camera at true 8 fps: its own 125 ms cadence.
        val frame = ArrayList<ChannelSeries.Sample>(5_761)
        var tf = 0L
        while (tf <= endT) {
            val impactFrame = if (tf in impactT until impactT + 400) 25.0 * (1.0 - (tf - impactT) / 400.0) else 0.0
            frame += ChannelSeries.Sample(
                tf,
                if (tf < stopT) 0.8 + noise(r, 0.15) + impactFrame
                else 0.02 + noise(r, 0.02),
            )
            tf += 125
        }

        return listOf(
            ChannelSeries("ACCEL_MAG", Modality.MOTION, "m/s2", accel),
            ChannelSeries("AUDIO_RMS", Modality.AUDIO, "rms", audio),
            ChannelSeries("FRAME_MOTION", Modality.VISION, "%", frame),
        )
    }

    /**
     * **Sleep night** — 8 h with the phone on a nightstand: mostly a
     * dead-quiet baseline (stillness, silence, dark frame, slow barometric
     * drift). Scripted disturbances:
     *  - +3 h 12 min: door slam — audio + accelerometer + frame spike within
     *    a second → one fused anomaly.
     *  - +5 h: restlessness — 90 s of elevated motion and murmur → sustained
     *    multi-channel episode.
     *  - +6 h: HVAC pressure gust — a barometric rate swing the RATE
     *    channel's level-scaled σ floor keeps out of the timeline (0.15 hPa
     *    per 2 s step sits below the 0.02 % level floor, mirroring the online
     *    barometer tuning): weather noise never floods the timeline.
     */
    fun sleepNight(): List<ChannelSeries> {
        val r = Random(7)
        val slamT = 11_520_000L            // 3 h 12 min
        val restlessStart = 18_000_000L    // 5 h
        val restlessEnd = 18_090_000L      // +90 s
        val gustStart = 21_600_000L        // 6 h
        val gustEnd = 21_620_000L          // 20 s of pressure swing
        val endT = 28_800_000L             // 8 h

        val accel = ArrayList<ChannelSeries.Sample>(28_801)
        val audio = ArrayList<ChannelSeries.Sample>(28_801)
        val frame = ArrayList<ChannelSeries.Sample>(14_401)
        val pressure = ArrayList<ChannelSeries.Sample>(14_401)
        var walk = 0.0

        var t = 0L
        while (t <= endT) {
            val restless = t in restlessStart..restlessEnd
            val slamAccel = when (t) {
                slamT -> 0.165
                slamT + 1_000 -> 0.10
                else -> 0.0
            }
            val slamAudio = if (t == slamT) 0.35 else 0.0
            val slamFrame = if (t == slamT) 2.0 else 0.0

            accel += ChannelSeries.Sample(
                t,
                if (restless) 0.06 + noise(r, 0.003) else 0.015 + noise(r, 0.004) + slamAccel,
            )
            audio += ChannelSeries.Sample(
                t,
                if (restless) 0.013 + noise(r, 0.0008) else 0.004 + noise(r, 0.0015) + slamAudio,
            )
            if (t % 2_000 == 0L) {
                frame += ChannelSeries.Sample(
                    t,
                    if (restless) 0.8 + noise(r, 0.1) else 0.1 + noise(r, 0.05) + slamFrame,
                )
                // Slow barometric drift + bounded random-walk noise; the gust
                // ramps the pressure down 0.15 hPa per step, then recovers.
                val gustOffset = when {
                    t in gustStart until gustStart + 8_000 ->
                        -0.15 * (1 + (t - gustStart) / 2_000)
                    t in gustStart + 8_000 until gustStart + 12_000 -> -0.60
                    t in gustStart + 12_000 until gustEnd -> -0.60 + 0.15 * (1 + (t - gustStart - 12_000) / 2_000)
                    else -> 0.0
                }
                walk = (walk + (if (r.nextBoolean()) 0.001 else -0.001)).coerceIn(-0.05, 0.05)
                pressure += ChannelSeries.Sample(
                    t,
                    1013.2 + 0.4 * kotlin.math.sin(2.0 * Math.PI * t / 7_200_000.0) + walk + gustOffset,
                )
            }
            t += 1_000
        }

        return listOf(
            ChannelSeries("ACCEL_MAG", Modality.MOTION, "m/s2", accel),
            ChannelSeries("AUDIO_RMS", Modality.AUDIO, "rms", audio),
            ChannelSeries("FRAME_MOTION", Modality.VISION, "%", frame),
            ChannelSeries("PRESSURE", Modality.ENVIRONMENT, "hPa", pressure, DetectorKind.RATE),
        )
    }
}
