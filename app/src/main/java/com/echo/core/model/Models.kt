package com.echo.core.model

/** Sensing modality of an observation or event. */
enum class Modality { AUDIO, VISION, MOTION, ENVIRONMENT }

/** Confidence tiers; UNCONFIRMED events are kept, never deleted. */
enum class EventTier { CONFIRMED, PROBABLE, UNCONFIRMED }

/**
 * A measurable primitive with no interpretation (the plan's three-tier
 * semantics: observation → event → inference). Pure data — no Android
 * imports — so detectors, the extractor, and JVM tests share one definition.
 */
data class Observation(
    val sessionId: Long = 0,
    val tMs: Long,
    val modality: Modality,
    val kind: String,
    val value: Double,
    val unit: String,
    val baselineValue: Double? = null,
    val deviationSigma: Double? = null,
)

/**
 * A typed, timestamped, confidence-tiered occurrence.
 *
 * No causal claim is ever stored as fact — cross-modal relations
 * (PRECEDES / CO_OCCURS) are added by the correlation engine in M1.
 */
data class Event(
    val id: Long,
    val sessionId: Long = 0,
    val tStartMs: Long,
    val tEndMs: Long,
    val type: String,
    val modalities: Set<Modality>,
    val tier: EventTier,
    val confidence: Double,
    val salience: Double,
    val description: String,
    val observationIds: List<Long> = emptyList(),
    val evidenceIds: List<Long> = emptyList(),
)
