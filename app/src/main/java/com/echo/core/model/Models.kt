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

/**
 * Where a timeline item came from. User tags and auto-detected events share
 * the timeline but are never conflated: the source is stored, not implied.
 */
enum class TimelineSource { USER, AUTO_DETECTED }

/**
 * Coarse preset buckets a manual tag can fall under (free text is allowed —
 * the category is always optional and defaults to [NOTE]).
 */
enum class TagCategory {
    STARTED, NOISE, ANOMALY, WOKE_UP, NOTE;

    companion object {
        /** Parses a stored name safely; unknown names degrade to [NOTE]. */
        fun fromName(name: String?): TagCategory =
            entries.firstOrNull { it.name == name } ?: NOTE
    }
}

/**
 * A moment the user marked by hand — live ("tag this moment") or
 * retroactively during review. Stored in its own table, separate from
 * auto-detected [Event]s, and merged only at display/digest time.
 *
 * @param id          store row id (0 until persisted)
 * @param sessionId   owning session
 * @param tMs         session-relative timestamp the user marked
 * @param label       free text naming what happened
 * @param category    optional preset bucket, [TagCategory.NOTE] when none
 * @param source      provenance, stored explicitly (USER for anything the
 *                    user created; the field exists so timeline consumers
 *                    never infer it)
 * @param matchedEventId  left null at creation — filled later when a
 *                    validation pass links this tag to an auto-detected
 *                    event (accuracy checking, plan §10); nullable forever
 * @param createdAtEpochMs  wall-clock time the tag was written, so retro
 *                    tags are distinguishable from live ones
 */
data class ManualTag(
    val id: Long = 0,
    val sessionId: Long = 0,
    val tMs: Long,
    val label: String,
    val category: TagCategory = TagCategory.NOTE,
    val source: TimelineSource = TimelineSource.USER,
    val matchedEventId: Long? = null,
    val createdAtEpochMs: Long = 0,
)
