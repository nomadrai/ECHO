package com.echo.perception

import kotlin.math.sqrt

/**
 * Deterministic audio DSP over 20 ms PCM16 hops ([com.echo.core.AudioSpec]).
 * Pure Kotlin — the identical math runs on the phone and in JVM tests.
 */
object AudioDsp {

    /** One-pole low-pass coefficient; ~4 kHz corner at a 16 kHz sample rate. */
    private const val LP_ALPHA = 0.5

    /** Root-mean-square level in [0, 1]. */
    fun rms(samples: ShortArray, len: Int): Double {
        var acc = 0.0
        for (i in 0 until len) {
            val s = samples[i] / 32768.0
            acc += s * s
        }
        return if (len == 0) 0.0 else sqrt(acc / len)
    }

    /** Fraction of adjacent-sample sign changes; high for hiss/tones, low for rumble. */
    fun zeroCrossingRate(samples: ShortArray, len: Int): Double {
        if (len < 2) return 0.0
        var crossings = 0
        for (i in 1 until len) {
            if ((samples[i - 1] < 0) != (samples[i] < 0)) crossings++
        }
        return crossings.toDouble() / (len - 1)
    }

    /**
     * Band RMS pair (low, high) from a one-pole split — separates rumble from
     * hiss so the dashboard shows *why* the energy gate opened, not just that
     * it did.
     */
    fun bandEnergies(samples: ShortArray, len: Int): Pair<Double, Double> {
        var lp = 0.0
        var lowAcc = 0.0
        var highAcc = 0.0
        for (i in 0 until len) {
            val s = samples[i] / 32768.0
            lp += LP_ALPHA * (s - lp)
            lowAcc += lp * lp
            highAcc += (s - lp) * (s - lp)
        }
        return if (len == 0) {
            0.0 to 0.0
        } else {
            sqrt(lowAcc / len) to sqrt(highAcc / len)
        }
    }
}
