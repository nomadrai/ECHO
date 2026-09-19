package com.echo.analysis

import com.echo.core.model.Modality
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The analyzer's entire output for one session: a compact, JSON-serializable
 * timeline of discrete events. This — never the raw sensor stream — is what
 * the AI layer receives: an 8 h session condenses to a few dozen objects, so
 * the context window and API cost stay flat with session length.
 *
 * Everything is session-relative (+mm from session start); no wall clock, no
 * device identifiers, no raw values except the per-event statistics the model
 * needs to reason about magnitude.
 */
@Serializable
data class SessionTimeline(

    /** Format tag for forward compatibility of the JSON artifact. */
    val version: Int = 1,

    /** Session-relative duration in ms (end of the last sample). */
    val durationMs: Long,

    /** The user's stated goal, as entered at session start. */
    val goal: String = "",

    /** Classified nature of the work (drives expected-normal context). */
    val workContext: String = "",

    /** Fused + single-channel events, chronological. */
    val events: List<TimelineEvent> = emptyList(),

    /** Per-channel session statistics (what "normal" looked like). */
    val channelStats: List<ChannelStats> = emptyList(),
)

/**
 * One discrete occurrence on the timeline. A fused event carries the channel
 * breakdown; a single-channel event carries exactly one member.
 */
@Serializable
data class TimelineEvent(

    /** Stable within this timeline (E1, E2, … in emission order). */
    val id: Int,

    /** Session-relative start offset in ms. */
    val tStartMs: Long,

    /** Session-relative end offset in ms (episodes span; spikes are points). */
    val tEndMs: Long,

    /** ANOMALY (multi-channel, corroborated) / EVENT (single channel). */
    val type: String,

    /** Modalities that contributed; size > 1 means fused. */
    val modalities: List<Modality>,

    /**
     * 0..1 — per-channel σ-margin blended with the modality trust weights,
     * lifted by corroboration when fused.
     */
    val confidence: Double,

    /**
     * Peak deviation margin in σ above the channel's own baseline (max over
     * contributing channels) — the magnitude the model can quote.
     */
    val peakSigma: Double,

    /** Channels that contributed, with per-channel statistics. */
    val members: List<EventMember>,

    /** Short human-readable description. */
    val description: String,
)

/**
 * One channel's contribution to a [TimelineEvent] — the evidence reference:
 * it names the stream, the window, and the summary statistics of the raw
 * segment, so any viewer (or later evidence store) knows exactly which raw
 * clip/segment backs the event without carrying the raw data itself.
 */
@Serializable
data class EventMember(
    val channel: String,
    val modality: Modality,
    val tStartMs: Long,
    val tEndMs: Long,

    /** Mean of the raw segment inside the event window. */
    val mean: Double,

    /** Peak of the raw segment inside the event window. */
    val peak: Double,

    /** Peak deviation margin in σ from the channel baseline. */
    val sigma: Double,
)

/** What "normal" looked like for one channel across the whole session. */
@Serializable
data class ChannelStats(
    val channel: String,
    val modality: Modality,
    val unit: String,
    val samples: Int,
    val mean: Double,
    /** Population σ of all samples — the session's noise level. */
    val sigma: Double,
    val min: Double,
    val max: Double,
)
