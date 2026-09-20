package com.echo.ui.tagging

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echo.core.model.Event
import com.echo.core.model.ManualTag
import com.echo.core.model.TagCategory
import com.echo.core.model.TimelineSource
import com.echo.core.time.SessionClock
import com.echo.data.EchoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One merged timeline row: an auto-detected event or a manual tag, ordered by
 * session-relative time. The two never collapse into one object — [source] is
 * stored on every row, so a viewer never guesses where an item came from.
 */
sealed class TimelineItem(
    /** Session-relative timestamp used for ordering. */
    val tMs: Long,
    val source: TimelineSource,
) {
    class EventItem(val event: Event) : TimelineItem(event.tStartMs, TimelineSource.AUTO_DETECTED)
    class TagItem(val tag: ManualTag) : TimelineItem(tag.tMs, TimelineSource.USER)
}

/** Stable within-session citation prefix: E for detected, T for user tags. */
val TimelineItem.citation: String
    get() = when (this) {
        is TimelineItem.EventItem -> "E${event.id}"
        is TimelineItem.TagItem -> "T${tag.id}"
    }

/**
 * Merges auto events and manual tags into one chronologically ordered timeline.
 * Ties (identical tMs) put the user's tag first — at the same instant, the
 * human's note is the more meaningful row (TimelineSource declares USER first).
 */
fun mergeTimeline(events: List<Event>, tags: List<ManualTag>): List<TimelineItem> =
    (events.map { TimelineItem.EventItem(it) } + tags.map { TimelineItem.TagItem(it) })
        .sortedWith(compareBy({ it.tMs }, { it.source }))

/**
 * Persists a manual tag off the main thread and returns the row with its
 * store id filled in (UI feeds are keyed by it). The category string is
 * parsed leniently — an unknown preset degrades to NOTE, never crashes.
 */
suspend fun saveTag(
    store: EchoStore,
    sessionId: Long,
    tMs: Long,
    label: String,
    category: TagCategory,
    createdAtEpochMs: Long,
): ManualTag? = withContext(Dispatchers.IO) {
    if (sessionId <= 0) return@withContext null
    val tag = ManualTag(
        tMs = tMs,
        label = label.trim().ifEmpty { "Tagged moment" },
        category = category,
        source = TimelineSource.USER,
        createdAtEpochMs = createdAtEpochMs,
    )
    val rowId = store.insertTag(sessionId, tag)
    if (rowId > 0) tag.copy(id = rowId, sessionId = sessionId) else null
}

/**
 * The quick-tag dialog shared by live capture and retro review: one tap on
 * "Tag this moment" pre-stamps the time and opens this — a text field plus
 * the preset chips. Confirm is enabled once there is a label or a preset, so
 * a one-tap tag ("Noise" + save) stays the fastest path.
 */
@Composable
fun TagMomentDialog(
    title: String,
    initialMs: Long,
    presets: List<TagCategory> = listOf(
        TagCategory.STARTED, TagCategory.NOISE, TagCategory.ANOMALY,
        TagCategory.WOKE_UP,
    ),
    onDismiss: () -> Unit,
    onConfirm: (label: String, category: TagCategory) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(TagCategory.NOTE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = "Marking " + SessionClock.formatOffset(initialMs),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("What's happening? (optional detail)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Quick labels",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                // Flow-style wrap without the FlowRow dependency: chunks of
                // chips per row — four presets stay a single line.
                presets.chunked(2).forEach { rowCategories ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowCategories.forEach { c ->
                            val selected = category == c
                            Text(
                                text = c.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .clickable { category = c },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label, category) },
                enabled = label.isNotBlank() || category != TagCategory.NOTE,
            ) { Text("Save tag") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Tag chip colour — teal, deliberately not any tier colour used by events. */
val TagColor: Color = Color(0xFF26A69A)
