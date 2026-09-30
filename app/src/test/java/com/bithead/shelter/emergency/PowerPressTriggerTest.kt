package com.bithead.shelter.emergency

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPressTriggerTest {
    @Test
    fun threePressesInsideWindowTrigger() {
        val trigger = PowerPressTrigger(presses = 3, windowMs = 4_000L)
        assertFalse(trigger.onScreenToggle(0L))
        assertFalse(trigger.onScreenToggle(900L))
        assertTrue(trigger.onScreenToggle(1_800L))
    }

    @Test
    fun slowPressesDoNotTrigger() {
        val trigger = PowerPressTrigger(presses = 3, windowMs = 4_000L)
        assertFalse(trigger.onScreenToggle(0L))
        assertFalse(trigger.onScreenToggle(3_000L))
        // The first press has expired, so only two remain in the window.
        assertFalse(trigger.onScreenToggle(6_000L))
    }

    @Test
    fun triggerResetsAfterFiring() {
        val trigger = PowerPressTrigger(presses = 3, windowMs = 4_000L)
        trigger.onScreenToggle(0L)
        trigger.onScreenToggle(500L)
        assertTrue(trigger.onScreenToggle(1_000L))
        // A fourth press right after firing must not start a second recording.
        assertFalse(trigger.onScreenToggle(1_200L))
    }
}
