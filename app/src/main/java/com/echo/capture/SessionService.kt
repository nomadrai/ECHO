package com.echo.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.echo.R
import com.echo.core.device.DeviceProfile
import com.echo.core.time.SessionClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The real capture pipeline as a foreground service (plan §5): it owns the
 * camera, mic and sensor sources, the monotonic clock, a partial wake lock,
 * and the [SessionBus] the dashboard renders. START/END SESSION are intents;
 * everything else — degraded modes, per-channel failures — is honest state on
 * the bus, never a mock.
 */
class SessionService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.echo.action.START_SESSION"
        const val ACTION_STOP = "com.echo.action.STOP_SESSION"

        private const val TAG = "EchoSession"
        private const val CHANNEL_ID = "echo_session"
        private const val NOTIFICATION_ID = 1

        /** Service-owned truth; the dashboard collects this, never a copy. */
        val bus = SessionBus()
    }

    private var clock: MonotonicClock? = null
    private var cameraSource: CameraSource? = null
    private var audioSource: AudioSource? = null
    private var sensorSource: SensorSource? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Session clock + live notification refresh while recording.
        lifecycleScope.launch {
            while (true) {
                delay(200)
                if (bus.state.value.phase == SessionPhase.RECORDING) {
                    bus.update { it.copy(sessionMs = clock?.elapsedMs() ?: 0) }
                }
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(1_000)
                val state = bus.state.value
                if (state.phase == SessionPhase.RECORDING) {
                    runCatching {
                        getSystemService(Context.NOTIFICATION_SERVICE)
                            .let { it as NotificationManager }
                            .notify(NOTIFICATION_ID, notification(state))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession()
        }
        return START_STICKY
    }

    private fun startSession() {
        val current = bus.state.value
        if (current.phase == SessionPhase.RECORDING || current.phase == SessionPhase.PREPARING) {
            return
        }
        bus.reset()
        bus.update { it.copy(phase = SessionPhase.PREPARING) }

        // startForeground must happen promptly and can itself fail when the
        // camera/mic FGS types are not allowed yet (missing while-in-use
        // permission on API 34+) — fail loudly but never crash.
        val started = runCatching {
            ensureChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(bus.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }
        if (started.isFailure) {
            val message = started.exceptionOrNull()?.message ?: "foreground service rejected"
            Log.e(TAG, "startForeground failed: $message")
            bus.update {
                it.copy(
                    phase = SessionPhase.STOPPED,
                    health = it.health.copy(audioError = "foreground service: $message"),
                )
            }
            stopSelf()
            return
        }

        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "echo:session")
            .apply { acquire(60 * 60 * 1000L) } // hard cap: 1 h session

        clock = MonotonicClock()
        val profile = DeviceProfile.detect(this)
        val sessionBus = bus
        val pipeline = PerceptionPipeline(sessionBus)
        val sessionClock = clock ?: return

        bus.update {
            it.copy(
                phase = SessionPhase.RECORDING,
                startedAtEpochMs = System.currentTimeMillis(),
                sessionMs = 0,
                motion = it.motion.copy(gyroAvailable = false),
            )
        }

        // If a permission is missing the session still runs — degraded, and the
        // dashboard shows exactly which channel is dead (plan §8).
        if (cameraGranted) {
            cameraSource = CameraSource(profile, sessionClock, sessionBus, pipeline).also {
                it.start(this, this)
            }
        } else {
            bus.update {
                it.copy(health = it.health.copy(cameraError = "CAMERA permission not granted"))
            }
        }
        if (micGranted) {
            audioSource = AudioSource(profile, sessionClock, sessionBus, pipeline).also {
                it.start(this)
            }
        } else {
            bus.update {
                it.copy(health = it.health.copy(audioError = "RECORD_AUDIO permission not granted"))
            }
        }
        sensorSource = SensorSource(sessionClock, sessionBus, pipeline).also { it.start(this) }

        Log.i(TAG, "session started (tier=${profile.tier}, llm=${profile.llm.name})")
    }

    private fun stopSession() {
        val phase = bus.state.value.phase
        if (phase == SessionPhase.IDLE || phase == SessionPhase.STOPPED || phase == SessionPhase.STOPPING) {
            return
        }
        bus.update { it.copy(phase = SessionPhase.STOPPING) }

        cameraSource?.stop()
        audioSource?.stop()
        sensorSource?.stop()
        cameraSource = null
        audioSource = null
        sensorSource = null

        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        val finalState = bus.state.value
        Log.i(
            TAG,
            "session ended: ${finalState.sessionMs} ms, ${finalState.eventCount} events, " +
                "degraded=${finalState.health.degraded}",
        )
        bus.update { it.copy(phase = SessionPhase.STOPPED) }
    }

    override fun onDestroy() {
        if (bus.state.value.phase == SessionPhase.RECORDING ||
            bus.state.value.phase == SessionPhase.PREPARING
        ) {
            stopSession()
        }
        super.onDestroy()
    }

    private fun notification(state: SessionBusState): Notification {
        val text = if (state.phase == SessionPhase.RECORDING) {
            "Monitoring · ${state.eventCount} events · ${SessionClock.formatOffset(state.sessionMs)}"
        } else {
            "Preparing capture…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ECHO session")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live session", NotificationManager.IMPORTANCE_LOW),
        )
    }
}
