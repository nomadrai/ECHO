package com.echo.core.device

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.echo.core.AudioSpec

enum class CheckStatus { PASS, WARN, FAIL }

/** One pre-flight line item shown verbatim on the START SESSION screen. */
data class CapabilityCheck(
    val id: String,
    val label: String,
    val status: CheckStatus,
    val detail: String,
)

/**
 * Verifies before a session that ECHO can actually do its job on this device.
 *
 * The whole point is to fail loudly *here* rather than halfway through a live
 * demo. Every check is a cheap metadata query — no capture, no permissions
 * prompt — so it is safe to run on every screen entry.
 */
class CapabilityProbe(private val context: Context) {

    fun run(): List<CapabilityCheck> = listOf(
        cameras(),
        microphone(),
        accelerometer(),
        gyroscope(),
        requiredPermissions(),
        notificationPermission(),
        storage(),
        batteryOptimisation(),
    )

    private fun cameras(): CapabilityCheck = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        if (ids.isEmpty()) {
            CapabilityCheck("camera", "Camera", CheckStatus.FAIL, "No camera reported by CameraManager")
        } else {
            CapabilityCheck(
                "camera",
                "Camera",
                CheckStatus.PASS,
                "${ids.size} camera(s) available (vision channel armed)",
            )
        }
    }.getOrElse {
        CapabilityCheck("camera", "Camera", CheckStatus.FAIL, "Camera probe failed: ${it.message}")
    }

    @Suppress("MissingPermission")
    private fun microphone(): CapabilityCheck = runCatching {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer > 0) {
            CapabilityCheck(
                "microphone",
                "Microphone",
                CheckStatus.PASS,
                "${AudioSpec.SAMPLE_RATE_HZ / 1000} kHz mono PCM supported (min buffer ${minBuffer}B)",
            )
        } else {
            CapabilityCheck(
                "microphone",
                "Microphone",
                CheckStatus.FAIL,
                "Device cannot record ${AudioSpec.SAMPLE_RATE_HZ} Hz mono PCM",
            )
        }
    }.getOrElse {
        CapabilityCheck("microphone", "Microphone", CheckStatus.FAIL, "Audio probe failed: ${it.message}")
    }

    private fun accelerometer(): CapabilityCheck {
        val sensor = sensorManager()?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        return if (sensor != null) {
            CapabilityCheck(
                "accelerometer",
                "Accelerometer",
                CheckStatus.PASS,
                "${sensor.name} · up to ${sensor.maximumRange} m/s²",
            )
        } else {
            CapabilityCheck(
                "accelerometer",
                "Accelerometer",
                CheckStatus.FAIL,
                "No accelerometer: impact detection is impossible on this device",
            )
        }
    }

    private fun gyroscope(): CapabilityCheck {
        val sensor = sensorManager()?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        return if (sensor != null) {
            CapabilityCheck("gyroscope", "Gyroscope", CheckStatus.PASS, sensor.name)
        } else {
            // The Galaxy M12 genuinely has no gyroscope — orientation-change events
            // simply get dropped and the session says so.
            CapabilityCheck(
                "gyroscope",
                "Gyroscope",
                CheckStatus.WARN,
                "Not present — orientation-change detection disabled, everything else runs",
            )
        }
    }

    private fun requiredPermissions(): CapabilityCheck {
        val camera = granted(Manifest.permission.CAMERA)
        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val detail = buildString {
            append(if (camera) "camera granted" else "camera NOT granted")
            append(" · ")
            append(if (mic) "microphone granted" else "microphone NOT granted")
        }
        return CapabilityCheck(
            "permissions",
            "Capture permissions",
            if (camera && mic) CheckStatus.PASS else CheckStatus.WARN,
            detail,
        )
    }

    private fun notificationPermission(): CapabilityCheck {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return CapabilityCheck(
                "notifications",
                "Notifications",
                CheckStatus.PASS,
                "Not required before Android 13",
            )
        }
        val granted = granted(Manifest.permission.POST_NOTIFICATIONS)
        return CapabilityCheck(
            "notifications",
            "Notifications",
            if (granted) CheckStatus.PASS else CheckStatus.WARN,
            if (granted) {
                "Granted — the monitoring service can show its live session notification"
            } else {
                "Not granted — required for the foreground monitoring notification"
            },
        )
    }

    private fun storage(): CapabilityCheck = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        val freeMb = stat.availableBytes / (1024 * 1024)
        val status = when {
            freeMb >= 1024 -> CheckStatus.PASS
            freeMb >= 400 -> CheckStatus.WARN
            else -> CheckStatus.FAIL
        }
        CapabilityCheck(
            "storage",
            "Evidence storage",
            status,
            "$freeMb MB free for event-anchored evidence",
        )
    }.getOrElse {
        CapabilityCheck("storage", "Evidence storage", CheckStatus.FAIL, "StatFs failed: ${it.message}")
    }

    private fun batteryOptimisation(): CapabilityCheck {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        return if (exempt) {
            CapabilityCheck(
                "battery",
                "Background survival",
                CheckStatus.PASS,
                "ECHO is exempt from battery optimisation",
            )
        } else {
            // OriginOS (iQOO/vivo) is aggressive here; a killed service mid-demo
            // is the single most likely way to lose a stage demo.
            CapabilityCheck(
                "battery",
                "Background survival",
                CheckStatus.WARN,
                "Not exempt — the monitoring service may be killed mid-session",
            )
        }
    }

    private fun sensorManager(): SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
