package com.echo.core

/**
 * Canonical audio capture format, shared by the capability probe and the live
 * detection pipeline. Kept free of Android imports so the whole detection and
 * correlation stack stays unit-testable on a plain JVM.
 */
object AudioSpec {
    /** 16 kHz mono is what YAMNet expects and is plenty for impact detection. */
    const val SAMPLE_RATE_HZ = 16_000

    /** 20 ms analysis hop: fine enough to timestamp an impact precisely. */
    const val HOP_MS = 20

    /** Samples in one analysis hop. */
    const val HOP_SAMPLES = SAMPLE_RATE_HZ / 1000 * HOP_MS
}
