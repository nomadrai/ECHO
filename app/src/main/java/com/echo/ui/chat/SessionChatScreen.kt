package com.echo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echo.core.time.SessionClock
import com.echo.ui.tagging.TagMomentDialog
import com.echo.ui.tagging.TimelineItem
import com.echo.ui.tagging.TagColor
import com.echo.ui.tagging.mergeTimeline

/**
 * Investigator chat for one sealed session: user questions, model answers
 * grounded in that session's persisted events. Purely reactive to
 * [SessionChatViewModel]; the model/provider is whichever the user configured
 * in API settings.
 */
@Composable
fun SessionChatScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onOpenApiSettings: () -> Unit,
    viewModel: SessionChatViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }

    // Retro-tagging: scrub the timeline, then label the moment. The stamp
    // is captured when the dialog OPENS (matching live tagging semantics).
    var tagDialogMs by remember { mutableStateOf<Long?>(null) }
    tagDialogMs?.let { stampMs ->
        TagMomentDialog(
            title = "Tag at " + SessionClock.formatOffset(stampMs),
            initialMs = stampMs,
            onDismiss = { tagDialogMs = null },
            onConfirm = { label, category ->
                tagDialogMs = null
                viewModel.addRetroactiveTag(stampMs, label, category)
            },
        )
    }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← History") }
            Spacer(Modifier.weight(1f))
            Text(
                text = state.providerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = state.sessionLabel.ifEmpty { "Loading…" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${state.eventCount} events in context",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The stated goal — the AI judges detections against this. Tappable
        // to correct it; the digest is rebuilt from the stored value.
        var editingGoal by remember(state.sessionId) { mutableStateOf(false) }
        if (editingGoal) {
            var goalInput by remember { mutableStateOf(state.goal) }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = goalInput,
                    onValueChange = { goalInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("What were you building?") },
                )
                TextButton(onClick = {
                    viewModel.updateGoal(goalInput)
                    editingGoal = false
                }) { Text("Save") }
            }
        } else if (state.goal.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Goal: ${state.goal} · tap to edit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { editingGoal = true },
            )
        } else {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "No goal recorded · tap to add what you were building",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { editingGoal = true },
            )
        }

        Spacer(Modifier.height(8.dp))

        TimelineReviewCard(
            state = state,
            onTagPoint = { ms -> tagDialogMs = ms },
        )

        Spacer(Modifier.height(8.dp))

        if (state.apiKeyMissing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "No API key configured",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Add a key for Groq, Google AI Studio or OpenRouter " +
                            "to chat about this session.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onOpenApiSettings) { Text("Open API settings") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty() && !state.apiKeyMissing) {
                item {
                    Text(
                        text = "Ask anything about this session: \"what happened?\", " +
                            "\"was anyone talking?\", \"what preceded the impact?\" — " +
                            "answers cite the recorded events by id and timestamp.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.messages) { message ->
                ChatBubble(message = message)
            }
            if (state.busy) {
                item {
                    Text(
                        text = "Thinking…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about this session…") },
                enabled = !state.busy,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.ask(input)
                    input = ""
                },
                enabled = !state.busy && input.isNotBlank(),
            ) {
                Text("Ask")
            }
        }
    }
}

/**
 * Post-session review: scrub the sealed session's timeline and add a tag
 * retroactively at any point. The merged list shows auto-detected events
 * (E#) and manual tags (T#, teal) distinctly but on one timeline.
 */
@Composable
private fun TimelineReviewCard(
    state: SessionChatState,
    onTagPoint: (Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            var scrubMs by remember(state.sessionId) {
                mutableStateOf(if (state.tags.isNotEmpty()) state.tags.first().tMs else 0L)
            }
            val duration = (state.events.maxOfOrNull { it.tEndMs } ?: 0L)
                .coerceAtLeast(state.tags.maxOfOrNull { it.tMs } ?: 0L)
                .coerceAtLeast(1_000L)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TIMELINE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${state.eventCount} events · ${state.tags.size} tags",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = SessionClock.formatOffset(scrubMs),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Slider(
                value = scrubMs.toFloat(),
                onValueChange = { scrubMs = it.toLong() },
                valueRange = 0f..duration.toFloat(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Scrub to any point",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onTagPoint(scrubMs) }) {
                    Text("Tag here")
                }
            }
            Spacer(Modifier.height(6.dp))
            // Merged timeline, chronological. Events keep their tier colour;
            // tags are teal with a T# citation — never conflated.
            val merged = remember(state.events, state.tags) {
                mergeTimeline(state.events, state.tags).takeLast(60)
            }
            if (merged.isEmpty()) {
                Text(
                    text = "Nothing on this timeline yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    merged.forEach { item ->
                        TimelineReviewRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineReviewRow(item: TimelineItem) {
    when (item) {
        is TimelineItem.TagItem -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "T${item.tag.id} ${SessionClock.formatOffset(item.tag.tMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TagColor,
                )
                Spacer(Modifier.width(8.dp))
                if (item.tag.category != com.echo.core.model.TagCategory.NOTE) {
                    Text(
                        text = item.tag.category.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TagColor,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(text = item.tag.label, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "MANUAL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TagColor,
                )
            }
        }
        is TimelineItem.EventItem -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "E${item.event.id} ${SessionClock.formatOffset(item.event.tStartMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.event.type,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "AUTO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // User bubble, right-aligned.
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(text = message.question, style = MaterialTheme.typography.bodyMedium)
            }
        }
        message.answer?.let { answer ->
            Spacer(Modifier.height(4.dp))
            // Answer bubble, left-aligned.
            Row {
                Box(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(text = answer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        message.error?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Error: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
