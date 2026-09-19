package com.echo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.echo.core.model.Event
import com.echo.core.model.EventRelation
import com.echo.core.model.EventTier
import com.echo.core.model.Modality
import com.echo.core.model.RelationKind
import java.io.File

/**
 * Session persistence: a hand-written SQLite store (plan §5 — no Room, no
 * annotation processors) living in the dedicated app-private folder
 * `filesDir/echo/echo.db`.
 *
 * Storage-efficiency contract: every row is fixed-column — integers for time
 * and scores, a 4-bit bitmask for modalities, a 0–2 int for tier — and no
 * per-row JSON. An event is ~100 bytes vs ~600 as a JSON line, and the
 * timeline stays queryable (SQL) for the M3 investigator digest.
 *
 * The extractor's runtime event ids reset each session, so the primary key is
 * an autoincrement row id and the runtime id is kept in its own column.
 * All access is serialized (events arrive from several capture threads).
 */
class EchoStore private constructor(private val dbFile: File) {

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

    init {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "started_at_epoch_ms INTEGER NOT NULL," +
                "ended_at_epoch_ms INTEGER," +
                "duration_ms INTEGER," +
                "device_meta TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id INTEGER NOT NULL," +
                "runtime_id INTEGER NOT NULL," +
                "t_start_ms INTEGER NOT NULL," +
                "t_end_ms INTEGER NOT NULL," +
                "type TEXT NOT NULL," +
                "modality_mask INTEGER NOT NULL," +
                "tier INTEGER NOT NULL," +
                "confidence REAL NOT NULL," +
                "salience REAL NOT NULL," +
                "description TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_events_session ON events(session_id, t_start_ms)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS event_relations (" +
                "session_id INTEGER NOT NULL," +
                "from_runtime_id INTEGER NOT NULL," +
                "to_runtime_id INTEGER NOT NULL," +
                "kind INTEGER NOT NULL," +
                "delta_ms INTEGER NOT NULL," +
                "window_ms INTEGER NOT NULL," +
                "confidence REAL NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_relations_session ON event_relations(session_id)",
        )
        // Migration for stores created before the session-goal column existed.
        // CREATE TABLE IF NOT EXISTS never alters an existing table, so the
        // ALTER (duplicate-column error swallowed) is the upgrade path.
        runCatching {
            db.execSQL("ALTER TABLE sessions ADD COLUMN intent TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * Opens a session row; returns the persisted session id. [goal] is what
     * the user said they were building — the investigator's context for
     * judging what the detected events mean.
     */
    @Synchronized
    fun createSession(startedAtEpochMs: Long, deviceMeta: String, goal: String): Long {
        val values = ContentValues().apply {
            put("started_at_epoch_ms", startedAtEpochMs)
            put("device_meta", deviceMeta)
            put("intent", goal)
        }
        return db.insert(TABLE_SESSIONS, null, values)
    }

    /** Persists one event; returns the row id (or -1 on failure, logged). */
    @Synchronized
    fun insertEvent(sessionId: Long, event: Event): Long {
        if (sessionId <= 0) return -1L
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("runtime_id", event.id)
            put("t_start_ms", event.tStartMs)
            put("t_end_ms", event.tEndMs)
            put("type", event.type)
            put("modality_mask", modalityMask(event.modalities))
            put("tier", event.tier.ordinal)
            put("confidence", event.confidence)
            put("salience", event.salience)
            put("description", event.description)
        }
        return runCatching { db.insert(TABLE_EVENTS, null, values) }
            .onFailure { Log.e(TAG, "event insert failed: ${it.message}") }
            .getOrDefault(-1L)
    }

    /** Persists one correlation edge; edges reference events by runtime id. */
    @Synchronized
    fun insertRelation(sessionId: Long, relation: EventRelation) {
        if (sessionId <= 0) return
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("from_runtime_id", relation.fromEventId)
            put("to_runtime_id", relation.toEventId)
            put("kind", relation.kind.ordinal)
            put("delta_ms", relation.deltaMs)
            put("window_ms", relation.windowMs)
            put("confidence", relation.confidence)
        }
        runCatching { db.insert("event_relations", null, values) }
            .onFailure { Log.e(TAG, "relation insert failed: ${it.message}") }
    }

    /** Seals the session: end time, duration. */
    @Synchronized
    fun endSession(sessionId: Long, endedAtEpochMs: Long, durationMs: Long) {
        if (sessionId <= 0) return
        val values = ContentValues().apply {
            put("ended_at_epoch_ms", endedAtEpochMs)
            put("duration_ms", durationMs)
        }
        db.update(TABLE_SESSIONS, values, "id = ?", arrayOf(sessionId.toString()))
    }

