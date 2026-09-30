package com.bithead.shelter.data

import android.content.Context
import com.bithead.shelter.security.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey

/** One serialized owner for every operation that can append to the evidence chain. */
object EvidenceSealer {
    internal val mutex = Mutex()

    data class Request(
        val raw: File,
        val label: String,
        val score: Int,
        val mediaType: String = "AUDIO",
        val mimeType: String = "audio/mp4",
        val incidentId: String? = null
    )

    data class Result(
        val entryId: Long,
        val chainHash: String,
        val encryptedFile: String,
        val rawDeleted: Boolean
    )

    suspend fun seal(context: Context, db: AppDatabase, key: SecretKey, request: Request): Result =
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock { sealLocked(context, db, key, request) }
        }

    /** Caller must hold [mutex]; used by startup recovery to preserve capture order. */
    internal suspend fun sealLocked(context: Context, db: AppDatabase, key: SecretKey, request: Request): Result {
                val raw = request.raw
                require(raw.exists() && raw.length() > 0L) { "Raw evidence is missing or empty" }
                require(raw.name.contains(".raw.") && raw.name.startsWith("evidence_")) {
                    "Invalid evidence filename"
                }
                val createdAt = Regex("^evidence_(\\d+)").find(raw.name)?.groupValues?.get(1)?.toLongOrNull()
                    ?: raw.lastModified().takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                val encrypted = File(context.filesDir, if (request.mediaType == "AUDIO") {
                    "${raw.name.substringBefore(".raw.")}.enc"
                } else {
                    "${raw.name.substringBefore(".raw.")}.${raw.extension}.enc"
                })
                val temporary = File(context.filesDir, "${encrypted.name}.tmp")
                var finalized = false
                var committed = false
                return try {
                    if (encrypted.exists()) throw IOException("Evidence destination already exists")
                    if (temporary.exists() && !temporary.delete()) {
                        throw IOException("Could not clear an old temporary evidence file")
                    }
                    Crypto.encrypt(raw, temporary, key)
                    moveAtomically(temporary, encrypted)
                    finalized = true
                    val fileHash = Crypto.sha256(encrypted)
                    val item = Evidence(
                        createdAt = createdAt,
                        encryptedFile = encrypted.name,
                        latitude = null,
                        longitude = null,
                        threatLabel = request.label,
                        threatScore = request.score,
                        sha256 = "",
                        previousHash = null,
                        incidentId = request.incidentId,
                        mediaType = request.mediaType,
                        mimeType = request.mimeType
                    )
                    val entryId = db.evidenceDao().insertChained(item, fileHash)
                    val inserted = db.evidenceDao().byId(entryId)
                        ?: throw IOException("Evidence row was not readable after insertion")
                    committed = true
                    Result(entryId, inserted.sha256, encrypted.name, raw.delete())
                } catch (failure: Exception) {
                    temporary.delete()
                    if (finalized && !committed) {
                        val lookup = runCatching { db.evidenceDao().entryIdForFile(encrypted.name) }
                        if (lookup.isSuccess && lookup.getOrNull() != null) {
                            val recovered = db.evidenceDao().byId(lookup.getOrThrow()!!)
                                ?: throw failure
                            return Result(recovered.id, recovered.sha256, encrypted.name, raw.delete())
                        }
                        if (lookup.isSuccess) encrypted.delete()
                    }
                    throw failure
                }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }
}
