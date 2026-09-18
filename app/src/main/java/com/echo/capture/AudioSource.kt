package com.echo.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.echo.core.AudioSpec
import com.echo.core.device.DeviceProfile
import com.echo.core.model.Modality
import com.echo.core.model.Observation
import com.echo.perception.AudioDsp
import com.echo.perception.BaselineTracker
import kotlin.math.max

/**
 * Audio channel: `AudioRecord` 16 kHz mono PCM16 on a dedicated thread, 20 ms
 * hops → RMS/ZCR/bands → adaptive baseline → observations. The YAMNet
 * classifier runs only when the energy gate is open and at most once per the
 * profile's classify interval — that gate is what keeps audio near 0% CPU in a
 * quiet room.
 */
class AudioSource(
    private val profile: DeviceProfile,
    private val clock: MonotonicClock,
    private val bus: SessionBus,
    private val pipeline: PerceptionPipeline,
) {
    private val thread = Thread({ loop() }, "echo-audio").apply { priority = Thread.NORM_PRIORITY - 1 }
    @Volatile private var running = false
    private var classifier: YamnetClassifier? = null

    private val baseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.005)
    private val classifyWindow = FloatArray(YamnetClassifier.WINDOW_SAMPLES)
    private var windowPos = 0
    private var windowFilled = 0
    private var lastClassifyMs = Long.MIN_VALUE
    private var lastRatePublishMs = 0L
    private val rateMeter = com.echo.core.metrics.RateMeter()

    fun start(context: Context) {
        val yamnet = YamnetClassifier(context)
        classifier = yamnet
        bus.update {
            it.copy(
                audio = it.audio.copy(
                    classifierReady = yamnet.available,
                    classifierError = yamnet.error,
                ),
            )
        }
        running = true
        thread.start()
    }

    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before starting this source
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            fail("cannot record ${AudioSpec.SAMPLE_RATE_HZ} Hz mono PCM (minBufferSize=$minBuf)")
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioSpec.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, AudioSpec.HOP_SAMPLES * 8),
            )
        } catch (t: Throwable) {
            fail(t.message ?: "AudioRecord init failed")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            fail("AudioRecord not initialized (state=${record.state})")
            record.release()
            return
        }
        try {
            record.startRecording()
        } catch (t: Throwable) {
            fail(t.message ?: "startRecording failed")
            record.release()
            return
        }
        bus.update { it.copy(health = it.health.copy(audioRunning = true)) }

        val hop = ShortArray(AudioSpec.HOP_SAMPLES)
        while (running) {
            val n = record.read(hop, 0, AudioSpec.HOP_SAMPLES)
            if (n > 0) onHop(hop, n)
        }
        runCatching {
            record.stop()
            record.release()
        }
    }

    private fun onHop(hop: ShortArray, n: Int) {
        val tMs = clock.elapsedMs()
        val rms = AudioDsp.rms(hop, n)
        val zcr = AudioDsp.zeroCrossingRate(hop, n)
        val (low, high) = AudioDsp.bandEnergies(hop, n)
        baseline.update(rms)
        val sigma = if (baseline.ready) baseline.deviationSigma(rms) else null

        // Ring buffer feeding the classifier window (0.975 s of float samples).
        for (i in 0 until n) {
            classifyWindow[windowPos] = hop[i] / 32768f
            windowPos = (windowPos + 1) % classifyWindow.size
        }
        windowFilled = (windowFilled + n).coerceAtMost(classifyWindow.size)

        // Energy gate: classify only when something is actually happening.
        val gateOpen = rms >= max(0.015, baseline.baselineMean * 1.5) ||
            (baseline.ready && baseline.deviationSigma(rms) >= 2.0)
        val yamnet = classifier
        if (gateOpen &&
            windowFilled == classifyWindow.size &&
            yamnet?.available == true &&
            tMs - lastClassifyMs >= profile.audioClassifyIntervalMs
        ) {
            lastClassifyMs = tMs
            val labels = yamnet.classify(classifyWindow)
            if (labels.isNotEmpty()) {
                bus.update { it.copy(audio = it.audio.copy(classifyLabels = labels)) }
            }
        }

        rateMeter.tick(tMs)
        pipeline.submit(
            Observation(
                tMs = tMs,
                modality = Modality.AUDIO,
                kind = "AUDIO_RMS",
                value = rms,
                unit = "rms",
                baselineValue = baseline.baselineMean,
                deviationSigma = sigma,
            ),
            sigma,
        )
        bus.update {
            it.copy(
                audio = it.audio.copy(
                    rms = rms,
                    baselineRms = baseline.baselineMean,
                    zcr = zcr,
                    lowBand = low,
                    highBand = high,
                ),
            )
        }
        if (tMs - lastRatePublishMs >= 1_000) {
            lastRatePublishMs = tMs
            bus.update {
                it.copy(rates = it.rates.copy(audioHopsPerSecond = rateMeter.ratePerSecond(tMs)))
            }
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, "audio source failed: $message")
        bus.update { it.copy(health = it.health.copy(audioError = message)) }
    }

    fun stop() {
        running = false
        runCatching { thread.join(500) }
    }

    companion object {
        private const val TAG = "EchoAudio"
    }
}
