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
import com.echo.core.model.ManualTag
import com.echo.core.model.Modality
import com.echo.core.model.TagCategory
import com.echo.core.model.TimelineSource
import com.echo.core.time.SessionClock
import com.echo.data.EchoStore
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

        /** What the user said they were building, captured before capture begins. */
        const val EXTRA_GOAL = "com.echo.extra.SESSION_GOAL"

        /**
         * Comma-joined [SensorChannel] ids enabled for this session
         * ([SensorChannelCodec]). Absent/empty = capture everything — the
         * pre-selection behaviour, kept as a safe fallback for raw starts.
         */
        const val EXTRA_CHANNELS = "com.echo.extra.SESSION_CHANNELS"

        /**
         * Tag-this-moment intent, sent by the dashboard. The service owns the
         * clock and the active session id, so it — not the activity — stamps
         * the tag: one tap, service-side timestamp, no clock skew and no gap
         * between what the user marked and what the sensors saw.
         */
        const val ACTION_TAG = "com.echo.action.TAG_MOMENT"

        /** Label text for [ACTION_TAG]. */
        const val EXTRA_TAG_LABEL = "com.echo.extra.TAG_LABEL"

        /** [com.echo.core.model.TagCategory] name for [ACTION_TAG]. */
        const val EXTRA_TAG_CATEGORY = "com.echo.extra.TAG_CATEGORY"

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
    private var environmentSource: EnvironmentSource? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Live pipeline; owned by the service so END can finalize correlation. */
    private var pipeline: PerceptionPipeline? = null

    /** DB row id of the session being recorded; 0 = none. */
    private var activeSessionId: Long = 0

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
            ACTION_START -> startSession(
                goal = intent.getStringExtra(EXTRA_GOAL) ?: "",
                channels = SensorChannelCodec.parse(intent.getStringExtra(EXTRA_CHANNELS)),
            )
            ACTION_STOP -> stopSession()
            ACTION_TAG -> tagMoment(
                label = intent.getStringExtra(EXTRA_TAG_LABEL) ?: "",
                category = TagCategory.fromName(intent.getStringExtra(EXTRA_TAG_CATEGORY)),
            )
        }
        return START_STICKY
    }

    private fun startSession(goal: String, channels: Set<SensorChannel>) {
        val current = bus.state.value
        if (current.phase == SessionPhase.RECORDING || current.phase == SessionPhase.PREPARING) {
            return
        }
        bus.reset()
        bus.update { it.copy(phase = SessionPhase.PREPARING) }

        // Sensor selection: what the user enabled for THIS session. Empty
        // (raw start without the extra) = everything, the legacy behaviour.
        val enabled = if (channels.isEmpty()) SensorChannel.ALL else channels
        // The camera/mic foreground-service types are requested only when
        // those channels are actually enabled — a sensor-only session (e.g.
        // a sleep night) needs no while-in-use permission state at all.
        val fgsTypes = (if (SensorChannel.CAMERA in enabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
            (if (SensorChannel.MICROPHONE in enabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)

        // startForeground must happen promptly and can itself fail when the
        // camera/mic FGS types are not allowed yet (missing while-in-use
        // permission on API 34+) — fail loudly but never crash.
        val started = runCatching {
            ensureChannel()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(bus.state.value), fgsTypes)
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
        // Persistence: one session row per START; every extracted event is
        // inserted synchronously before it reaches the dashboard, so the DB
        // is always the source of truth for the timeline (plan §7).
        val store = EchoStore.get(this)
        activeSessionId = store.createSession(
            startedAtEpochMs = System.currentTimeMillis(),
            deviceMeta = "tier=${profile.tier},sdk=${profile.sdkInt},ram=${profile.describeRam}",
            goal = goal,
        )
        val pipeline = PerceptionPipeline(
            bus = sessionBus,
            onEvent = { event -> store.insertEvent(activeSessionId, event) },
            onRelation = { relation -> store.insertRelation(activeSessionId, relation) },
        ).also { this.pipeline = it }
        val sessionClock = clock ?: return

        bus.update {
            it.copy(
                phase = SessionPhase.RECORDING,
                startedAtEpochMs = System.currentTimeMillis(),
                sessionMs = 0,
                selectedChannels = enabled,
                motion = it.motion.copy(
                    gyroAvailable = false,
                    linearAccelAvailable = false,
                    tiltAvailable = false,
                ),
            )
        }

        // Only the ENABLED sources are constructed and started — a disabled
        // channel has no thread, no listener, no camera bind: the battery and
        // storage savings are real capture work that never happens, not a
        // display filter. Permission-missing still degrades the channel the
        // same way as before (dashboard shows why).
        if (SensorChannel.CAMERA in enabled) {
            if (cameraGranted) {
                cameraSource = CameraSource(profile, sessionClock, sessionBus, pipeline).also {
                    it.start(this, this)
                }
            } else {
                bus.update {
                    it.copy(health = it.health.copy(cameraError = "CAMERA permission not granted"))
                }
            }
        }
        if (SensorChannel.MICROPHONE in enabled) {
            if (micGranted) {
                audioSource = AudioSource(profile, sessionClock, sessionBus, pipeline).also {
                    it.start(this)
                }
            } else {
                bus.update {
                    it.copy(health = it.health.copy(audioError = "RECORD_AUDIO permission not granted"))
                }
            }
        }
        if (enabled.any { it.modality == Modality.MOTION }) {
            sensorSource = SensorSource(sessionClock, sessionBus, pipeline, enabled).also { it.start(this) }
        }
        // Environment sensors (magnetometer, barometer, light, proximity,
        // step counter, temperature/humidity when present): zero permissions,
        // presence-gated per sensor, registration gated per selection.
        if (enabled.any { it.modality == Modality.ENVIRONMENT }) {
            environmentSource = EnvironmentSource(sessionClock, sessionBus, pipeline, enabled).also {
                it.start(this)
            }
        }

        Log.i(
            TAG,
            "session started (tier=${profile.tier}, llm=${profile.llm.name}, " +
                "channels=${enabled.joinToString("+") { it.id }})",
        )
    }

    /**
     * Live tagging: stamps the moment with the session clock, persists the
     * tag through the same serialized store the events use, and only then
     * raises it on the bus — the DB is the source of truth, exactly like
     * events. Ignored when no session is active (a stale intent arriving
     * after END must not create an orphan row).
     */
    private fun tagMoment(label: String, category: TagCategory) {
        val sessionId = activeSessionId
        if (sessionId <= 0) return
        val tag = ManualTag(
            sessionId = sessionId,
            tMs = clock?.elapsedMs() ?: 0L,
            label = label.trim().ifEmpty { "Tagged moment" },
            category = category,
            source = TimelineSource.USER,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val rowId = EchoStore.get(this).insertTag(sessionId, tag)
        bus.addTag(if (rowId > 0) tag.copy(id = rowId) else tag)
    }

    private fun stopSession() {
        val phase = bus.state.value.phase
        if (phase == SessionPhase.IDLE || phase == SessionPhase.STOPPED || phase == SessionPhase.STOPPING) {
            return
        }
        bus.update { it.copy(phase = SessionPhase.STOPPING) }

        val store = EchoStore.get(this)
        if (activeSessionId > 0) {
            // M1 finalize: derive PRE_INCIDENT_DEVIATION events and flush all
            // relation edges. finalize() persists through the same hooks the
            // live pipeline used, so derived events land in the DB before the
            // session row is sealed.
            pipeline?.finalize()
            store.endSession(
                sessionId = activeSessionId,
                endedAtEpochMs = System.currentTimeMillis(),
                durationMs = bus.state.value.sessionMs,
            )
            Log.i(TAG, "session sealed: id=$activeSessionId")
            activeSessionId = 0
        }

        cameraSource?.stop()
        audioSource?.stop()
        sensorSource?.stop()
        environmentSource?.stop()
        cameraSource = null
        audioSource = null
        sensorSource = null
        environmentSource = null
        pipeline = null

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
