package com.bithead.shelter.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query

@Entity(
    tableName = "sms_outbox",
    indices = [Index(value = ["purpose", "evidenceId", "number"], unique = true)]
)
data class SmsOutbox(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purpose: String,
    val evidenceId: Long?,
    val number: String,
    val body: String,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface SmsOutboxDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insert(item: SmsOutbox): Long

    @Query("SELECT * FROM sms_outbox WHERE purpose = :purpose AND evidenceId = :evidenceId AND number = :number LIMIT 1")
    suspend fun find(purpose: String, evidenceId: Long?, number: String): SmsOutbox?

    @Query("""UPDATE sms_outbox SET status = :status WHERE id = :id
        AND status NOT LIKE 'FAILED%' AND status NOT IN ('DELIVERED', 'DELIVERY_FAILED')
        AND (:status != 'PENDING' OR status = 'PENDING')
        AND (:status != 'SENT' OR status IN ('PENDING', 'SENT'))""")
    suspend fun updateStatus(id: Long, status: String): Int
}