    /**
     * All sealed sessions, newest first. Counts and event-type summaries come
     * from one aggregate query while the connection is already held — cheap
     * and keeps the history screen to a single store touch.
     */
    @Synchronized
    fun listSessions(): List<SessionRecord> {
        val counts = HashMap<Long, Int>()
        val types = HashMap<Long, String>()
        db.rawQuery(
            "SELECT session_id, COUNT(*), GROUP_CONCAT(DISTINCT type) FROM events " +
                "GROUP BY session_id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                counts[c.getLong(0)] = c.getInt(1)
                types[c.getLong(0)] = c.getString(2) ?: ""
            }
        }
        val out = ArrayList<SessionRecord>()
        db.rawQuery(
            "SELECT id, started_at_epoch_ms, ended_at_epoch_ms, duration_ms, device_meta, intent " +
                "FROM sessions WHERE ended_at_epoch_ms IS NOT NULL ORDER BY id DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += SessionRecord(
                    id = c.getLong(0),
                    startedAtEpochMs = c.getLong(1),
                    endedAtEpochMs = c.getLong(2),
                    durationMs = c.getLong(3),
                    deviceMeta = c.getString(4) ?: "",
                    eventCount = counts[c.getLong(0)] ?: 0,
                    eventTypes = types[c.getLong(0)] ?: "",
                    goal = c.getString(5) ?: "",
                )
            }
        }
        return out
    }

    /** Rebuilds events for a session in timeline order (M3 digest input). */
    @Synchronized
    fun eventsForSession(sessionId: Long): List<Event> {
        if (sessionId <= 0) return emptyList()
        val out = ArrayList<Event>()
        db.rawQuery(
            "SELECT runtime_id, t_start_ms, t_end_ms, type, modality_mask, tier, " +
                "confidence, salience, description FROM events " +
                "WHERE session_id = ? ORDER BY t_start_ms, id",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += Event(
                    id = cursor.getLong(0),
                    sessionId = sessionId,
                    tStartMs = cursor.getLong(1),
                    tEndMs = cursor.getLong(2),
                    type = cursor.getString(3),
                    modalities = modalitiesFromMask(cursor.getLong(4)),
                    tier = EventTier.entries[cursor.getInt(5).coerceIn(0, EventTier.entries.size - 1)],
                    confidence = cursor.getDouble(6),
                    salience = cursor.getDouble(7),
                    description = cursor.getString(8),
                )
            }
        }
        return out
    }

    /** Relation edges for a session, joined to event types for readability. */
    @Synchronized
    fun relationsForSession(sessionId: Long): List<EventRelation> {
        if (sessionId <= 0) return emptyList()
        val out = ArrayList<EventRelation>()
        db.rawQuery(
            "SELECT from_runtime_id, to_runtime_id, kind, delta_ms, window_ms, confidence " +
                "FROM event_relations WHERE session_id = ?",
            arrayOf(sessionId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val kind = RelationKind.entries[c.getInt(2).coerceIn(0, RelationKind.entries.size - 1)]
                out += EventRelation(
                    fromEventId = c.getLong(0),
                    toEventId = c.getLong(1),
                    kind = kind,
                    deltaMs = c.getLong(3),
                    windowMs = c.getLong(4),
                    confidence = c.getDouble(5),
                )
            }
        }
        return out
    }

    /** Persists an edited session goal and re-seals nothing else. */
    @Synchronized
    fun updateGoal(sessionId: Long, goal: String) {
        if (sessionId <= 0) return
        db.update(
            TABLE_SESSIONS,
            ContentValues().apply { put("intent", goal) },
            "id = ?",
            arrayOf(sessionId.toString()),
        )
    }

    /** Current on-disk size of the store, for the dashboard storage meter. */
    @Synchronized
    fun databaseBytes(): Long = dbFile.length()

    companion object {
        private const val TAG = "EchoStore"
        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_EVENTS = "events"

        @Volatile
        private var instance: EchoStore? = null

        /** The dedicated ECHO data folder, app-private, wiped on uninstall. */
        fun folder(context: Context): File =
            File(context.filesDir, "echo").apply { mkdirs() }

        fun get(context: Context): EchoStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val file = File(folder(context), "echo.db")
                return EchoStore(file).also { instance = it }
            }
        }

        /** AUDIO=1, VISION=2, MOTION=4, ENVIRONMENT=8. */
        fun modalityMask(modalities: Set<Modality>): Int = ModalityCodec.mask(modalities)

        fun modalitiesFromMask(mask: Long): Set<Modality> = ModalityCodec.fromMask(mask)
    }
}

/** One row of the session-history list. */
data class SessionRecord(
    val id: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMs: Long,
    val deviceMeta: String,
    val eventCount: Int,
    val eventTypes: String,
    /** What the user said they were building; empty if skipped. */
    val goal: String = "",
)

/**
 * Bitmask codec for [Modality] — its own object so the storage format is
 * unit-testable on the JVM without touching any Android class.
 */
object ModalityCodec {

    /** AUDIO=1, VISION=2, MOTION=4, ENVIRONMENT=8. */
    fun mask(modalities: Set<Modality>): Int =
        modalities.fold(0) { acc, m -> acc or (1 shl m.ordinal) }

    fun fromMask(mask: Long): Set<Modality> =
        Modality.entries.filter { mask and (1L shl it.ordinal) != 0L }.toSet()
}
