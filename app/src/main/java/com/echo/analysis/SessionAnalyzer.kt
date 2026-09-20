package com.echo.analysis

import com.echo.core.model.Modality
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The local, deterministic event-extraction layer between raw sensor capture
 * and the AI: whole-session, offline, pure Kotlin — no model, no network,
 * O(n) per channel. It turns per-channel raw series into a compact
 * [SessionTimeline] that is the *only* session data the AI layer ever sees,
 * so context window and API cost stay flat with session length (an 8 h sleep
 * session condenses to a few dozen objects, never the raw floats).
 *
 * Pipeline, per channel (channels are independent — sources may interleave
 * and drop out):
 *  1. **Rolling baseline** — a causal sliding window (the last
 *     [AnalyzerConfig.baselineSamples] scored values, never future samples)
 *     gives mean + σ of "normal" for *this* stream in *this* session; nothing
 *     is hardcoded, so a rig's vibration and a night's stillness each learn
 *     their own normal. No scoring before [AnalyzerConfig.minWarmupSamples]:
 *     the first seconds are calibration, not evidence.
 *  2. **Detection** — LEVEL channels score |value − mean| in σ; RATE channels
 *     score |Δvalue| in σ (slow drift is normal weather, fast change is an
 *     event). σ has a relative floor ([AnalyzerConfig.relativeSigmaFloor]) so
 *     near-constant signals (constant machine vibration) cannot amplify
 *     microscopic wobble into false σ.
 *  3. **Episode building** — consecutive elevated samples merge into one
 *     episode (gaps ≤ [AnalyzerConfig.episodeMergeMs] bridge); an episode is
 *     kept if it spiked ≥ levelSigma at least once, or stayed elevated
 *     ≥ [AnalyzerConfig.minEpisodeMs] — short blips are noise.
 *  4. **Fusion** — episodes from different channels starting within
 *     [AnalyzerConfig.fusionWindowMs] merge into one multi-channel ANOMALY
 *     (the same real-world moment is never reported as three unrelated
 *     events); single-channel survivors stay EVENTs, and weak ones below
 *     [AnalyzerConfig.minConfidence] are dropped — fused events are exempt.
 *
 * Quiet-channel rule ([AnalyzerConfig.minNoiseSigma]): a channel whose
 * rolling σ stays below the bar (silent room, resting phone) demands the full
 * levelSigma for an instant spike, and [AnalyzerConfig.quietSustainedMs] (not
 * just minEpisodeMs) for a sustained episode — its 2–3 σ micro-blips are
 * calibration drift and never reach the timeline, while a genuine deviation
 * there (a door slam in a dead-quiet room is hundreds of σ) still flags
 * instantly. Gaps between consecutive samples of one channel never split an
 * episode: the merge window is at least the channel's own sampling step.
 *
 * Evidence references, not raw data: each member carries the segment window
 * plus its mean/peak/σ statistics — a pointer to *which* raw clip/segment
 * backs the event — never the raw stream itself.
 *
 * Deterministic: sorted inputs, single forward pass, no randomness, no clock.
 * The same input always yields the same JSON. Not thread-safe — one analyzer
 * instance per run, like the offline tool it is.
 */
class SessionAnalyzer(private val config: AnalyzerConfig = AnalyzerConfig.DEFAULT) {

    /**
     * Analyze whole-session channel series. [goal] and [workContext] ride
     * along as metadata only (the caller classifies the goal via
     * `com.echo.ai.WorkContext`; this package stays independent of the AI
     * layer). They never influence detection.
     */
    fun analyze(
        channels: List<ChannelSeries>,
        goal: String = "",
        workContext: String = "",
    ): SessionTimeline {
        val results = channels.map { analyzeChannel(it) }
        return SessionTimeline(
            durationMs = results.maxOfOrNull { it.lastTMs } ?: 0L,
            goal = goal,
            workContext = workContext,
            events = fuse(results),
            channelStats = results.map { it.stats },
        )
    }

    // --- per-channel detection ----------------------------------------------

    /** One flagged deviation on one channel, before cross-channel fusion. */
    private class Episode(
        val tStartMs: Long,
        var tEndMs: Long,
        var peakSigma: Double,
        /** Direction of the dominant excursion (+1 above / −1 below baseline). */
        var direction: Int,
        var triggered: Boolean,
        /** Opened on a quiet (near-constant) channel — see AnalyzerConfig. */
        val quiet: Boolean,
    )

    /** A channel's detection output: stats + kept episodes. */
    private class ChannelResult(
        val series: ChannelSeries,
        val stats: ChannelStats,
        val episodes: List<Episode>,
        val lastTMs: Long,
    )

    /** An episode paired with its channel, for the fusion pass. */
    private data class MemberRef(val result: ChannelResult, val episode: Episode)

