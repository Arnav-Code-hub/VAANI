package com.bithead.shelter.sensors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleJerkGateTest {
    @Test fun requiresTwoJerksInsideTheWindow() {
        val gate = DoubleJerkGate(1_500L)
        assertFalse(gate.onJerk(1_000L))
        assertTrue(gate.onJerk(2_400L))
        assertFalse(gate.onJerk(3_000L))
    }

    @Test fun lateSecondJerkStartsANewPair() {
        val gate = DoubleJerkGate(1_500L)
        assertFalse(gate.onJerk(1_000L))
        assertFalse(gate.onJerk(2_501L))
        assertTrue(gate.onJerk(3_000L))
    }
}
