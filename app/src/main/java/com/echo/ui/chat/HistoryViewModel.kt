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
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val store = EchoStore.get(application)

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    fun refresh() {
        _sessions.value = store.listSessions()
    }
}
