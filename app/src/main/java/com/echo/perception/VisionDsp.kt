package com.echo.perception

import kotlin.math.abs

/**
 * Luma frame-diff, Android-free: operates on a packed 8-bit luma plane the
 * caller produces from an ImageProxy Y plane (or any greyscale frame).
 * Deterministic — the identical math runs in JVM tests.
 */
object VisionDsp {

    data class FrameStats(
        val changedFraction: Double,
        val meanAbsDiff: Double,
        val meanLuma: Double,
    )

    /** Box-average downscale from srcW×srcH into a dstW×dstH buffer. */
    fun downscale(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, dst: ByteArray) {
        for (dy in 0 until dstH) {
            val sy0 = dy * srcH / dstH
            val sy1 = ((dy + 1) * srcH / dstH).coerceAtLeast(sy0 + 1)
            for (dx in 0 until dstW) {
                val sx0 = dx * srcW / dstW
                val sx1 = ((dx + 1) * srcW / dstW).coerceAtLeast(sx0 + 1)
                var acc = 0
                var count = 0
                var y = sy0
                while (y < sy1) {
                    var x = sx0
                    val row = y * srcW
                    while (x < sx1) {
                        acc += src[row + x].toInt() and 0xFF
                        count++
                        x++
                    }
                    y++
                }
                dst[dy * dstW + dx] = (acc / count.coerceAtLeast(1)).toByte()
            }
        }
    }

    /**
     * Diff against a reference frame: fraction of pixels that moved more than
     * [threshold] luma levels, mean absolute difference, mean luma.
     */
    fun diff(
        current: ByteArray,
        reference: ByteArray,
        size: Int,
        threshold: Int = 18,
    ): FrameStats {
        var changed = 0
        var totalAbs = 0L
        var luma = 0L
        for (i in 0 until size) {
            val c = current[i].toInt() and 0xFF
            val r = reference[i].toInt() and 0xFF
            val d = abs(c - r)
            if (d > threshold) changed++
            totalAbs += d
            luma += c
        }
        val n = size.coerceAtLeast(1).toDouble()
        return FrameStats(changed / n, totalAbs / n, luma / n)
    }
}
