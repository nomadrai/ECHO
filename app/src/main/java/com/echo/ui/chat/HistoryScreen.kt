package com.echo.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echo.data.SessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Previous sessions: every sealed session in the timeline DB. Tap a row to
 * open the single-session investigator chat — or check rows (or use a quick
 * filter) to build a comparison set and enter the cross-session trends chat.
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onCompare: (List<Long>) -> Unit = {},
    backLabel: String = "← Dashboard",
    viewModel: HistoryViewModel = viewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val selected by viewModel.selected.collectAsState()

    // Load on entry so a just-ended session appears without a manual refresh.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(backLabel) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::refresh) { Text("Refresh") }
        }

        Text(
            text = "Previous sessions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Tap a session to investigate it with AI — or check several to compare",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Quick filters: one tap fills the comparison set. Sessions whose
        // work type repeats get a chip of their own (the "all sessions tagged
        // 'sleeping'" filter) — built from the same goal text the detector
        // digest uses, never a second source of truth.
        val workTypes = sessions.groupBy { it.goal.trim() }
            .filter { it.key.isNotBlank() && it.value.size > 1 }
            .keys
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = false,
                onClick = { viewModel.selectLastN(5) },
                label = { Text("Last 5") },
            )
            FilterChip(
                selected = false,
                onClick = { viewModel.selectLastDays(30) },
                label = { Text("Last 30 days") },
            )
            workTypes.take(4).forEach { goal ->
                FilterChip(
                    selected = false,
                    onClick = {
                        viewModel.clearSelection()
                        sessions.filter { it.goal.trim() == goal }
                            .forEach { viewModel.toggle(it.id) }
                    },
                    label = { Text(goal, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            if (selected.isNotEmpty()) {
                FilterChip(
                    selected = true,
                    onClick = { viewModel.clearSelection() },
                    label = { Text("Clear ${selected.size} ✓") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // The compare entry point appears the moment a set exists — checkbox
        // or filter — with the count on the button.
        if (selected.isNotEmpty()) {
            Button(
                onClick = { onCompare(sessions.filter { it.id in selected }.sortedBy { it.id }.map { it.id }) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("COMPARE ${selected.size} SESSIONS →", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (sessions.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("No sealed sessions yet.")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Run a session on the dashboard — when you press END " +
                            "SESSION it is saved to the timeline database and appears here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Plain Column, not LazyColumn: this screen already lives inside a
            // verticalScroll Column and nesting scrollables crashes Compose.
            // Session histories are small (tens), so laziness buys nothing.
            Column {
                for (record in sessions) {
                    SessionRow(
                        record = record,
                        checked = record.id in selected,
                        onToggle = { viewModel.toggle(record.id) },
                        onClick = { onOpenSession(record.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    record: SessionRecord,
    checked: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = if (checked) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(start = 0.dp, top = 14.dp, end = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Selection checkbox: the comparison set for trends. The row
                // itself keeps opening the single-session chat on tap.
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Session #${record.id}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = SimpleDateFormat("MMM d, HH:mm", Locale.US)
                        .format(Date(record.startedAtEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatDuration(record.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (record.goal.isNotBlank()) {
                Text(
                    text = "Goal: ${record.goal}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 56.dp),
                )
            }
            Text(
                text = "${record.eventCount} events",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 56.dp),
            )
            if (record.eventTypes.isNotBlank()) {
                Text(
                    text = record.eventTypes.split(",").take(4).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp),
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(Locale.US, m, s)
}
