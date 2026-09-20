package com.echo.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.echo.data.EchoStore
import com.echo.data.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session history: sealed sessions from the timeline DB, newest first.
 * Reloaded on every entry to the screen so a just-ended session appears
 * without any manual refresh.
 *
 * Selection for the trends comparison set lives here too: checkbox state,
 * plus the quick filters ("last 5", "last 30 days") that fill it in one tap.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val store = EchoStore.get(application)

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    /** Explicitly checked session ids — the comparison set when non-empty. */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    fun refresh() {
        _sessions.value = store.listSessions()
    }

    fun toggle(sessionId: Long) {
        _selected.value = _selected.value.let { if (sessionId in it) it - sessionId else it + sessionId }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** Pre-fills the selection with the [n] most recent sessions. */
    fun selectLastN(n: Int) {
        _selected.value = _sessions.value.take(n).map { it.id }.toSet()
    }

    /** Pre-fills the selection with everything in the last [days] days. */
    fun selectLastDays(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        _selected.value = _sessions.value
            .filter { it.startedAtEpochMs >= cutoff }
            .map { it.id }.toSet()
    }
}
