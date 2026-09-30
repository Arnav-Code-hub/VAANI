package com.bithead.shelter.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Arrays

class UnlockCodeTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun codesAreNormalizedForPhoneKeyboards() {
        assertEquals("sunflower", AppDisguiseManager.normalizeCode("  SunFlower "))
    }

    @Test
    fun sameCodeAndSaltGiveTheSameHash() {
        val first = AppDisguiseManager.hashUnlockCode(salt, "sunflower")
        val second = AppDisguiseManager.hashUnlockCode(salt, "sunflower")
        assertArrayEquals(first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun differentCodeOrSaltGivesDifferentHash() {
        val base = AppDisguiseManager.hashUnlockCode(salt, "sunflower")
        assertFalse(Arrays.equals(base, AppDisguiseManager.hashUnlockCode(salt, "sunflowers")))
        assertFalse(Arrays.equals(base, AppDisguiseManager.hashUnlockCode(ByteArray(16), "sunflower")))
    }

    @Test
    fun oldHardCodedPasscodesAreTooShortOrNotStored() {
        // "##" and "911" can no longer unlock: codes need 4+ characters and are user-chosen.
        assertEquals(true, AppDisguiseManager.normalizeCode("911").length < AppDisguiseManager.MIN_CODE_LENGTH)
    }
}
