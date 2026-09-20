package com.echo.analysis

import com.echo.core.model.Modality

/**
 * One recording-channel segment fed to the [SessionAnalyzer]: raw time-stamped
 * values from a single sensor stream. The analyzer is offline/whole-session —
 * sources may interleave and drop out (a covered camera, a gyro-less device),
 * channels are processed independently.
 *
 * No Android imports: capture adapters convert from live ring buffers or
 * persisted traces; for now the JVM tests feed synthetic streams.
 */
data class ChannelSeries(
    val kind: String,
    val modality: Modality,
    val unit: String,
    val samples: List<Sample>,
    /** How this channel's deviations are detected (a channel uses one method). */
    val detector: DetectorKind = DetectorKind.LEVEL,
) {
    /** One measured value at a session-relative millisecond timestamp. */
    data class Sample(val tMs: Long, val value: Double)
}

/** How a channel's deviations are detected; a channel uses one method. */
enum class DetectorKind {
    /**
     * Absolute level vs rolling baseline (acceleration magnitude, RMS, luma,
     * magnetic field) — level jumps and sustained episodes.
     */
    LEVEL,

    /**
     * Rate-of-change (barometric dP/dt, tilt rotation rate): slow drift is
     * normal weather; fast change is an event.
     */
    RATE,
}

/**
 * Analyzer tuning, all relative — nothing is hardcoded to one session type.
 * The same defaults must pass both validation fixtures (mechanical rig with
 * constant vibration; 8 h sleep with a door slam and restlessness), which is
 * the whole point: the baseline adapts per session, thresholds don't.
 */
data class AnalyzerConfig(
    /**
     * Rolling baseline window length in samples. ~20 s at 50 Hz motion
     * rates, ~100 s at sleep-sampling rates; both sit well below the episode
     * timescales the detectors must resolve.
     */
    val baselineSamples: Int = 400,
    /** Deviation above this many σ (level) flags a point anomaly. */
    val levelSigma: Double = 4.0,
    /** Deviation above this many σ (rate-of-change) flags a jump. */
    val rateSigma: Double = 4.0,
    /**
     * Quiet-channel bar. A channel whose *rolling* σ (before the relative
     * floor) stays below this is considered near-constant — a resting phone,
     * a silent room. Its sub-[levelSigma] wobble is calibration drift, not
     * signal: a spike needs the full [levelSigma] to count instantly, and a
     * sustained episode must hold for [quietSustainedMs] (not just
     * [minEpisodeMs]) to be kept — real Gaussian noise on a quiet channel
     * produces 2–3 σ blips lasting ~2 s, never tens of seconds. Genuine
     * deviations on quiet channels are enormous in σ (a door slam in a
     * dead-quiet room is hundreds of σ) and still flag instantly.
     */
    val minNoiseSigma: Double = 0.25,
    /**
     * How long a sustained episode on a quiet channel must hold before it is
     * kept (see [minNoiseSigma]). Non-quiet channels use [minEpisodeMs].
     */
    val quietSustainedMs: Long = 10_000,
    /**
     * σ never drops below this fraction of |mean| (same idea as the online
     * [com.echo.perception.BaselineTracker] relative floor): a near-constant
     * signal with ±2 % wobble must not turn 5 % excursions into 5σ events.
     * Applied per window, on top of the raw rolling σ.
     */
    val relativeSigmaFloor: Double = 0.05,
    /**
     * RATE channels score step-to-step deltas, whose raw σ can be microscopic
     * (a quiet barometer's walk is ±0.001 hPa) — without a scale floor every
     * real step becomes hundreds of σ and weather noise floods the timeline.
     * The floor is this fraction of the channel's overall |mean level| — the
     * same 0.02 % the online barometer baseline is tuned to — so typical
     * per-step weather noise stays invisible while genuine transients (a door
     * slam's pressure wave, an HVAC damper snap) still clear it. Channels
     * centred near zero (tilt rate) get a ≈0 floor and fall back to raw σ.
     */
    val rateRelativeFloor: Double = 0.0002,
    /**
     * No detection before this many scored samples: the baseline must learn
     * the room first. The first seconds of a session are calibration, not
     * evidence (mirrors the digest's 30 s baseline-calibration window).
     */
    val minWarmupSamples: Int = 50,
    /** An episode stays open while values exceed this σ. */
    val episodeSigma: Double = 2.5,
    /** Minimum duration for a distinct sustained episode. */
    val minEpisodeMs: Long = 2_000,
    /** Gaps shorter than this inside an elevated run don't close it. */
    val episodeMergeMs: Long = 1_500,
    /** Deviations closer than this fuse across channels. */
    val fusionWindowMs: Long = 1_000,
    /**
     * Per-channel trust weight when scoring fused events. Motion channels
     * rank highest: the phone is mechanically coupled to the rig.
     */
    val modalityWeight: Map<Modality, Double> =
        mapOf(Modality.MOTION to 1.0, Modality.AUDIO to 0.9,
            Modality.VISION to 0.8, Modality.ENVIRONMENT to 0.5),
    /**
     * Single-channel events below this confidence are dropped entirely
     * (noise-floor hygiene); fused events are exempt (corroboration lifts
     * them).
     */
    val minConfidence: Double = 0.35,
) {
    companion object {
        val DEFAULT = AnalyzerConfig()
    }
}
