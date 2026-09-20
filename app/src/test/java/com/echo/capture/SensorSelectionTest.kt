package com.echo.capture

import com.echo.core.model.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection plumbing on a plain JVM: intent codec round-trips, the
 * rule-based suggestions (instant, offline — no AI), and goal-key
 * normalization. The service gating itself (sources never constructed when
 * disabled) is Android-bound; the rules here are what the dialog previews.
 */
class SensorSelectionTest {

    // --- codec ---------------------------------------------------------------

    @Test
    fun `codec round-trips a selection`() {
        val set = setOf(SensorChannel.CAMERA, SensorChannel.BAROMETER, SensorChannel.STEPS)
        assertEquals(set, SensorChannelCodec.parse(SensorChannelCodec.encode(set)))
    }

    @Test
    fun `parse is order-independent`() {
        assertEquals(
            SensorChannelCodec.parse("mic,camera"),
            SensorChannelCodec.parse("camera,mic"),
        )
    }

    @Test
    fun `parse of null or blank means legacy capture-everything`() {
        assertEquals(emptySet<SensorChannel>(), SensorChannelCodec.parse(null))
        assertEquals(emptySet<SensorChannel>(), SensorChannelCodec.parse(""))
        assertEquals(emptySet<SensorChannel>(), SensorChannelCodec.parse("  "))
    }

    @Test
    fun `parse skips unknown ids instead of failing`() {
        val parsed = SensorChannelCodec.parse("camera,future_sensor,mic")
        assertEquals(setOf(SensorChannel.CAMERA, SensorChannel.MICROPHONE), parsed)
    }

    @Test
    fun `every channel round-trips through the codec`() {
        assertEquals(SensorChannel.ALL, SensorChannelCodec.parse(SensorChannelCodec.encode(SensorChannel.ALL)))
    }

    // --- suggestions ---------------------------------------------------------

    @Test
    fun `sleep suggests no camera and no high-rate motion stack`() {
        val s = WorkTypeSuggestions.suggest("sleeping")
        assertTrue(SensorChannel.CAMERA !in s)
        assertTrue(SensorChannel.MICROPHONE in s)
        assertTrue(SensorChannel.ACCELEROMETER in s)
        assertTrue(SensorChannel.GYROSCOPE !in s)
        assertTrue(s.all { it.modality != Modality.VISION })
    }

    @Test
    fun `overnight nap wording matches the sleep rule`() {
        for (goal in listOf("overnight sleep tracking", "afternoon nap", "night rest")) {
            assertTrue(
                "\"$goal\" should suggest camera-free capture",
                SensorChannel.CAMERA !in WorkTypeSuggestions.suggest(goal),
            )
        }
    }

    @Test
    fun `mechanical work suggests the full multimodal set`() {
        val s = WorkTypeSuggestions.suggest("DC motor rig under load")
        assertTrue(SensorChannel.CAMERA in s)
        assertTrue(SensorChannel.MICROPHONE in s)
        assertTrue(SensorChannel.ACCELEROMETER in s)
        assertTrue(SensorChannel.MAGNETOMETER in s)
    }

    @Test
    fun `unknown work falls back to the default suggestion`() {
        assertEquals(
            WorkTypeSuggestions.DEFAULT_SUGGESTION,
            WorkTypeSuggestions.suggest("quantum entanglement bench"),
        )
    }

    @Test
    fun `matching is case-insensitive substring`() {
        assertEquals(
            WorkTypeSuggestions.suggest("SLEEPING"),
            WorkTypeSuggestions.suggest("sleeping"),
        )
    }

    @Test
    fun `empty goal still suggests a usable set`() {
        assertTrue(WorkTypeSuggestions.suggest("").isNotEmpty())
    }

    // --- work-key normalization ---------------------------------------------

    @Test
    fun `word order does not change the key`() {
        assertEquals(WorkKey.normalize("motor rig test"), WorkKey.normalize("rig test motor"))
    }

    @Test
    fun `short filler words are dropped`() {
        // "on"/"my" (≤2 chars) vanish; content words do not.
        assertEquals(WorkKey.normalize("sleep"), WorkKey.normalize("sleep on my"))
        assertEquals(WorkKey.normalize("motor rig"), WorkKey.normalize("my motor rig on"))
    }

    @Test
    fun `empty or punctuation-only goals share one key`() {
        assertEquals("general", WorkKey.normalize(""))
        assertEquals(WorkKey.normalize("..."), WorkKey.normalize(""))
    }
}
