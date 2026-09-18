package com.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.echo.ui.chat.ApiSettingsScreen
import com.echo.ui.chat.HistoryScreen
import com.echo.ui.chat.SessionChatScreen
import com.echo.ui.dashboard.DashboardRoute
import com.echo.ui.preflight.PreflightRoute
import com.echo.ui.theme.EchoTheme

/**
 * Single-activity host: pre-flight checks, live dashboard, session history,
 * per-session investigator chat and API settings. Capture state lives in
 * [com.echo.capture.SessionService] — this activity is only a navigation shell.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoTheme {
                // screen grammar: "preflight" | "dashboard" | "history" |
                // "chat:<sessionId>" | "api"
                var screen by rememberSaveable { mutableStateOf("preflight") }

                // Survives process death: rememberSaveable restores the string,
                // chat session ids ride inside the string itself.
                when {
                    screen == "preflight" -> PreflightRoute(
                        onContinue = { screen = "dashboard" },
                    )
                    screen == "dashboard" -> DashboardRoute(
                        onBack = { screen = "preflight" },
                        onHistory = { screen = "history" },
                        onApiSettings = { screen = "api" },
                    )
                    screen == "history" -> HistoryScreen(
                        onBack = { screen = "dashboard" },
                        onOpenSession = { id -> screen = "chat:$id" },
                    )
                    screen == "api" -> ApiSettingsScreen(
                        onBack = { screen = "history" },
                    )
                    screen.startsWith("chat:") -> SessionChatScreen(
                        sessionId = screen.removePrefix("chat:").toLongOrNull() ?: 0L,
                        onBack = { screen = "history" },
                        onOpenApiSettings = { screen = "api" },
                    )
                }
            }
        }
    }
}
