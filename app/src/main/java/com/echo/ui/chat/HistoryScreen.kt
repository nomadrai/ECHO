package com.echo.ui.chat

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * Previous sessions: every sealed session in the timeline DB. Tapping a row
 * opens the investigator chat over that session's real events.
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    backLabel: String = "← Dashboard",
    viewModel: HistoryViewModel = viewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()

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
            text = "Tap a session to investigate it with AI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

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
                    SessionRow(record = record, onClick = { onOpenSession(record.id) })
                }
            }
        }
    }
}

@Composable
private fun SessionRow(record: SessionRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                )
            }
            Text(
                text = "${record.eventCount} events",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            if (record.eventTypes.isNotBlank()) {
                Text(
                    text = record.eventTypes.split(",").take(4).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
