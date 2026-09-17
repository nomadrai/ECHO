package com.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.echo.ui.preflight.PreflightRoute
import com.echo.ui.theme.EchoTheme

/**
 * Single-activity host. Everything below the pre-flight screen (session service,
 * dashboard, investigator) is added in later milestones; this activity stays a
 * thin shell so the service owns all capture state.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoTheme {
                PreflightRoute()
            }
        }
    }
}
