package com.echo.capture

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.audio.AudioClassifier
import com.google.mediapipe.tasks.audio.AudioData
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifierOptions
import com.google.mediapipe.tasks.core.BaseOptions

/**
 * YAMNet audio classifier via MediaPipe `tasks-audio`, loaded from
 * `assets/models/yamnet.tflite`.
 *
 * Entirely fail-soft: a missing model or a load failure degrades the session
 * to energy-only audio (the dashboard says so) — it never crashes capture.
 * The caller only invokes [classify] when the energy gate is open, so the
 * classifier idles at ~0% while the room is quiet.
 */
class YamnetClassifier(context: Context) {

    private val classifier: AudioClassifier?
    val error: String?

    init {
        val loaded = runCatching {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("models/yamnet.tflite")
                .build()
            val options = AudioClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(3)
                .setScoreThreshold(0.25f)
                .build()
            AudioClassifier.createFromOptions(context, options)
        }
        classifier = loaded.getOrNull()
        error = loaded.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        if (classifier == null) {
            Log.w(TAG, "YAMNet unavailable (energy-only mode): $error")
        }
    }

    val available: Boolean get() = classifier != null

    /** Returns up to 3 (label, score) pairs for one 0.975 s float window. */
    fun classify(samples: FloatArray): List<Pair<String, Float>> {
        val active = classifier ?: return emptyList()
        return runCatching {
            val audioData = AudioData.create(
                AudioData.AudioDataFormat.withFloatPrecision(),
                samples.size,
            )
            audioData.load(samples, 0, samples.size)
            active.classify(audioData)
                .classificationResults()
                .firstOrNull()
                ?.categories()
                ?.map { it.categoryName() to it.score() }
                ?: emptyList()
        }.onFailure { Log.w(TAG, "classify failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "EchoYamnet"

        /** YAMNet consumes 0.975 s at 16 kHz. */
        const val WINDOW_SAMPLES = 15_600
    }
}
