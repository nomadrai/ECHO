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
 * Relation between two events, always labelled co-occurrence — never causality
 * (plan §7: no causal claim is ever stored as fact).
 */
enum class RelationKind { PRECEDES, CO_OCCURS, SUSTAINED_WITH }

/** A relation edge between two events, with the window it was judged in. */
data class EventRelation(
    val fromEventId: Long,
    val toEventId: Long,
    val kind: RelationKind,
    val deltaMs: Long,
    val windowMs: Long,
    val confidence: Double,
)

/**
 * A typed, timestamped, confidence-tiered occurrence.
 *
 * No causal claim is ever stored as fact — cross-modal relations
 * (PRECEDES / CO_OCCURS) are added by the correlation engine.
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
