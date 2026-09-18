package com.echo.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.echo.capture.SessionBusState
import com.echo.capture.SessionPhase
import com.echo.capture.SessionService
import com.echo.core.model.Event
import com.echo.core.model.EventTier
import com.echo.core.time.SessionClock
import java.util.Locale

private val WarningAmber = Color(0xFFFFC857)

@Composable
fun DashboardRoute(
    onBack: () -> Unit,
    onHistory: () -> Unit = {},
    onApiSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by SessionService.bus.state.collectAsState()
    DashboardScreen(
        state = state,
        onStart = {
            val intent = Intent(context, SessionService::class.java)
                .setAction(SessionService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        },
        onStop = {
            val intent = Intent(context, SessionService::class.java)
                .setAction(SessionService.ACTION_STOP)
            context.startService(intent)
        },
        onBack = onBack,
        onHistory = onHistory,
        onApiSettings = onApiSettings,
    )
}

@Composable
fun DashboardScreen(
    state: SessionBusState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onHistory: () -> Unit = {},
    onApiSettings: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Pre-flight") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onHistory) { Text("Previous sessions") }
            Spacer(Modifier.width(4.dp))
            PhaseChip(state.phase)
        }

        Text(
            text = "ECHO",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Live dashboard — every number below is real capture",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = SessionClock.formatOffset(state.sessionMs),
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "session clock",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        val busy = state.phase == SessionPhase.PREPARING || state.phase == SessionPhase.STOPPING
        Button(
            onClick = if (state.phase == SessionPhase.RECORDING) onStop else onStart,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = if (state.phase == SessionPhase.RECORDING) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                when (state.phase) {
                    SessionPhase.RECORDING -> "END SESSION"
                    SessionPhase.STOPPING -> "Stopping…"
                    SessionPhase.PREPARING -> "Preparing…"
                    else -> "START SESSION"
                },
                fontWeight = FontWeight.Bold,
            )
        }

        if (state.health.degraded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Degraded mode: " + listOfNotNull(
                    state.health.cameraError?.let { "camera ($it)" },
                    state.health.audioError?.let { "audio ($it)" },
                    state.health.sensorError?.let { "sensors ($it)" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = WarningAmber,
            )
        }

        Spacer(Modifier.height(16.dp))

        RatesCard(state)

        Spacer(Modifier.height(12.dp))

        AudioCard(state)
        Spacer(Modifier.height(12.dp))
        VisionCard(state)
        Spacer(Modifier.height(12.dp))
        MotionCard(state)
        Spacer(Modifier.height(12.dp))
        EnvironmentCard(state)

        Spacer(Modifier.height(16.dp))

        if (state.phase == SessionPhase.STOPPED && state.eventCount > 0) {
            SessionEndedCard(state)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onHistory,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Investigate this session →")
            }
            Spacer(Modifier.height(16.dp))
        }

        EventFeedCard(state)

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onApiSettings) {
            Text("AI provider settings (API key) →")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PhaseChip(phase: SessionPhase) {
    val (label, color) = when (phase) {
        SessionPhase.RECORDING -> "LIVE" to MaterialTheme.colorScheme.secondary
        SessionPhase.PREPARING, SessionPhase.STOPPING -> "…" to WarningAmber
        SessionPhase.STOPPED -> "ENDED" to MaterialTheme.colorScheme.onSurfaceVariant
        SessionPhase.IDLE -> "IDLE" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
private fun RatesCard(state: SessionBusState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            SectionLabel("MEASURED RATES")
            Spacer(Modifier.height(6.dp))
            KeyValue("Camera frames/s", fmt(state.rates.framesPerSecond, 1))
            KeyValue("Audio hops/s", fmt(state.rates.audioHopsPerSecond, 1))
            KeyValue("Motion Hz", fmt(state.rates.sensorHz, 1))
            KeyValue("Environment Hz", fmt(state.rates.environmentHz, 1))
        }
    }
}

@Composable
private fun AudioCard(state: SessionBusState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.health.audioRunning, state.health.audioError)
                Spacer(Modifier.width(8.dp))
                SectionLabel("AUDIO")
            }
            Spacer(Modifier.height(6.dp))
            MeterBar(value = state.audio.rms, max = 0.5, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            KeyValue("RMS", fmt(state.audio.rms, 3))
            KeyValue("Baseline RMS", fmt(state.audio.baselineRms, 3))
            KeyValue("Zero-cross rate", fmt(state.audio.zcr, 3))
            KeyValue("Bands low/high", "${fmt(state.audio.lowBand, 3)} / ${fmt(state.audio.highBand, 3)}")
            val labels = state.audio.classifyLabels
            when {
                !state.audio.classifierReady -> Text(
                    text = "YAMNet unavailable — energy-only mode" +
                        (state.audio.classifierError?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                )
                labels.isEmpty() -> Text(
                    text = "Classifier idle (energy gate closed — quiet room)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.take(3).forEach { (label, score) ->
                        Text(
                            text =                            "$label ${fmt(score * 100.0, 0)}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisionCard(state: SessionBusState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.health.cameraRunning, state.health.cameraError)
                Spacer(Modifier.width(8.dp))
                SectionLabel("VISION")
            }
            Spacer(Modifier.height(6.dp))
            MeterBar(value = state.vision.changedFraction, max = 0.3, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            KeyValue("Changed pixels", "${fmt(state.vision.changedFraction * 100, 1)} %")
            KeyValue("Mean |Δ| luma", fmt(state.vision.meanAbsDiff, 2))
            KeyValue("Mean luma", fmt(state.vision.meanLuma, 1))
            val resolution = if (state.vision.width == 0) {
                "waiting for first frame"
            } else {
                "${state.vision.width}×${state.vision.height}"
            }
            KeyValue("Analysis resolution", resolution)
        }
    }
}

@Composable
private fun MotionCard(state: SessionBusState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.health.sensorRunning, state.health.sensorError)
                Spacer(Modifier.width(8.dp))
                SectionLabel("MOTION")
            }
            Spacer(Modifier.height(6.dp))
            MeterBar(value = state.motion.accelMagnitude, max = 30.0, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            KeyValue("|a| magnitude", fmt(state.motion.accelMagnitude, 2))
            KeyValue("Baseline |a|", fmt(state.motion.baselineAccel, 2))
            KeyValue("|ω| angular", if (state.motion.gyroAvailable) fmt(state.motion.gyroMagnitude, 2) + " rad/s" else "no gyroscope")
            KeyValue("Linear |a|", if (state.motion.linearAccelAvailable) fmt(state.motion.linearAccelMagnitude, 2) + " m/s²" else "not available")
            KeyValue("Tilt offset", if (state.motion.tiltAvailable) fmt(state.motion.tiltAngleDeg, 1) + "°" else "not available")
        }
    }
}

