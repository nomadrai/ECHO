package com.echo.ui.preflight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echo.core.device.CapabilityCheck
import com.echo.core.device.CapabilityProbe
import com.echo.core.device.DeviceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PreflightState(
    val profile: DeviceProfile? = null,
    val checks: List<CapabilityCheck> = emptyList(),
    val probed: Boolean = false,
) {
    /** A single hard failure is enough to make a live session pointless. */
    val hasBlockingFailure: Boolean
        get() = checks.any { it.status == com.echo.core.device.CheckStatus.FAIL }
}

class PreflightViewModel(application: Application) : AndroidViewModel(application) {

    private val probe = CapabilityProbe(application)

    private val _state = MutableStateFlow(PreflightState())
    val state: StateFlow<PreflightState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            // Camera-service and sensor queries can occasionally take tens of
            // milliseconds; never block the first frame on them.
            val profile = withContext(Dispatchers.Default) { DeviceProfile.detect(app) }
            val checks = withContext(Dispatchers.Default) { probe.run() }
            _state.value = PreflightState(profile = profile, checks = checks, probed = true)
        }
    }
}
