package com.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.echo.ui.dashboard.DashboardRoute
import com.echo.ui.preflight.PreflightRoute
import com.echo.ui.theme.EchoTheme

/**
 * Single-activity host: pre-flight checks, then the live dashboard. Capture
 * state lives in [com.echo.capture.SessionService] — this activity is only a
 * navigation shell.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoTheme {
                var screen by rememberSaveable { mutableStateOf("preflight") }
                if (screen == "preflight") {
                    PreflightRoute(onContinue = { screen = "dashboard" })
                } else {
                    DashboardRoute(onBack = { screen = "preflight" })
                }
            }
        }
    }
}
