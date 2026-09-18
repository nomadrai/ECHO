package com.echo.ui.chat

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echo.ai.ApiProvider
import com.echo.ai.ApiSettingsStore
import androidx.compose.ui.platform.LocalContext

/**
 * API settings: pick a provider (Groq, Google AI Studio, OpenRouter), paste its
 * key, optionally override the model id. Keys are stored app-private and are
 * sent only to the provider they belong to.
 */
@Composable
fun ApiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ApiSettingsStore(context) }

    var provider by remember { mutableStateOf(settings.provider) }
    var key by remember { mutableStateOf(settings.keyFor(settings.provider)) }
    var model by remember { mutableStateOf(settings.modelFor(settings.provider)) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        Text(
            text = "AI provider",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Used by the investigator chat. The key stays on this device; " +
                "only the compact event digest is sent with each question — never " +
                "raw audio, frames or sensor streams.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        for (p in ApiProvider.entries) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = provider == p,
                    onClick = {
                        provider = p
                        key = settings.keyFor(p)
                        model = settings.modelFor(p)
                        saved = false
                    },
                )
                Column {
                    Text(p.displayName, fontWeight = FontWeight.Medium)
                    Text(
                        text = "${p.defaultModel} · key: ${p.keyHint}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                saved = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("${provider.displayName} API key") },
            placeholder = { Text("gsk_… / AIza… / sk-or-…") },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = {
                model = it
                saved = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model id (optional override)") },
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                settings.provider = provider
                settings.setKey(provider, key)
                settings.setModel(provider, model)
                saved = true
            },
            enabled = key.isNotBlank(),
        ) {
            Text("Save")
        }
        if (saved) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Saved. ${provider.displayName} · ${model.ifBlank { provider.defaultModel }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Where to get a key", fontWeight = FontWeight.Medium)
                Text(
                    text = "${ApiProvider.GROQ.displayName}: ${ApiProvider.GROQ.keyHint} (free tier)\n" +
                        "${ApiProvider.GOOGLE_AI_STUDIO.displayName}: ${ApiProvider.GOOGLE_AI_STUDIO.keyHint} (free tier)\n" +
                        "${ApiProvider.OPENROUTER.displayName}: ${ApiProvider.OPENROUTER.keyHint} (free models available)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
