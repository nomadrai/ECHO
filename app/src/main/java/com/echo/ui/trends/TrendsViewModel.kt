package com.echo.ui.trends

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echo.ai.ApiSettingsStore
import com.echo.ai.ExternalAiClient
import com.echo.ai.TrendsAggregator
import com.echo.ai.WorkContext
import com.echo.data.EchoStore
import com.echo.data.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrendsChatMessage(
    val question: String,
    val answer: String? = null,
    val error: String? = null,
)

/**
 * One plotted point: the metric per session, chronological. The chart is
 * derived — never stored — from the same summaries the AI context uses.
 */
data class TrendPoint(
    val sessionId: Long,
    val label: String,
    val eventsPerHour: Double,
    val eventCount: Int,
)

data class TrendsState(
    /** Sessions actually compared, chronological. */
    val sessions: List<SessionRecord> = emptyList(),
    /** Same-work-type groups inside the set (domain display name → ids). */
    val groups: Map<String, List<Long>> = emptyMap(),
    /** Group currently in context; empty list = all groups (cross-type). */
    val activeGroupIds: List<Long> = emptyList(),
    val crossType: Boolean = false,
    val stats: TrendsAggregator.CrossStats? = null,
    val context: String = "",
    val chart: List<TrendPoint> = emptyList(),
    val messages: List<TrendsChatMessage> = emptyList(),
    val busy: Boolean = false,
    val providerLabel: String = "",
    val apiKeyMissing: Boolean = false,
)

/**
 * Cross-session trends conversation. The context is built ONCE per selection
 * from persisted timeline rows via [TrendsAggregator] (local-first reduction,
 * same discipline as the single-session digest): deterministic stats + compact
 * per-session summaries — never raw sensor data. Same-work-type grouping is
 * the default; the user can lift it deliberately.
 */
class TrendsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = EchoStore.get(application)
    private val apiSettings = ApiSettingsStore(application)
    private val client = ExternalAiClient(apiSettings)

    private val _state = MutableStateFlow(TrendsState())
    val state: StateFlow<TrendsState> = _state.asStateFlow()

    fun load(sessionIds: List<Long>) {
        if (sessionIds.isEmpty()) return
        if (_state.value.sessions.map { it.id }.sorted() == sessionIds.sorted()) return
        val records = store.listSessions().filter { it.id in sessionIds.toSet() }
        if (records.isEmpty()) return

        // Same-work-type groups by the goal-derived domain (WorkContext) —
        // the same classifier the digest uses, so grouping matches what the
        // model reads in each session's block.
        val groups = records.groupBy { WorkContext.classify(it.goal).domain.displayName }
            .mapValues { entry -> entry.value.map { it.id }.sorted() }

        applySelection(records, groups, activeGroupIds = emptyList(), crossType = false)
    }

    /** Deliberate override: compare everything, cross-type. */
    fun compareAll() {
        val current = _state.value
        applySelection(current.sessions, current.groups, current.sessions.map { it.id }, crossType = true)
    }

    /** Narrow back to one work-type group after a cross-type comparison. */
    fun focusGroup(groupName: String) {
        val current = _state.value
        val ids = current.groups[groupName].orEmpty()
        if (ids.isEmpty()) return
        applySelection(current.sessions, current.groups, ids, crossType = false)
    }

    private fun applySelection(
        allRecords: List<SessionRecord>,
        groups: Map<String, List<Long>>,
        activeGroupIds: List<Long>,
        crossType: Boolean,
    ) {
        val selectedIds = if (crossType) allRecords.map { it.id } else activeGroupIds
        val selected = allRecords.filter { it.id in selectedIds.toSet() }

        // Persistence pass: indexed aggregate queries + per-session timeline
        // pulls — no raw sensor data is ever reprocessed.
        val typeCounts = store.eventTypeCountsForSessions(selectedIds)
        val events = selectedIds.associateWith { store.eventsForSession(it) }
        val tags = selectedIds.associateWith { store.tagsForSession(it) }
        val (stats, summaries) = TrendsAggregator.build(selected, events, tags, typeCounts)

        val groupLabel = if (crossType) {
            "mixed (intentional)"
        } else {
            selected.groupBy { WorkContext.classify(it.goal).domain.displayName }
                .maxByOrNull { it.value.size }?.key ?: "General"
        }

        _state.value = TrendsState(
            sessions = allRecords.sortedBy { it.id },
            groups = groups,
            activeGroupIds = selectedIds,
            crossType = crossType,
            stats = stats,
            context = TrendsAggregator.buildContext(stats, summaries, groupLabel, crossType),
            chart = chartPoints(selected),
            providerLabel = providerLabel(),
            apiKeyMissing = !apiSettings.isConfigured(),
        )
    }

    /**
     * Key metric: events per hour of session duration (duration-normalized,
     * so a 20-minute rig session and an 8-hour sleep night are comparable on
     * one axis). Zero-duration sessions plot eventCount as-is.
     */
    private fun chartPoints(records: List<SessionRecord>): List<TrendPoint> =
        records.sortedBy { it.startedAtEpochMs }.map { rec ->
            val perHour = if (rec.durationMs > 0) {
                rec.eventCount * 3_600_000.0 / rec.durationMs
            } else {
                rec.eventCount.toDouble()
            }
            TrendPoint(
                sessionId = rec.id,
                label = "S${rec.id}",
                eventsPerHour = perHour,
                eventCount = rec.eventCount,
            )
        }

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        if (!apiSettings.isConfigured()) {
            _state.value = _state.value.copy(apiKeyMissing = true)
            return
        }
        val current = _state.value
        val history = current.messages.filter { it.answer != null }.map { it.question to it.answer!! }
        _state.value = current.copy(
            busy = true,
            messages = current.messages + TrendsChatMessage(question = trimmed),
        )
        viewModelScope.launch {
            runCatching { client.ask(current.context, trimmed, history, ExternalAiClient.Mode.TRENDS) }
                .onFailure { e -> markLast { it.copy(error = e.message ?: "request failed") } }
                .onSuccess { reply -> markLast { it.copy(answer = reply) } }
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun markLast(transform: (TrendsChatMessage) -> TrendsChatMessage) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.mapIndexed { i, m ->
                if (i == _state.value.messages.lastIndex) transform(m) else m
            },
        )
    }

    private fun providerLabel(): String {
        val p = apiSettings.provider
        return "${p.displayName} · ${apiSettings.modelFor(p)}"
    }
}
