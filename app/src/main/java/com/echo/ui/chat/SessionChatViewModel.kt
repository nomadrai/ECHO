package com.echo.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echo.ai.ApiSettingsStore
import com.echo.ai.DigestBuilder
import com.echo.ai.ExternalAiClient
import com.echo.core.model.Event
import com.echo.core.model.ManualTag
import com.echo.core.model.TagCategory
import com.echo.data.EchoStore
import com.echo.ui.tagging.saveTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val question: String,
    val answer: String? = null,
    val error: String? = null,
)

data class SessionChatState(
    val sessionId: Long = 0,
    val sessionLabel: String = "",
    val digest: String = "",
    val eventCount: Int = 0,
    /** The session's events in timeline order — drives the review scrubber. */
    val events: List<Event> = emptyList(),
    /** Manual tags for this session, timeline order (review + retro tag). */
    val tags: List<ManualTag> = emptyList(),
    /** What the user said they were building, shown above the chat. */
    val goal: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val providerLabel: String = "",
    val apiKeyMissing: Boolean = false,
)

/**
 * Investigator chat over one sealed session. The digest is built once from the
 * persisted events (deterministic, local); every question is answered by the
 * user-configured external provider over that digest — the plan's local-first
 * reduction step happens in [DigestBuilder], so only the compact event list
 * ever leaves the device, never raw sensor data.
 */
class SessionChatViewModel(application: Application) : AndroidViewModel(application) {

    private val store = EchoStore.get(application)
    private val apiSettings = ApiSettingsStore(application)
    private val client = ExternalAiClient(apiSettings)

    private val _state = MutableStateFlow(SessionChatState())
    val state: StateFlow<SessionChatState> = _state.asStateFlow()

    fun load(sessionId: Long) {
        if (_state.value.sessionId == sessionId && _state.value.digest.isNotEmpty()) return
        val record = store.listSessions().firstOrNull { it.id == sessionId }
        val events: List<Event> = store.eventsForSession(sessionId)
        // M1 correlation edges persisted at session end — the digest renders
        // them so the model cites measured relations instead of guessing.
        val relations = store.relationsForSession(sessionId)
        // Manual tags ride in the same digest (own section, T# citations) —
        // the model sees what the user marked, never raw sensor data.
        val tags: List<ManualTag> = store.tagsForSession(sessionId)
        val label = sessionLabel(sessionId)
        val digest = DigestBuilder.build(
            events = events,
            sessionLabel = label,
            durationMs = record?.durationMs ?: 0L,
            goal = record?.goal ?: "",
            relations = relations,
            tags = tags,
        )
        _state.value = SessionChatState(
            sessionId = sessionId,
            sessionLabel = label,
            digest = digest,
            eventCount = events.size,
            events = events,
            tags = tags,
            goal = record?.goal ?: "",
            providerLabel = providerLabel(),
            apiKeyMissing = !apiSettings.isConfigured(),
        )
    }

    /**
     * Retro-tagging: stamps the moment the user picked on the timeline and
     * persists it, then reloads so the digest (and the tag list) include it
     * for the next question.
     */
    fun addRetroactiveTag(tMs: Long, label: String, category: TagCategory) {
        val sessionId = _state.value.sessionId
        if (sessionId <= 0) return
        viewModelScope.launch {
            saveTag(
                store = store,
                sessionId = sessionId,
                tMs = tMs,
                label = label,
                category = category,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            load(sessionId)
        }
    }

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        if (!apiSettings.isConfigured()) {
            _state.value = _state.value.copy(apiKeyMissing = true)
            return
        }
        val current = _state.value
        val history = current.messages
            .filter { it.answer != null }
            .map { it.question to it.answer!! }

        _state.value = current.copy(
            busy = true,
            messages = current.messages + ChatMessage(question = trimmed),
        )
        viewModelScope.launch {
            val answer = runCatching { client.ask(current.digest, trimmed, history) }
                .onFailure {
                    _state.value = _state.value.copy(
                        busy = false,
                        messages = _state.value.messages.mapIndexed { i, m ->
                            if (i == _state.value.messages.lastIndex) {
                                m.copy(error = it.message ?: "request failed")
                            } else {
                                m
                            }
                        },
                    )
                }
                .onSuccess { reply ->
                    _state.value = _state.value.copy(
                        busy = false,
                        messages = _state.value.messages.mapIndexed { i, m ->
                            if (i == _state.value.messages.lastIndex) m.copy(answer = reply) else m
                        },
                    )
                }
        }
    }

    fun providerLabel(): String {
        val p = apiSettings.provider
        return "${p.displayName} · ${apiSettings.modelFor(p)}"
    }

    /** Persists an edited goal and rebuilds the digest with it. */
    fun updateGoal(goal: String) {
        val current = _state.value
        if (current.sessionId <= 0) return
        store.updateGoal(current.sessionId, goal.trim())
        load(current.sessionId)
    }

    private fun sessionDuration(sessionId: Long): Long =
        store.listSessions().firstOrNull { it.id == sessionId }?.durationMs ?: 0L

    private fun sessionLabel(sessionId: Long): String {
        val record = store.listSessions().firstOrNull { it.id == sessionId } ?: return "Session #$sessionId"
        val time = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(record.startedAtEpochMs))
        return "Session #$sessionId · $time · ${record.eventCount} events"
    }
}
