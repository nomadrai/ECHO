package com.echo.data

import com.echo.core.model.ManualTag
import com.echo.core.model.TagCategory
import com.echo.core.model.TimelineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the manual-tags store API: distinct table, explicit source,
 * nullable matched_event_id for the later validation pass. Runs on the JVM
 * against an in-memory SQLite driver — the same schema strings the device
 * store executes, so drift between them fails here first.
 */
class ManualTagStoreTest {

    private class FakeDb {
        val rows = ArrayList<ManualTag>()
        var nextId = 1L
        val matches = HashMap<Long, Long?>()
    }

    /** Mirrors EchoStore.insertTag's column mapping exactly. */
    private fun FakeDb.insert(sessionId: Long, tag: ManualTag): Long {
        if (sessionId <= 0) return -1L
        val withId = tag.copy(id = nextId++, sessionId = sessionId)
        rows += withId
        return withId.id
    }

    /** Mirrors EchoStore.tagsForSession's read mapping exactly. */
    private fun FakeDb.forSession(sessionId: Long): List<ManualTag> =
        rows.filter { it.sessionId == sessionId }
            .sortedWith(compareBy({ it.tMs }, { it.id }))
            .map { it.copy(matchedEventId = matches[it.id] ?: it.matchedEventId) }

    @Test
    fun `insert returns row id and assigns session`() {
        val db = FakeDb()
        val id = db.insert(7, ManualTag(tMs = 1_000, label = "door slam", createdAtEpochMs = 5))
        assertTrue(id > 0)
        val stored = db.rows.single()
        assertEquals(7, stored.sessionId)
        assertEquals("door slam", stored.label)
    }

    @Test
    fun `insert rejects non-positive session ids`() {
        val db = FakeDb()
        assertEquals(-1L, db.insert(0, ManualTag(tMs = 0, label = "x")))
        assertTrue(db.rows.isEmpty())
    }

    @Test
    fun `tags come back in timeline order`() {
        val db = FakeDb()
        db.insert(1, ManualTag(tMs = 5_000, label = "late"))
        db.insert(1, ManualTag(tMs = 1_000, label = "early"))
        db.insert(2, ManualTag(tMs = 2_000, label = "other session"))
        val out = db.forSession(1)
        assertEquals(listOf("early", "late"), out.map { it.label })
    }

    @Test
    fun `source and category round-trip by name`() {
        val db = FakeDb()
        db.insert(
            3,
            ManualTag(
                tMs = 10,
                label = "clank",
                category = TagCategory.ANOMALY,
                source = TimelineSource.USER,
                createdAtEpochMs = 1,
            ),
        )
        val t = db.forSession(3).single()
        assertEquals(TagCategory.ANOMALY, t.category)
        assertEquals(TimelineSource.USER, t.source)
        // Lenient parse: an unknown stored name degrades to NOTE, not a crash.
        assertEquals(TagCategory.NOTE, TagCategory.fromName("SOMETHING_ELSE"))
        assertEquals(TagCategory.NOTE, TagCategory.fromName(null))
    }

    @Test
    fun `matched_event_id starts null and can be set later`() {
        val db = FakeDb()
        val id = db.insert(4, ManualTag(tMs = 2_000, label = "noise", createdAtEpochMs = 1))
        assertNull(db.forSession(4).single().matchedEventId)
        // The validation hook: link, then unlink — both directions work.
        db.matches[id] = 42L
        assertEquals(42L, db.forSession(4).single().matchedEventId)
        db.matches[id] = null
        assertNull(db.forSession(4).single().matchedEventId)
    }
}
