package com.bithead.shelter.emergency

import android.media.MediaMetadataRetriever
import java.io.File

/** Refuse to discard the only raw copy of an MP4 that never finalized. */
internal object AudioEvidenceFile {
    fun isPlayable(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { it > 0L } == true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }
}
