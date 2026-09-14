package com.bithead.shelter.sensors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JerkTriggerGateTest {
    @Test
    fun triggersOnlyOnRisingThresholdCrossingAndHonorsCooldown() {
        val gate = JerkTriggerGate(thresholdG = 1.8f, cooldownMs = 2_500L)

        assertFalse(gate.onSample(1.79f, 1_000L))
        assertTrue(gate.onSample(1.8f, 1_100L))
        assertFalse(gate.onSample(2.2f, 1_200L))
        assertFalse(gate.onSample(1.0f, 1_300L))
        assertFalse(gate.onSample(2.0f, 2_000L))
        assertFalse(gate.onSample(1.0f, 2_100L))
        assertTrue(gate.onSample(1.9f, 3_600L))
    }

    @Test
    fun resetAllowsImmediateTrigger() {
        val gate = JerkTriggerGate()

        assertTrue(gate.onSample(2.0f, 10_000L))
        gate.reset()
        assertTrue(gate.onSample(2.0f, 10_100L))
    }
}
