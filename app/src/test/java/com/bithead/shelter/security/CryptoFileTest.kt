package com.bithead.shelter.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CryptoFileTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun tamperedCiphertextDoesNotLeaveDecryptedEvidenceBehind() {
        val raw = files.newFile("capture.raw.m4a")
        val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        raw.writeBytes(bytes)
        val encrypted = files.newFile("capture.enc")
        val playback = files.newFile("playback.m4a")
        val key = Crypto.newKey()

        Crypto.encrypt(raw, encrypted, key)
        assertTrue(raw.exists())
        Crypto.decrypt(encrypted, playback, key)
        assertArrayEquals(bytes, playback.readBytes())

        val damaged = encrypted.readBytes()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        encrypted.writeBytes(damaged)
        val failure = runCatching { Crypto.decrypt(encrypted, playback, key) }.exceptionOrNull()
        assertTrue("A modified GCM tag must fail authentication", failure != null)
        assertFalse("Failed decryption must remove the plaintext copy", playback.exists())
        assertArrayEquals("The original raw recording is the recovery source", bytes, raw.readBytes())
    }
}
