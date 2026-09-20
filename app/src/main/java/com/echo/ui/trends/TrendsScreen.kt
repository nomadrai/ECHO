package com.echo.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.echo.ai.TrendsAggregator

/**
 * Cross-session trends: a derived chart (events per hour, chronological) plus
 * a trends-mode chat grounded in the same pre-computed context. Deliberately
 * plain — Canvas-free bars from boxed weights, like the rest of ECHO's UI.
 */
@Composable
fun TrendsScreen(
    sessionIds: List<Long>,
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    viewModel: TrendsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(sessionIds) { viewModel.load(sessionIds) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
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
            text = "Trends across sessions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Comparing ${state.activeGroupIds.size} of ${state.sessions.size} selected sessions",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        // Same-work-type grouping is the default; lifting it is explicit.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.crossType) {
                state.groups.keys.sorted().forEach { name ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.focusGroup(name) },
                        label = { Text(name.substringBefore(" /"), maxLines = 1) },
                    )
                }
            } else if (state.groups.size > 1) {
                FilterChip(
                    selected = false,
                    onClick = { viewModel.compareAll() },
                    label = { Text("Compare all types") },
                )
            }
        }

        state.stats?.let { stats ->
            Spacer(Modifier.height(8.dp))
            StatsCard(stats)
        }

        if (state.chart.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TrendChartCard(state)
        }

        Spacer(Modifier.height(8.dp))

        if (state.apiKeyMissing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("No API key configured", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onBack) { Text("← Back") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (state.messages.isEmpty() && !state.apiKeyMissing) {
            Text(
                text = "Ask across sessions: \"how has my sleep quality changed this week?\", " +
                    "\"is this machine getting noisier over time?\", \"which sessions had the " +
                    "most anomalies?\" — answers cite sessions (S#) and events (S#E#).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        for (message in state.messages) {
            TrendsBubble(message)
            Spacer(Modifier.height(8.dp))
        }
        if (state.busy) {
            Text(
                text = "Thinking…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Compare across sessions…") },
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

/** The deterministic numbers, shown to the user exactly as sent to the model. */
@Composable
private fun StatsCard(stats: TrendsAggregator.CrossStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "CROSS-SESSION STATS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    text = "Avg events/session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%.1f".format(stats.avgEventsPerSession),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row {
                Text(
                    text = "Trend over time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${stats.trendDirection} (%.2f/h)".format(stats.eventsTrendPerHour),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (stats.mostCommonTypes.isNotEmpty()) {
                Row {
                    Text(
                        text = "Most common",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stats.mostCommonTypes.take(3)
                            .joinToString(", ") { "${it.second}x ${it.first}" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row {
                Text(
                    text = "Manual tags total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stats.totalTags.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Events-per-hour bars, chronological, one per session; tap to open it. */
@Composable
private fun TrendChartCard(state: TrendsState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "EVENTS PER HOUR (duration-normalized)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val max = state.chart.maxOf { it.eventsPerHour }.coerceAtLeast(0.001)
            state.chart.forEach { point ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = point.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(44.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((point.eventsPerHour / max).toFloat().coerceIn(0.02f, 1f))
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "%.1f".format(point.eventsPerHour),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.widthIn(min = 44.dp),
                    )
                }
            }
            Text(
                text = "Bars scale to the busiest session in the set",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrendsBubble(message: TrendsChatMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
