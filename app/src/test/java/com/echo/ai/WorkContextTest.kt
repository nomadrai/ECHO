package com.echo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Work-context classification drives how the model calibrates "normal": the
 * same vibration burst is the machine running in mechanical work and a
 * possible anomaly in a quiet chemistry lab. These tests pin the mapping.
 */
class WorkContextTest {

    @Test
    fun `mechanical keywords classify as mechanical`() {
        val profile = WorkContext.classify("Testing a small DC motor rig under load")
        assertEquals(WorkContext.Domain.MECHANICAL, profile.domain)
        assertTrue(profile.expectedNormal.any { it.contains("VIBRATION_BURST") })
    }

    @Test
    fun `mechanical guidance says vibration is running-not-anomaly`() {
        val profile = WorkContext.classify("bearing wear on the lathe")
        assertTrue(profile.guidance.contains("RUNNING"))
    }

    @Test
    fun `electronics keywords classify as electronics`() {
        val profile = WorkContext.classify("Soldering a PCB for the sensor board")
        assertEquals(WorkContext.Domain.ELECTRONICS, profile.domain)
    }

    @Test
    fun `chemistry keywords classify as chemistry`() {
        val profile = WorkContext.classify("Titration of the acid solution")
        assertEquals(WorkContext.Domain.CHEMISTRY, profile.domain)
    }

    @Test
    fun `thermal keywords classify as thermal`() {
        val profile = WorkContext.classify("Heating the sample in the furnace")
        assertEquals(WorkContext.Domain.THERMAL, profile.domain)
    }

    @Test
    fun `acoustic keywords classify as acoustic`() {
        val profile = WorkContext.classify("Speaker frequency response test")
        assertEquals(WorkContext.Domain.ACOUSTIC, profile.domain)
    }

    @Test
    fun `construction keywords classify as construction`() {
        val profile = WorkContext.classify("Load test on the concrete beam")
        assertEquals(WorkContext.Domain.CONSTRUCTION, profile.domain)
    }

    @Test
    fun `blank or unrelated goal falls back to general`() {
        assertEquals(
            WorkContext.Domain.GENERAL,
            WorkContext.classify("").domain,
        )
        assertEquals(
            WorkContext.Domain.GENERAL,
            WorkContext.classify("Watching my plant grow").domain,
        )
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(
            WorkContext.Domain.MECHANICAL,
            WorkContext.classify("MOTOR RIG FAILURE DEMO").domain,
        )
    }

    @Test
    fun `general profile still gives guidance`() {
        val profile = WorkContext.classify("Watching my plant grow")
        assertTrue(profile.guidance.isNotBlank())
        assertFalse(profile.expectedNormal.isEmpty())
    }
}
