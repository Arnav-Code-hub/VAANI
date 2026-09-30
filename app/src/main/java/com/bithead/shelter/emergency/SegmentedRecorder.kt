package com.bithead.shelter.emergency

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Records audio evidence as consecutive ~30 s MP4 segments on one continuous
 * MediaRecorder session. When a segment closes, [onSegmentReady] receives it so
 * the owner can seal it into the hash chain immediately. If the phone is killed
 * or destroyed mid-recording, at most the segment in progress is lost.
 *
 * Files stay registered in [inFlight] from creation until the owner calls
 * [release] after sealing, so startup recovery never touches a live segment.
 */
class SegmentedRecorder(
    private val context: Context,
    private val pendingDir: File,
    private val incidentId: String,
    private val onSegmentReady: (File) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val segmentMs: Int = SEGMENT_MS
) {
    companion object {
        const val SEGMENT_MS = 30_000
        private const val BIT_RATE = 64_000
        private const val SAMPLE_RATE = 44_100

        private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private var lastTimestamp = 0L

        /** True while [file] is being written or awaiting its seal in this process. */
        fun isInFlight(file: File): Boolean = file.name in inFlight

        /** Owner calls this once a finished segment has been sealed (or abandoned). */
        fun release(file: File) { inFlight.remove(file.name) }

        @Synchronized
        private fun nextTimestamp(): Long {
            // Segment names carry their capture time and must never collide.
            val now = System.currentTimeMillis()
            lastTimestamp = if (now > lastTimestamp) now else lastTimestamp + 1
            return lastTimestamp
        }
    }

    private var recorder: MediaRecorder? = null
    private var current: File? = null
    private var queued: File? = null

    /** Number of segments handed to [onSegmentReady] so far. */
    var completedSegments = 0
        private set

    val isRecording: Boolean get() = recorder != null

    /** Starts the first segment. Must be called on a thread with a Looper (the main thread). */
    fun start() {
        check(recorder == null) { "Recording already in progress" }
        if (!pendingDir.exists() && !pendingDir.mkdirs()) throw IOException("Could not create private pending-evidence storage")
        completedSegments = 0
        startRecorder(newSegmentFile())
    }

    /**
     * Stops recording and returns every remaining segment that holds audio, in
     * capture order, for the owner to seal. A stop can race a segment hand-off
     * (MediaRecorder then reports "stop failed" although the finished segment
     * is complete), so a stop error never discards captured bytes: anything
     * non-empty is returned, and only empty placeholders are deleted.
     */
    fun stop(): List<File> {
        val active = recorder ?: return emptyList()
        recorder = null
        runCatching { active.stop() }
        runCatching { active.release() }
        val remaining = listOfNotNull(current, queued)
        current = null
        queued = null
        return remaining.filter { file ->
            val hasAudio = file.exists() && file.length() > 0L
            if (!hasAudio) {
                file.delete()
                release(file)
            }
            hasAudio
        }
    }

    private fun startRecorder(file: File) {
        inFlight.add(file.name)
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                // Android only exposes seamless file hand-off for size limits, so a
                // fixed bitrate turns the segment duration into a byte budget.
                setMaxFileSize(BIT_RATE / 8L * segmentMs / 1000L)
                setOnInfoListener { mr, what, _ -> onInfo(mr, what) }
                prepare()
                start()
            }
        } catch (error: Exception) {
            runCatching { created.release() }
            release(file)
            file.takeIf { it.length() == 0L }?.delete()
            throw error
        }
        recorder = created
        current = file
    }

    private fun onInfo(mr: MediaRecorder, what: Int) {
        if (mr !== recorder) return
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                if (queued != null) return
                val next = newSegmentFile()
                inFlight.add(next.name)
                try {
                    mr.setNextOutputFile(next)
                    queued = next
                } catch (_: Exception) {
                    // The recorder will stop at the limit; MAX_FILESIZE_REACHED restarts it.
                    release(next)
                    next.delete()
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                val finished = current
                current = queued
                queued = null
                finished?.let(::handOff)
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                // No next output was accepted. Android does not guarantee that
                // the recorder has stopped when this callback arrives; stop it
                // explicitly before handing the file to the seal worker.
                val finished = current
                recorder = null
                current = null
                runCatching { mr.stop() }
                runCatching { mr.release() }
                finished?.let(::handOff)
                runCatching { startRecorder(newSegmentFile()) }
                    .onFailure(onFailure)
            }
        }
    }

    private fun handOff(file: File) {
        if (file.exists() && file.length() > 0L) {
            completedSegments++
            onSegmentReady(file)
        } else {
            release(file)
        }
    }

    private fun newSegmentFile() = File(pendingDir, "evidence_${nextTimestamp()}_audio_$incidentId.raw.m4a")
}
