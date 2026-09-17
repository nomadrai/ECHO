package com.echo.core.device

import android.app.ActivityManager
import android.content.Context

/** Coarse performance class. The pipeline is identical; only the rates change. */
enum class DeviceTier { LOW, HIGH }

/**
 * Which on-device model ECHO should attempt to load.
 *
 * Sizes are the actual published `.litertlm` artifacts (see PROJECT.md §5.2).
 * The 4 GB Galaxy M12 cannot host an E2B-class model alongside a live pipeline,
 * hence the 584 MB int4 fallback.
 */
enum class LlmChoice(
    val displayName: String,
    val approxSizeMb: Int,
    val preferGpu: Boolean,
) {
    GEMMA3_1B_INT4("Gemma3-1B-IT int4", 584, preferGpu = false),
    GEMMA4_E2B("Gemma4-E2B", 2010, preferGpu = true),
}

/**
 * Runtime envelope detected once at startup.
 *
 * Detector rates, model choice and backend differ per tier; the *architecture*
 * does not. Surfacing this in the UI keeps the demo honest — the viewer can see
 * why the phone is doing what it is doing.
 */
data class DeviceProfile(
    val tier: DeviceTier,
    val totalRamMb: Long,
    val isLowRamDevice: Boolean,
    val sdkInt: Int,
    val cameraFps: Int,
    val visionWidth: Int,
    val visionHeight: Int,
    val audioClassifyIntervalMs: Long,
    val llm: LlmChoice,
) {
    val describeRam: String get() = "${totalRamMb / 1024} GB"

    companion object {
        /** Below this, an E2B-class model will not coexist with live capture. */
        private const val HIGH_TIER_MIN_RAM_MB = 6 * 1024L

        fun detect(context: Context): DeviceProfile {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val totalRamMb = memoryInfo.totalMem / (1024 * 1024)

            val lowTier = activityManager.isLowRamDevice || totalRamMb < HIGH_TIER_MIN_RAM_MB

            return if (lowTier) {
                DeviceProfile(
                    tier = DeviceTier.LOW,
                    totalRamMb = totalRamMb,
                    isLowRamDevice = activityManager.isLowRamDevice,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                    cameraFps = 3,
                    visionWidth = 320,
                    visionHeight = 240,
                    audioClassifyIntervalMs = 1_000,
                    llm = LlmChoice.GEMMA3_1B_INT4,
                )
            } else {
                DeviceProfile(
                    tier = DeviceTier.HIGH,
                    totalRamMb = totalRamMb,
                    isLowRamDevice = activityManager.isLowRamDevice,
                    sdkInt = android.os.Build.VERSION.SDK_INT,
                    cameraFps = 8,
                    visionWidth = 640,
                    visionHeight = 480,
                    audioClassifyIntervalMs = 500,
                    llm = LlmChoice.GEMMA4_E2B,
                )
            }
        }
    }
}
