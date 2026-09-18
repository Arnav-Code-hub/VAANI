package com.bithead.shelter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "evidence")
data class Evidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val encryptedFile: String,
    val latitude: Double?,
    val longitude: Double?,
    val threatLabel: String,
    val threatScore: Int,
    val sha256: String,
    val previousHash: String?,
    val deletedFileHash: String? = null,
    val deletedAt: Long? = null
)
