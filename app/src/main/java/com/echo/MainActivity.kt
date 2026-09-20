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
import com.echo.ui.trends.TrendsScreen

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
                // "chat:<sessionId>" | "trends:<id,id,…>" | "api"
                var screen by rememberSaveable { mutableStateOf("preflight") }

                // The API settings / history screens can be opened from several
                // places; Back returns to wherever they came from.
                var returnAfterApi by rememberSaveable { mutableStateOf("preflight") }
                var historyFromPreflight by rememberSaveable { mutableStateOf(false) }
                fun openApi() {
                    returnAfterApi = screen
                    screen = "api"
                }
                fun openHistory(fromPreflight: Boolean) {
                    historyFromPreflight = fromPreflight
                    screen = "history"
                }

                // Survives process death: rememberSaveable restores the strings,
                // chat session ids ride inside the string itself.
                when {
                    screen == "preflight" -> PreflightRoute(
                        onContinue = { screen = "dashboard" },
                        onHistory = { openHistory(fromPreflight = true) },
                        onApiSettings = { openApi() },
                    )
                    screen == "dashboard" -> DashboardRoute(
                        onBack = { screen = "preflight" },
                        onHistory = { screen = "history" },
                        onApiSettings = { openApi() },
                    )
                    screen == "history" -> HistoryScreen(
                        onBack = { screen = if (historyFromPreflight) "preflight" else "dashboard" },
                        backLabel = if (historyFromPreflight) "← Pre-flight" else "← Dashboard",
                        onOpenSession = { id -> screen = "chat:$id" },
                        onCompare = { ids ->
                            // Survives process death like chat ids: the set
                            // rides inside the route string.
                            screen = "trends:" + ids.joinToString(",")
                        },
                    )
                    screen == "api" -> ApiSettingsScreen(
                        onBack = { screen = returnAfterApi },
                    )
                    screen.startsWith("chat:") -> SessionChatScreen(
                        sessionId = screen.removePrefix("chat:").toLongOrNull() ?: 0L,
                        onBack = { screen = "history" },
                        onOpenApiSettings = { openApi() },
                    )
                    screen.startsWith("trends:") -> TrendsScreen(
                        sessionIds = screen.removePrefix("trends:").split(",")
                            .mapNotNull { it.toLongOrNull() },
                        onBack = { screen = "history" },
                        onOpenSession = { id -> screen = "chat:$id" },
                    )
                }
            }
        }
    }
}
