package com.bithead.shelter.ai

import android.content.Context
import android.media.AudioRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import kotlin.math.sqrt

class ThreatAnalyzer(private val context: Context) {

    data class Result(
        val label: String,
        val score: Int
    )

    private var classifier: AudioClassifier? = null
    private var tensorAudio: TensorAudio? = null
    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // Exposes the live, ticking peak so the UI can show threat detection
    // reacting in real time during recording, not just a final number.
    private val _liveResult = MutableStateFlow(Result("Ambient Noise", 0))
    val liveResult: StateFlow<Result> = _liveResult.asStateFlow()

    @Volatile
    private var peakResult = Result("Ambient Noise", 0)

    init {
        try {
            classifier = AudioClassifier.createFromFile(context, "yamnet.tflite")
            tensorAudio = classifier?.createInputTensorAudio()
            audioRecord = classifier?.createAudioRecord()
        } catch (_: Exception) {
            classifier = null
        }
    }

    private fun getThreatWeight(label: String): Float {
        val l = label.lowercase()
        return when {
            l.contains("scream") || l.contains("shriek") -> 1.0f
            l.contains("shout") || l.contains("yell") || l.contains("bellow") -> 0.95f
            l.contains("groan") || l.contains("cry") || l.contains("sob") || l.contains("wail") -> 0.80f
            l.contains("glass") || l.contains("explosion") || l.contains("gun") || l.contains("bang") -> 1.0f
            l.contains("clap") || l.contains("slap") || l.contains("smash") || l.contains("percussion") -> 0.70f
            l.contains("speech") || l.contains("voice") || l.contains("human") -> 0.50f
            else -> 0.15f
        }
    }

    private fun calculateVolumeBoost(tensor: TensorAudio): Float {
        return try {
            val floatArray = tensor.tensorBuffer.floatArray
            if (floatArray.isEmpty()) return 1.0f

            var sum = 0.0
            for (sample in floatArray) {
                sum += (sample * sample)
            }
            val rms = sqrt(sum / floatArray.size).toFloat()
            (1.0f + (rms * 18f)).coerceIn(1.0f, 2.5f)
        } catch (_: Exception) {
            1.0f
        }
    }

    fun startListening() {
        peakResult = Result("Ambient Noise", 0)
        _liveResult.value = peakResult
        val record = audioRecord ?: return
        val tensor = tensorAudio ?: return
        val activeClassifier = classifier ?: return

        try {
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.startRecording()
            }
        } catch (_: Exception) {
            return
        }

        listeningJob = scope.launch {
            while (isActive) {
                try {
                    tensor.load(record)
                    val results = activeClassifier.classify(tensor)
                    val volumeMultiplier = calculateVolumeBoost(tensor)

                    val categories = results.flatMap { it.categories }
                    for (cat in categories) {
                        val weight = getThreatWeight(cat.label)
                        val rawScore = (cat.score * weight * 100)
                        val finalScore = (rawScore * volumeMultiplier).toInt().coerceIn(0, 100)

                        if (finalScore > peakResult.score) {
                            val baseLabel = cat.displayName.takeIf { it.isNotBlank() } ?: cat.label
                            val labelWithVolume = if (volumeMultiplier > 1.4f && weight >= 0.5f) "$baseLabel (Loud Peak)" else baseLabel

                            peakResult = Result(
                                label = labelWithVolume,
                                score = finalScore
                            )
                            _liveResult.value = peakResult
                        }
                    }
                } catch (_: Exception) {}
                delay(350)
            }
        }
    }

    fun stopAndAnalyze(): Result {
        listeningJob?.cancel()
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (_: Exception) {}

        return peakResult
    }

    fun close() {
        listeningJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
            classifier?.close()
        } catch (_: Exception) {}
    }
}