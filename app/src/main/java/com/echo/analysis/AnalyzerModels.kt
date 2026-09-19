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
     * Minimum σ gap between baseline noise and flagged anomalies. Channels
     * with post-warmup σ below this are treated as quiet and effectively
     * exempt (their variation is calibration drift, not signal).
     */
    val minNoiseSigma: Double = 0.25,
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
