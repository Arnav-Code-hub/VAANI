package com.bithead.shelter.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** All capture and inference stays in this process. No Android recognition provider is invoked. */
object OfflineSafeword {
    private const val MODEL = "models/ggml-base-q5_1.bin"
    private const val SAMPLE_RATE = 16_000
    private val inferenceMutex = Mutex()
    private var model: WhisperContext? = null

    fun available(context: Context): Boolean = runCatching {
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        context.assets.open(MODEL).use { it.read() >= 0 }
        WhisperContext.getSystemInfo()
        true
    }.getOrDefault(false)

    fun languageCode(tag: String): String = tag.substringBefore('-').also {
        require(it in setOf("en", "hi", "bn", "mr", "ta")) {
            "Select English, Hindi, Bengali, Marathi, or Tamil for offline voice recognition"
        }
    }

    fun isVerified(context: Context, word: String, languageTag: String): Boolean = runCatching {
        context.getSharedPreferences("shelter_prefs", Context.MODE_PRIVATE)
            .getString("offline_safeword_verified_for", null) == verificationKey(word, languageTag)
    }.getOrDefault(false)

    fun markVerified(context: Context, word: String, languageTag: String) {
        context.getSharedPreferences("shelter_prefs", Context.MODE_PRIVATE).edit()
            .putString("offline_safeword_verified_for", verificationKey(word, languageTag)).apply()
    }

    private fun verificationKey(word: String, tag: String): String =
        "${languageCode(tag)}|${word.trim().lowercase(Locale.ROOT)}"

    suspend fun transcribe(context: Context, samples: FloatArray, tag: String): String =
        inferenceMutex.withLock {
            val loaded = model ?: withContext(Dispatchers.IO) {
                WhisperContext.createContextFromAsset(context.assets, MODEL)
            }.also { model = it }
            loaded.transcribeData(samples, languageCode(tag))
        }

    suspend fun capture(context: Context, durationMs: Long, onReady: () -> Unit): FloatArray =
        withContext(Dispatchers.IO) {
            val recorder = openRecorder(context)
            try {
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Microphone did not open"
                }
                onReady()
                readChunk(recorder, durationMs)
            } finally {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                recorder.release()
            }
        }

    /** Keeps one AudioRecord open while inference consumes bounded four-second chunks. */
    suspend fun stream(context: Context, tag: String, onReady: () -> Unit,
        onPhrase: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        val recorder = openRecorder(context)
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not open" }
            onReady()
            coroutineScope {
                val chunks = Channel<FloatArray>(2)
                val consumer = launch(Dispatchers.Default) {
                    for (chunk in chunks) onPhrase(transcribe(context, chunk, tag))
                }
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        check(chunks.trySend(readChunk(recorder, 4_000L)).isSuccess) {
                            "Offline voice processing cannot keep up with live audio"
                        }
                    }
                } finally {
                    chunks.close()
                    consumer.cancel()
                }
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
    }

    private fun openRecorder(context: Context): AudioRecord {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "Microphone permission is missing" }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone does not support 16 kHz mono capture" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum.coerceAtLeast(4096))
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Microphone could not be initialized"
        }
        return recorder
    }

    private suspend fun readChunk(recorder: AudioRecord, durationMs: Long): FloatArray {
        val target = (SAMPLE_RATE * durationMs / 1_000L).toInt()
        val output = FloatArray(target)
        val buffer = ShortArray(2048)
        var offset = 0
        while (offset < target) {
            currentCoroutineContext().ensureActive()
            val count = recorder.read(buffer, 0, minOf(buffer.size, target - offset),
                AudioRecord.READ_NON_BLOCKING)
            if (count == 0) { delay(10L); continue }
            check(count > 0) { "Microphone read failed ($count)" }
            for (i in 0 until count) output[offset + i] = buffer[i] / 32768f
            offset += count
        }
        return output
    }
}
