package com.echo.capture

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.echo.core.device.DeviceProfile
import com.echo.core.model.Modality
import com.echo.core.model.Observation
import com.echo.perception.BaselineTracker
import com.echo.perception.VisionDsp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Vision channel: CameraX `ImageAnalysis` at the profile's low resolution with
 * KEEP_ONLY_LATEST backpressure. Every frame: Y plane → packed luma → 160×120
 * downscale → frame-diff against the previous frame → changed-pixel-fraction
 * observation. Deterministic DSP, no model, a few percent of one core.
 */
class CameraSource(
    private val profile: DeviceProfile,
    private val clock: MonotonicClock,
    private val bus: SessionBus,
    private val pipeline: PerceptionPipeline,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "echo-camera").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private var provider: ProcessCameraProvider? = null

    // Luma buffers, allocated on first frame.
    private var srcW = 0
    private var srcH = 0
    private var packed: ByteArray? = null
    private var previous: ByteArray? = null
    private var scaled: ByteArray? = null

    private val diffW = 160
    private val diffH = 120
    private val baseline = BaselineTracker(minSamples = 50, sigmaFloor = 0.002)
    private var lastRatePublishMs = 0L
    private val rateMeter = com.echo.core.metrics.RateMeter()

    private fun publishRate(tMs: Long) {
        rateMeter.tick(tMs)
        if (tMs - lastRatePublishMs >= 1_000) {
            lastRatePublishMs = tMs
            bus.update {
                it.copy(rates = it.rates.copy(framesPerSecond = rateMeter.ratePerSecond(tMs)))
            }
        }
    }

    @SuppressLint("MissingPermission") // the service checks CAMERA before starting this source
    fun start(context: Context, owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val cameraProvider = future.get()
                provider = cameraProvider
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(profile.visionWidth, profile.visionHeight),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor) { proxy -> analyze(proxy) } }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis,
                )
                bus.update { it.copy(health = it.health.copy(cameraRunning = true)) }
            }.onFailure { e ->
                Log.e(TAG, "camera bind failed", e)
                bus.update {
                    it.copy(health = it.health.copy(cameraError = it.health.cameraError ?: (e.message ?: "bind failed")))
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val tMs = clock.elapsedMs()
            publishRate(tMs)
            val plane = proxy.planes[0]
            if (plane.pixelStride != 1) {
                return // unexpected Y layout; skip the frame
            }
            val w = proxy.width
            val h = proxy.height
            if (w != srcW || h != srcH) {
                srcW = w
                srcH = h
                packed = ByteArray(w * h)
                previous = null
                bus.update { it.copy(vision = it.vision.copy(width = w, height = h)) }
            }
            val src = packed ?: return
            val buffer = plane.buffer
            var offset = 0
            for (row in 0 until h) {
                buffer.position(row * plane.rowStride)
                buffer.get(src, offset, w)
                offset += w
            }

            val scaledArr = scaled ?: ByteArray(diffW * diffH).also { scaled = it }
            VisionDsp.downscale(src, w, h, diffW, diffH, scaledArr)

            val prev = previous
            if (prev != null) {
                val stats = VisionDsp.diff(scaledArr, prev, diffW * diffH)
                baseline.update(stats.changedFraction)
                val sigma = if (baseline.ready) baseline.deviationSigma(stats.changedFraction) else null
                pipeline.submit(
                    Observation(
                        tMs = tMs,
                        modality = Modality.VISION,
                        kind = "FRAME_MOTION",
                        value = stats.changedFraction,
                        unit = "px-frac",
                        baselineValue = baseline.baselineMean,
                        deviationSigma = sigma,
                    ),
                    sigma,
                )
                bus.update {
                    it.copy(
                        vision = it.vision.copy(
                            changedFraction = stats.changedFraction,
                            meanAbsDiff = stats.meanAbsDiff,
                            meanLuma = stats.meanLuma,
                        ),
                    )
                }
            }
            previous = scaledArr.copyOf()
        } catch (t: Throwable) {
            Log.e(TAG, "frame analysis failed", t)
        } finally {
            proxy.close()
        }
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        provider = null
        executor.shutdown()
    }

    companion object {
        private const val TAG = "EchoCamera"
    }
}
