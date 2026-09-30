package com.bithead.shelter.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class EvidenceChainTest {
    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("evidence", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun chainLinksDependOnPreviousEntry() {
        val first = Crypto.chainHash("aa", null)
        val second = Crypto.chainHash("bb", first)
        assertEquals(64, first.length)
        assertEquals(second, Crypto.chainHash("bb", first))
        assertNotEquals(second, Crypto.chainHash("bb", Crypto.chainHash("ab", null)))
    }

    @Test
    fun flippingOneByteBreaksTheChainAndDecryption() {
        val key = Crypto.newKey()
        val plain = tempFile(ByteArray(4_096) { (it % 251).toByte() })
        val sealed = File.createTempFile("evidence", ".enc").apply { deleteOnExit() }
        Crypto.encrypt(plain, sealed, key)
        val sealedLink = Crypto.chainHash(Crypto.sha256(sealed), null)

        // A one-byte mutation invalidates the authenticated ciphertext.
        RandomAccessFile(sealed, "rw").use { file ->
            file.seek(40L)
            val original = file.read()
            file.seek(40L)
            file.write(original xor 0x01)
        }

        assertNotEquals(sealedLink, Crypto.chainHash(Crypto.sha256(sealed), null))
        val output = File.createTempFile("evidence", ".out").apply { deleteOnExit() }
        assertThrows(Exception::class.java) { Crypto.decrypt(sealed, output, key) }
    }

    @Test
    fun untouchedEvidenceRoundTrips() {
        val key = Crypto.newKey()
        val bytes = ByteArray(10_000) { (it * 7).toByte() }
        val sealed = File.createTempFile("evidence", ".enc").apply { deleteOnExit() }
        Crypto.encrypt(tempFile(bytes), sealed, key)
        val output = File.createTempFile("evidence", ".out").apply { deleteOnExit() }
        Crypto.decrypt(sealed, output, key)
        assertArrayEquals(bytes, output.readBytes())
    }
}
