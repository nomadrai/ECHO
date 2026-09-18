package com.echo.data

import com.echo.core.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk storage format contract: modality bitmask round-trips exactly,
 * stays within its 4-bit budget, and matches the documented bit values.
 * (SQLite persistence itself is exercised on device; the JVM test pins the
 * *format* so a schema change cannot silently corrupt stored timelines.)
 */
class ModalityCodecTest {

    @Test
    fun `every single modality round-trips`() {
        for (m in Modality.entries) {
            val restored = ModalityCodec.fromMask(ModalityCodec.mask(setOf(m)).toLong())
            assertEquals(setOf(m), restored)
        }
    }

    @Test
    fun `multi-modality sets round-trip`() {
        val all = Modality.entries.toSet()
        val restored = ModalityCodec.fromMask(ModalityCodec.mask(all).toLong())
        assertEquals(all, restored)
        // A fused incident across audio + motion, the common correlation case.
        val pair = setOf(Modality.AUDIO, Modality.MOTION)
        assertEquals(pair, ModalityCodec.fromMask(ModalityCodec.mask(pair).toLong()))
    }

    @Test
    fun `bit values match the documented schema`() {
        assertEquals(1, ModalityCodec.mask(setOf(Modality.AUDIO)))
        assertEquals(2, ModalityCodec.mask(setOf(Modality.VISION)))
        assertEquals(4, ModalityCodec.mask(setOf(Modality.MOTION)))
        assertEquals(8, ModalityCodec.mask(setOf(Modality.ENVIRONMENT)))
        assertEquals(15, ModalityCodec.mask(Modality.entries.toSet()))
    }

    @Test
    fun `mask fits in the 4-bit storage budget`() {
        assertTrue(Modality.entries.size <= 4)
    }

    @Test
    fun `unknown bits are ignored rather than crashing reads`() {
        // Forward compatibility: bits for modalities added later must not
        // break older readers.
        assertEquals(setOf(Modality.MOTION), ModalityCodec.fromMask(0b10000L or 0b100L))
    }
}