    private fun analyzeChannel(series: ChannelSeries): ChannelResult {
        val samples = series.samples.sortedBy { it.tMs }
        val lastTMs = samples.lastOrNull()?.tMs ?: 0L

        // Scored series: LEVEL scores the value itself; RATE scores the
        // step-to-step change, attributed to the later timestamp.
        val scored = ArrayList<Sample>(samples.size)
        if (series.detector == DetectorKind.LEVEL) {
            for (s in samples) scored += Sample(s.tMs, s.value)
        } else {
            for (i in 1 until samples.size) {
                scored += Sample(samples[i].tMs, samples[i].value - samples[i - 1].value)
            }
        }

        // RATE channels score deltas whose raw σ can be microscopic; floor it
        // against the channel's overall level scale (see AnalyzerConfig).
        val rateFloor = if (series.detector == DetectorKind.RATE && samples.isNotEmpty()) {
            abs(samples.sumOf { it.value } / samples.size) * config.rateRelativeFloor
        } else 0.0

        // A gap between consecutive samples of the same channel is not a gap:
        // the merge window must never be shorter than the channel's own
        // sampling step (median of consecutive intervals), or a coarse channel
        // (a 2 s frame sample) would fragment one episode per sample.
        val mergeMs = if (samples.size > 2) {
            val steps = samples.zipWithNext { a, b -> b.tMs - a.tMs }.sorted()
            max(config.episodeMergeMs, steps[steps.size / 2])
        } else config.episodeMergeMs

        val episodes = ArrayList<Episode>()
        if (scored.size > config.minWarmupSamples) {
            // Causal sliding window over scored values, exclusive of the
            // current sample: at index i the window holds scored[i−W ..< i].
            // Running sum/sum² keeps the whole scan O(n).
            var winCount = 0
            var winSum = 0.0
            var winSumSq = 0.0
            var open: Episode? = null
            var lastElevatedT = Long.MIN_VALUE

            for (i in scored.indices) {
                val s = scored[i]
                if (i >= 1) {
                    val incoming = scored[i - 1]
                    winSum += incoming.value
                    winSumSq += incoming.value * incoming.value
                    winCount++
                    if (winCount > config.baselineSamples) {
                        val outgoing = scored[i - 1 - config.baselineSamples]
                        winSum -= outgoing.value
                        winSumSq -= outgoing.value * outgoing.value
                        winCount--
                    }
                }
                if (i < config.minWarmupSamples || winCount == 0) continue

                val mean = winSum / winCount
                val rawSigma = sqrt(max(0.0, winSumSq / winCount - mean * mean))
                val sigma = max(max(rawSigma, abs(mean) * config.relativeSigmaFloor), rateFloor)
                    .coerceAtLeast(SIGMA_EPS)
                val z = (s.value - mean) / sigma
                val az = abs(z)
                val elevated = az >= config.episodeSigma

                if (elevated) {
                    val e = open
                    if (e == null || s.tMs - lastElevatedT > mergeMs) {
                        open = Episode(
                            tStartMs = s.tMs,
                            tEndMs = s.tMs,
                            peakSigma = az,
                            direction = if (z >= 0) 1 else -1,
                            triggered = az >= config.levelSigma,
                            quiet = rawSigma < config.minNoiseSigma,
                        )
                        episodes += open
                    } else {
                        e.tEndMs = s.tMs
                        if (az > e.peakSigma) {
                            e.peakSigma = az
                            e.direction = if (z >= 0) 1 else -1
                        }
                        if (az >= config.levelSigma) e.triggered = true
                    }
                    lastElevatedT = s.tMs
                } else if (open != null && s.tMs - lastElevatedT > mergeMs) {
                    open = null // closed for good; a later elevation opens a new episode
                }
            }
        }

        // Noise-floor hygiene: an episode survives as an event if it spiked
        // (≥ levelSigma, instant) or held long enough — quiet channels need a
        // much longer hold, so their Gaussian micro-blips (2–3 σ for a couple
        // of samples) never reach the timeline.
        val kept = episodes.filter {
            it.triggered || it.tEndMs - it.tStartMs >=
                (if (it.quiet) config.quietSustainedMs else config.minEpisodeMs)
        }
        return ChannelResult(series, channelStats(series, samples), kept, lastTMs)
    }

    private fun channelStats(series: ChannelSeries, samples: List<ChannelSeries.Sample>): ChannelStats {
        if (samples.isEmpty()) {
            return ChannelStats(series.kind, series.modality, series.unit, 0, 0.0, 0.0, 0.0, 0.0)
        }
        var sum = 0.0
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (s in samples) {
            sum += s.value
            if (s.value < minV) minV = s.value
            if (s.value > maxV) maxV = s.value
        }
        val mean = sum / samples.size
        var sq = 0.0
        for (s in samples) sq += (s.value - mean) * (s.value - mean)
        return ChannelStats(
            channel = series.kind,
            modality = series.modality,
            unit = series.unit,
            samples = samples.size,
            mean = mean,
            sigma = sqrt(sq / samples.size),
            min = minV,
            max = maxV,
        )
    }

    // --- cross-channel fusion ------------------------------------------------

