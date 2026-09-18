package com.bithead.shelter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.bithead.shelter.security.Crypto
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {
    @Insert suspend fun insert(item: Evidence): Long
    @Query("SELECT * FROM evidence WHERE deletedAt IS NULL ORDER BY id DESC") fun observeAll(): Flow<List<Evidence>>
    @Query("SELECT * FROM evidence ORDER BY id DESC LIMIT 1") suspend fun latest(): Evidence?
    @Query("SELECT * FROM evidence ORDER BY id ASC") suspend fun allAscending(): List<Evidence>
    @Query("SELECT * FROM evidence WHERE id = :id LIMIT 1") suspend fun byId(id: Long): Evidence?
    @Query("SELECT id FROM evidence WHERE encryptedFile = :fileName LIMIT 1")
    suspend fun entryIdForFile(fileName: String): Long?
    @Query("UPDATE evidence SET deletedFileHash = :fileHash WHERE id = :id AND deletedFileHash IS NULL AND deletedAt IS NULL")
    suspend fun requestDeletion(id: Long, fileHash: String): Int
    @Query("UPDATE evidence SET deletedAt = :deletedAt WHERE id = :id AND deletedFileHash IS NOT NULL AND deletedAt IS NULL")
    suspend fun finishDeletion(id: Long, deletedAt: Long): Int
    @Query("UPDATE evidence SET latitude = :latitude, longitude = :longitude WHERE id = :id")
    suspend fun updateLocation(id: Long, latitude: Double, longitude: Double)

    @Transaction
    suspend fun insertChained(item: Evidence, encryptedFileHash: String): Long {
        val previous = latest()?.sha256
        return insert(
            item.copy(
                sha256 = Crypto.chainHash(encryptedFileHash, previous),
                previousHash = previous
            )
        )
    }
}
