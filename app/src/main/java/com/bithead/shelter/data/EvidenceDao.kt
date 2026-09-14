package com.bithead.shelter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {
    @Insert suspend fun insert(item: Evidence): Long
    @Query("SELECT * FROM evidence ORDER BY id DESC") fun observeAll(): Flow<List<Evidence>>
    @Query("SELECT * FROM evidence ORDER BY id DESC LIMIT 1") suspend fun latest(): Evidence?
    @Query("SELECT * FROM evidence ORDER BY id ASC") suspend fun allAscending(): List<Evidence>
}
