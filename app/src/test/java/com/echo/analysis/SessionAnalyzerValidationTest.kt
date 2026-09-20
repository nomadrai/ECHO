package com.echo.analysis

import com.echo.core.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance spec for the extraction layer: the **same** analyzer
 * defaults must pass two very different session types.
 *
 *  - **Mechanical rig** (12 min, constant motor vibration + hum): the
 *    constant machine activity is *expected normal* and must produce zero
 *    events; the scripted impact and the machine-stop change-point must fire.
 *  - **Sleep night** (8 h, dead-quiet baseline): the slam and the restless
 *    episode must fire with huge σ margins; slow barometric drift and
 *    micro-noise must not.
 *
 * Passing both proves the baseline adapts per session and thresholds don't —
 * nothing about either session type is hardcoded.
 */
class SessionAnalyzerValidationTest {

    // --- mechanical rig ------------------------------------------------------

    @Test
    fun `mechanical rig - constant machine activity is normal, impact and stop are events`() {
        val timeline = SessionAnalyzer().analyze(
            Fixtures.mechanicalRig(),
            goal = "DC motor rig failure under load",
            workContext = "Mechanical / rig work",
        )

        // Exactly two fused anomalies: the impact and the machine stop.
        // Zero single-channel events — six minutes of constant vibration,
        // hum and idle motion produced NOTHING.
        assertEquals(2, timeline.events.size)
        assertTrue(timeline.events.all { it.type == SessionAnalyzer.TYPE_ANOMALY })
        assertTrue(timeline.events.all { it.members.size >= 2 })

        val impact = timeline.events[0]
        assertEquals(360_000L, impact.tStartMs)
        assertEquals(
            setOf(Modality.AUDIO, Modality.MOTION, Modality.VISION),
            impact.modalities.toSet(),
        )
        assertTrue(impact.peakSigma > 10.0)
        assertTrue(impact.confidence >= 0.9)

        // Evidence reference: the accelerometer member points at the raw
        // impact segment (top amplitude ≈ baseline + 2.1 g spike).
        val accelMember = impact.members.single { it.channel == "ACCEL_MAG" }
        assertEquals(360_000L, accelMember.tStartMs)
        assertTrue(accelMember.peak in 2.48..2.56)
        assertTrue(accelMember.mean > accelMember.peak - 1.0) // segment stats, not the whole stream

        val stop = timeline.events[1]
        assertEquals(540_000L, stop.tStartMs)
        assertTrue(stop.peakSigma > 5.0)

        // Baseline learned the rig: session-level stats reflect 9 min of
        // running machine + 3 min of silence.
        val accel = timeline.channelStats.single { it.channel == "ACCEL_MAG" }
        assertTrue("accel mean ${accel.mean}", accel.mean in 0.30..0.33)
        val audio = timeline.channelStats.single { it.channel == "AUDIO_RMS" }
        assertTrue("audio mean ${audio.mean}", audio.mean in 0.055..0.068)
        val frame = timeline.channelStats.single { it.channel == "FRAME_MOTION" }
        assertTrue("frame mean ${frame.mean}", frame.mean in 0.55..0.66)

        assertEquals(720_000L, timeline.durationMs)
    }

    // --- sleep night ---------------------------------------------------------

    @Test
    fun `sleep night - slam and restlessness fire, quiet baseline stays silent`() {
        val timeline = SessionAnalyzer().analyze(
            Fixtures.sleepNight(),
            goal = "overnight sleep monitoring",
            workContext = "General experiment",
        )

        // Exactly two anomalies in 8 hours: the door slam and the restless
        // episode. Micro-noise and slow barometric drift produced nothing.
        assertEquals(2, timeline.events.size)
        assertTrue(timeline.events.all { it.type == SessionAnalyzer.TYPE_ANOMALY })

        val slam = timeline.events[0]
        assertEquals(11_520_000L, slam.tStartMs)
        assertEquals(
            setOf(Modality.AUDIO, Modality.MOTION, Modality.VISION),
            slam.modalities.toSet(),
        )
        // The baseline adapted to dead quiet, so the slam registers with an
        // enormous σ margin — the payoff of per-session baselines.
        assertTrue("slam σ ${slam.peakSigma}", slam.peakSigma > 10.0)
        val audioMember = slam.members.single { it.channel == "AUDIO_RMS" }
        assertTrue("slam audio peak ${audioMember.peak}", audioMember.peak in 0.33..0.38)

        val restlessness = timeline.events[1]
        assertTrue(restlessness.tStartMs in 18_000_000..18_006_000)
        // Held far beyond the quiet-channel bar — a sustained episode, not a blip.
        assertTrue(
            "restless duration ${restlessness.tEndMs - restlessness.tStartMs}",
            restlessness.tEndMs - restlessness.tStartMs >= AnalyzerConfig.DEFAULT.quietSustainedMs,
        )
        assertEquals(
            setOf(Modality.AUDIO, Modality.MOTION, Modality.VISION),
            restlessness.modalities.toSet(),
        )

        // Baseline learned the night: every channel sits at its quiet level.
        val accel = timeline.channelStats.single { it.channel == "ACCEL_MAG" }
        assertTrue("accel mean ${accel.mean}", accel.mean in 0.0145..0.016)
        val audio = timeline.channelStats.single { it.channel == "AUDIO_RMS" }
        assertTrue("audio mean ${audio.mean}", audio.mean in 0.0038..0.0043)
        val pressure = timeline.channelStats.single { it.channel == "PRESSURE" }
        assertTrue("pressure mean ${pressure.mean}", pressure.mean in 1013.0..1013.4)

        assertEquals(28_800_000L, timeline.durationMs)
    }

    // --- cross-session adaptation -------------------------------------------

    @Test
    fun `same defaults, opposite baselines - nothing is hardcoded per session type`() {
        val rig = SessionAnalyzer().analyze(Fixtures.mechanicalRig())
        val sleep = SessionAnalyzer().analyze(Fixtures.sleepNight())

        val rigAccel = rig.channelStats.single { it.channel == "ACCEL_MAG" }
        val sleepAccel = sleep.channelStats.single { it.channel == "ACCEL_MAG" }
        // The identical detector calls 0.42 g of running rig "normal" and
        // 0.015 g of nightstand stillness "normal".
        assertTrue(rigAccel.mean > 20 * sleepAccel.mean)

        // And the same physical deviation size means opposite things: 0.06 g
        // is 11σ restlessness at night, yet inside the rig's normal band.
        val nightRestless = sleep.events[1].members.single { it.channel == "ACCEL_MAG" }
        assertTrue(nightRestless.sigma > 4.0)
        assertTrue(rigAccel.mean > nightRestless.peak)
    }
}