@Composable
private fun EnvironmentCard(state: SessionBusState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(running = true, error = null)
                Spacer(Modifier.width(8.dp))
                SectionLabel("ENVIRONMENT")
            }
            Spacer(Modifier.height(6.dp))
            val env = state.environment
            if (!env.magnetometerAvailable && !env.barometerAvailable &&
                !env.lightSensorAvailable && !env.proximityAvailable &&
                !env.stepCounterAvailable && !env.temperatureAvailable && !env.humidityAvailable
            ) {
                Text(
                    text = "No environment sensors present on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (env.magnetometerAvailable) KeyValue("|B| magnetic", fmt(env.magneticFieldUt, 1) + " µT")
                if (env.barometerAvailable) KeyValue("Pressure", fmt(env.pressureHpa, 2) + " hPa")
                if (env.lightSensorAvailable) KeyValue("Ambient light", fmt(env.lightLux, 1) + " lx")
                if (env.proximityAvailable) {
                    KeyValue(
                        "Proximity",
                        if (env.proximityCovered) "COVERED" else fmt(env.proximityCm, 1) + " cm",
                    )
                }
                if (env.stepCounterAvailable && env.stepCount >= 0) KeyValue("Steps", env.stepCount.toString())
                if (env.temperatureAvailable) KeyValue("Ambient temp", fmt(env.temperatureC, 1) + " °C")
                if (env.humidityAvailable) KeyValue("Humidity", fmt(env.humidityPct, 1) + " %")
            }
        }
    }
}

@Composable
private fun SessionEndedCard(state: SessionBusState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            SectionLabel("SESSION ENDED")
            Spacer(Modifier.height(6.dp))
            KeyValue("Duration", SessionClock.formatOffset(state.sessionMs))
            KeyValue("Events detected", state.eventCount.toString())
            val perType = state.events
                .groupingBy { it.type }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .joinToString(" · ") { "${it.key}×${it.value}" }
            if (perType.isNotEmpty()) {
                KeyValue("By type", perType)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "All $state.eventCount events are saved to the session " +
                    "database (files/echo/echo.db) — the timeline survives restarts " +
                    "and feeds the AI investigator.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventFeedCard(state: SessionBusState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            SectionLabel("EVENT FEED (${state.eventCount})")
            Spacer(Modifier.height(6.dp))
            if (state.events.isEmpty()) {
                Text(
                    text = "No events yet. Tap, knock or drop something near the phone " +
                        "while recording — impacts show up here within a second.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(state.events.reversed()) { event ->
                        EventRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: Event) {
    val tierColor = when (event.tier) {
        EventTier.CONFIRMED -> MaterialTheme.colorScheme.secondary
        EventTier.PROBABLE -> WarningAmber
        EventTier.UNCONFIRMED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "E${event.id} ${SessionClock.formatOffset(event.tStartMs)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = event.type,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${event.tier} ${fmt(event.confidence, 2)}",
                style = MaterialTheme.typography.labelSmall,
                color = tierColor,
            )
        }
        Text(
            text = event.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(running: Boolean, error: String?) {
    val color = when {
        error != null -> MaterialTheme.colorScheme.error
        running -> MaterialTheme.colorScheme.secondary
        else -> WarningAmber
    }
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun MeterBar(value: Double, max: Double, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth((value / max).toFloat().coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun fmt(v: Double, digits: Int = 2): String =
    String.format(Locale.US, "%.${digits}f", v)
