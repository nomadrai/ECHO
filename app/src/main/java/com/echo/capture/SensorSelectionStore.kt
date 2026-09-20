package com.echo.capture

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers the last-used sensor selection per work type. The goal text is
 * normalized to a lowercase key of its alphabetic words, so "Sleeping with TV
 * on" and "sleep" share one entry — the point is that picking the same kind
 * of work reuses your customization without reconfiguring.
 *
 * Stores, per key: the enabled channel ids (one pref per key, comma-joined)
 * and the most recent goal text per key (bounded ring, for a future picker).
 * A selection saved with no goal text (raw START path) still persists under
 * the `last` catch-all.
 */
class SensorSelectionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The selection to pre-fill for [goal]: the user's last customization for
     * this work type if one exists, else the rule-based suggestion. Returns
     * (channels, isRemembered).
     */
    fun resolve(goal: String): Pair<Set<SensorChannel>, Boolean> {
        val remembered = prefs.getStringSet(selectionKey(goal), null)
        if (remembered != null) {
            val channels = remembered.mapNotNull { SensorChannel.fromId(it) }.toSet()
            if (channels.isNotEmpty()) return channels to true
        }
        return WorkTypeSuggestions.suggest(goal) to false
    }

    /** Persist the user's choice for this work type (and as last-used). */
    fun remember(goal: String, channels: Set<SensorChannel>) {
        val ids = channels.map { it.id }.toSet()
        prefs.edit()
            .putStringSet(selectionKey(goal), ids)
            .putString(goalTextKey(goal), goal.trim())
            .putStringSet(KEY_LAST_ANY, ids)
            .apply()
    }

    /** The goal text the user typed most recently with this work-type key. */
    fun lastGoalText(goal: String): String =
        prefs.getString(goalTextKey(goal), null) ?: ""

    /** Channel set used by the most recent session, whatever its work type. */
    fun lastUsedAny(): Set<SensorChannel> =
        (prefs.getStringSet(KEY_LAST_ANY, null) ?: emptySet())
            .mapNotNull { SensorChannel.fromId(it) }.toSet()

    private fun selectionKey(goal: String): String =
        PREFIX_SEL + WorkKey.normalize(goal)

    private fun goalTextKey(goal: String): String =
        PREFIX_GOAL + WorkKey.normalize(goal)

    private companion object {
        const val PREFS_NAME = "echo_sensor_selection"
        const val PREFIX_SEL = "sel_"
        const val PREFIX_GOAL = "goal_"
        const val KEY_LAST_ANY = "last_any"
    }
}

/** Normalizes free-text goals into a stable per-work-type key. */
internal object WorkKey {
    /**
     * Lowercased alphabetic words, sorted for order independence ("motor rig
     * test" == "rig motor test"), joined. Words of ≤2 chars are dropped
     * (noise like "a", "on", "my"). Empty text → "general".
     */
    fun normalize(goal: String): String {
        val words = goal.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 2 }
            .distinct()
            .sorted()
        return if (words.isEmpty()) "general" else words.joinToString("_")
    }
}
