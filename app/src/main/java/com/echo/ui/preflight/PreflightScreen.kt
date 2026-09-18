package com.echo.ui.preflight

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echo.core.device.CapabilityCheck
import com.echo.core.device.CheckStatus
import com.echo.ui.theme.EchoTheme

private val WarningAmber = Color(0xFFFFC857)

@Composable
fun PreflightRoute(
    viewModel: PreflightViewModel = viewModel(),
    onContinue: () -> Unit = {},
    onHistory: () -> Unit = {},
    onApiSettings: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    PreflightScreen(
        state = state,
        onRescan = viewModel::refresh,
        onContinue = onContinue,
        onRequestPermissions = { permissionLauncher.launch(runtimePermissions()) },
        onOpenBatterySettings = {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { context.startActivity(intent) }
        },
        onHistory = onHistory,
        onApiSettings = onApiSettings,
    )
}

private fun runtimePermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

@Composable
fun PreflightScreen(
    state: PreflightState,
    onRescan: () -> Unit,
    onContinue: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onHistory: () -> Unit = {},
    onApiSettings: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "ECHO",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "On-device incident investigation",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        DeviceProfileCard(state)

        Spacer(Modifier.height(20.dp))

        Text(
            text = "PRE-FLIGHT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (!state.probed) {
            Text(
                text = "Probing hardware…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.checks.forEach { check ->
                CheckRow(check)
                Spacer(Modifier.height(2.dp))
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRequestPermissions) { Text("Grant permissions") }
            OutlinedButton(onClick = onOpenBatterySettings) { Text("Battery settings") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onRescan) { Text("Re-run checks") }
            OutlinedButton(onClick = onHistory) { Text("Previous sessions") }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onContinue,
            enabled = state.probed,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue to live dashboard →", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(28.dp))

        TextButton(onClick = onApiSettings) {
            Text("AI provider settings (API key) →")
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Next milestone: live camera, microphone and motion capture behind " +
                "START SESSION. Nothing on this screen is simulated — every line above is " +
                "read from this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceProfileCard(state: PreflightState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "DETECTED PROFILE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val profile = state.profile
            if (profile == null) {
                Text("Detecting…", style = MaterialTheme.typography.bodyMedium)
            } else {
                KeyValue("Tier", "${profile.tier} · ${profile.describeRam} RAM")
                KeyValue("Android API", profile.sdkInt.toString())
                KeyValue("Vision channel", "${profile.visionWidth}×${profile.visionHeight} @ ${profile.cameraFps} fps")
                KeyValue("Audio classify interval", "${profile.audioClassifyIntervalMs} ms")
                KeyValue(
                    "Local model",
                    "${profile.llm.displayName} (${profile.llm.approxSizeMb} MB, " +
                        "${if (profile.llm.preferGpu) "GPU" else "CPU"} preferred)",
                )
                if (profile.isLowRamDevice) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Low-RAM device: reduced detector rates and the smaller model " +
                            "are selected automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun CheckRow(check: CapabilityCheck) {
    val color = when (check.status) {
        CheckStatus.PASS -> MaterialTheme.colorScheme.secondary
        CheckStatus.WARN -> WarningAmber
        CheckStatus.FAIL -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = check.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreflightScreenPreview() {
    EchoTheme {
        PreflightScreen(
            state = PreflightState(probed = true),
            onRescan = {},
            onContinue = {},
            onRequestPermissions = {},
            onOpenBatterySettings = {},
        )
    }
}
