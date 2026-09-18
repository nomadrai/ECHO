package com.echo.perception

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Adaptive EMA baseline for one detector stream: learns the local "normal" and
 * scores new values as deviation in σ. Pure Kotlin (unit-tested).
 *
 * A σ floor (absolute, plus relative to the mean) keeps near-constant signals —
 * a silent room, a resting phone — from amplifying microscopic drift into
 * false events. This is the main false-positive control of the whole pipeline.
 */
class BaselineTracker(
    private val alpha: Double = 0.02,
    private val minSamples: Int = 300,
    private val sigmaFloor: Double = 0.0,
    /**
     * σ never drops below this fraction of |mean|. The absolute [sigmaFloor]
     * alone is wrong for large-magnitude channels (a barometer resting at
     * 101 kPa, gravity at 9.81 m/s²) where tiny *relative* wobble is normal
     * noise; without a relative floor every gust becomes a 5σ event. Defaults
     * to the legacy 2 %-of-mean floor; slow-drifting environment channels pass
     * a much smaller value (the barometer needs 0.02 %).
     */
    private val relativeSigmaFloor: Double = 0.02,
) {
    private var mean = 0.0
    private var emaVar = 0.0
    private var n = 0
    private var started = false

    fun update(value: Double) {
        lastValue = value
        if (!started) {
            mean = value
            emaVar = 0.0
            started = true
            n = 1
            return
        }
        val delta = value - mean
        mean += alpha * delta
        emaVar = (1.0 - alpha) * emaVar + alpha * delta * delta
        n++
    }

    /** Events are suppressed until the baseline has learned this many samples. */
    val ready: Boolean get() = n >= minSamples

    val baselineMean: Double get() = mean
    val sampleCount: Int get() = n

    /** Most recent value fed to [update]; for UI meters between events. */
    var lastValue: Double = Double.NaN
        private set

    val baselineSigma: Double
        get() = max(sqrt(emaVar), max(sigmaFloor, abs(mean) * relativeSigmaFloor))

    fun deviationSigma(value: Double): Double = (value - mean) / baselineSigma
}
