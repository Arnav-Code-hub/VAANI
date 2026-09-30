package com.bithead.shelter.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.DriverManager

class Migration34Test {
    @Test fun preservesVaultRowsAndAddsRoomDefaults() {
        for (oldDefaults in listOf(false, true)) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
                val mediaColumns = if (oldDefaults) {
                    "mediaType TEXT NOT NULL DEFAULT 'AUDIO', mimeType TEXT NOT NULL DEFAULT 'audio/mp4'"
                } else {
                    "mediaType TEXT NOT NULL, mimeType TEXT NOT NULL"
                }
                connection.createStatement().use { sql ->
                    sql.execute("CREATE TABLE evidence (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL, encryptedFile TEXT NOT NULL, latitude REAL, longitude REAL, threatLabel TEXT NOT NULL, threatScore INTEGER NOT NULL, sha256 TEXT NOT NULL, previousHash TEXT, deletedFileHash TEXT, deletedAt INTEGER, incidentId TEXT, $mediaColumns)")
                    sql.execute("INSERT INTO evidence VALUES (7, 1000, 'evidence_1000.enc', NULL, NULL, 'Safe', 0, 'chain-7', 'chain-6', NULL, NULL, 'incident-1', 'AUDIO', 'audio/mp4')")
                }
                val db = Proxy.newProxyInstance(
                    SupportSQLiteDatabase::class.java.classLoader,
                    arrayOf(SupportSQLiteDatabase::class.java)
                ) { _, method, args ->
                    if (method.name != "execSQL") error("Unexpected database call: ${method.name}")
                    connection.createStatement().use { it.execute(args!![0] as String) }
                    Unit
                } as SupportSQLiteDatabase

                AppDatabase.MIGRATION_3_4.migrate(db)

                connection.createStatement().use { sql ->
                    sql.executeQuery("SELECT id, encryptedFile, sha256, previousHash, incidentId FROM evidence").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(7L, rows.getLong(1))
                        assertEquals("evidence_1000.enc", rows.getString(2))
                        assertEquals("chain-7", rows.getString(3))
                        assertEquals("chain-6", rows.getString(4))
                        assertEquals("incident-1", rows.getString(5))
                    }
                    sql.executeQuery("PRAGMA table_info(evidence)").use { columns ->
                        val defaults = mutableMapOf<String, String?>()
                        while (columns.next()) defaults[columns.getString("name")] = columns.getString("dflt_value")
                        assertEquals("'AUDIO'", defaults["mediaType"])
                        assertEquals("'audio/mp4'", defaults["mimeType"])
                    }
                    sql.executeQuery("SELECT COUNT(*) FROM sms_outbox").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
        }
    }
}
