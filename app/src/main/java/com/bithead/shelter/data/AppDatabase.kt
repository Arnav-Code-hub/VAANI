package com.bithead.shelter.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Evidence::class, SmsOutbox::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun evidenceDao(): EvidenceDao
    abstract fun smsOutboxDao(): SmsOutboxDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE evidence ADD COLUMN deletedFileHash TEXT")
                db.execSQL("ALTER TABLE evidence ADD COLUMN deletedAt INTEGER")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE evidence ADD COLUMN incidentId TEXT")
                db.execSQL("ALTER TABLE evidence ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'AUDIO'")
                db.execSQL("ALTER TABLE evidence ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'audio/mp4'")
            }
        }
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Some v3 installs have these columns without SQL defaults. Room
                // validates the default values as part of the v4 table schema.
                db.execSQL("CREATE TABLE IF NOT EXISTS evidence_v4 (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL, encryptedFile TEXT NOT NULL, latitude REAL, longitude REAL, threatLabel TEXT NOT NULL, threatScore INTEGER NOT NULL, sha256 TEXT NOT NULL, previousHash TEXT, deletedFileHash TEXT, deletedAt INTEGER, incidentId TEXT, mediaType TEXT NOT NULL DEFAULT 'AUDIO', mimeType TEXT NOT NULL DEFAULT 'audio/mp4')")
                db.execSQL("INSERT INTO evidence_v4 (id, createdAt, encryptedFile, latitude, longitude, threatLabel, threatScore, sha256, previousHash, deletedFileHash, deletedAt, incidentId, mediaType, mimeType) SELECT id, createdAt, encryptedFile, latitude, longitude, threatLabel, threatScore, sha256, previousHash, deletedFileHash, deletedAt, incidentId, mediaType, mimeType FROM evidence")
                db.execSQL("DROP TABLE evidence")
                db.execSQL("ALTER TABLE evidence_v4 RENAME TO evidence")
                db.execSQL("CREATE TABLE IF NOT EXISTS sms_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, purpose TEXT NOT NULL, evidenceId INTEGER, number TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sms_outbox_purpose_evidenceId_number ON sms_outbox(purpose, evidenceId, number)")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context, AppDatabase::class.java, "shelter.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { INSTANCE = it }
        }
    }
}