    /**
     * Strict-window fusion: episodes sorted by start; one joins the open
     * group only if it starts within [AnalyzerConfig.fusionWindowMs] of the
     * group's *first* member — no chaining, so a group never outgrows the
     * window. Groups with ≥2 distinct channels become ANOMALY; the rest are
     * single-channel EVENTs, dropped entirely when their confidence falls
     * below [AnalyzerConfig.minConfidence] (fused events are exempt).
     */
    private fun fuse(results: List<ChannelResult>): List<TimelineEvent> {
        val all = results.flatMap { r -> r.episodes.map { MemberRef(r, it) } }
            .sortedWith(compareBy({ it.episode.tStartMs }, { it.result.series.kind }))

        val events = ArrayList<TimelineEvent>()
        var group = ArrayList<MemberRef>()
        var groupStart = Long.MIN_VALUE
        var nextEventId = 1

        fun closeGroup() {
            if (group.isEmpty()) return
            val event = buildEvent(nextEventId, group)
            nextEventId++
            // Noise-floor hygiene: weak single-channel events never reach the
            // timeline; corroboration exempts fused ones.
            if (event.type == TYPE_ANOMALY || event.confidence >= config.minConfidence) {
                events += event
            }
            group = ArrayList()
        }

        for (m in all) {
            if (group.isNotEmpty() && m.episode.tStartMs - groupStart > config.fusionWindowMs) {
                closeGroup()
            }
            if (group.isEmpty()) groupStart = m.episode.tStartMs
            group += m
        }
        closeGroup()
        return events
    }

    private fun buildEvent(id: Int, group: List<MemberRef>): TimelineEvent {
        val members = group.map { (r, e) ->
            val segment = r.series.samples.filter { it.tMs in e.tStartMs..e.tEndMs }
            // Peak raw value in the direction of the deviation: a slam reads
            // its top amplitude, a drop-to-zero reads its floor.
            val peak = if (segment.isEmpty()) 0.0
            else if (e.direction >= 0) segment.maxOf { it.value }
            else segment.minOf { it.value }
            EventMember(
                channel = r.series.kind,
                modality = r.series.modality,
                tStartMs = e.tStartMs,
                tEndMs = e.tEndMs,
                mean = if (segment.isEmpty()) 0.0 else segment.sumOf { it.value } / segment.size,
                peak = peak,
                sigma = e.peakSigma,
            )
        }
        val distinctChannels = members.map { it.channel }.toSet().size
        val isFused = distinctChannels >= 2
        val peakSigma = members.maxOf { it.sigma }

        val confidence = if (isFused) {
            val weighted = group.sumOf { (r, e) ->
                memberConfidence(e.peakSigma) * config.modalityWeight.getOrElse(r.series.modality) { 0.5 }
            }
            val wSum = group.sumOf { (r, _) ->
                config.modalityWeight.getOrElse(r.series.modality) { 0.5 }
            }
            val wMean = if (wSum > 0) weighted / wSum else 0.0
            min(0.95, wMean + CORROBORATION_BONUS * (distinctChannels - 1))
        } else {
            val (r, e) = group.first()
            memberConfidence(e.peakSigma) * config.modalityWeight.getOrElse(r.series.modality) { 0.5 }
        }

        val description = if (isFused) {
            "Multi-channel deviation within ${config.fusionWindowMs} ms: " +
                members.joinToString(", ") { "${it.channel} ${fmt(it.sigma)}σ" } +
                " — corroborated across $distinctChannels channels"
        } else {
            val (r, e) = group.first()
            val m = members.first()
            val dir = if (e.direction >= 0) "above" else "below"
            val kind = if (e.triggered) "spike" else "sustained ${e.tEndMs - e.tStartMs} ms"
            "${r.series.kind} $kind: ${fmt(m.peak)} ${r.series.unit} $dir " +
                "baseline ${fmt(m.mean)} (${fmt(e.peakSigma)}σ)"
        }
        return TimelineEvent(
            id = id,
            tStartMs = group.first().episode.tStartMs,
            tEndMs = group.maxOf { it.episode.tEndMs },
            type = if (isFused) TYPE_ANOMALY else TYPE_EVENT,
            modalities = members.map { it.modality }.distinct(),
            confidence = confidence,
            peakSigma = peakSigma,
            members = members,
            description = description,
        )
    }

    /** Saturating σ→confidence curve: 4σ ≈ 0.40, 6σ ≈ 0.50, 14σ ≈ 0.70. */
    private fun memberConfidence(peakSigma: Double): Double = peakSigma / (peakSigma + CONFIDENCE_KNEE)

    private data class Sample(val tMs: Long, val value: Double)

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

    companion object {
        private const val SIGMA_EPS = 1e-12
        private const val CONFIDENCE_KNEE = 6.0
        private const val CORROBORATION_BONUS = 0.12
        const val TYPE_ANOMALY = "ANOMALY"
        const val TYPE_EVENT = "EVENT"
    }
}
