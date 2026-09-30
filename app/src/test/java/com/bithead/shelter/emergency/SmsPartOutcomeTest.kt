package com.bithead.shelter.emergency

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsPartOutcomeTest {
    @Test fun multipartMessageIsDeliveredOnlyAfterEveryDeliveryCallback() {
        assertEquals("PENDING", summarizeSmsParts(2, setOf("0"), emptySet(), emptySet()).stored)
        assertEquals("SENT", summarizeSmsParts(2, setOf("0", "1"), emptySet(), emptySet()).stored)
        assertEquals("SENT", summarizeSmsParts(2, setOf("0", "1"), setOf("1"), emptySet()).stored)
        assertEquals("DELIVERED", summarizeSmsParts(2, setOf("0", "1"), setOf("0", "1"), emptySet()).stored)
    }

    @Test fun failedPartCannotBeReportedAsDelivered() {
        val sendFailure = summarizeSmsParts(2, setOf("0"), setOf("0"), setOf("send:1:4"))
        assertEquals("FAILED", sendFailure.stored)
        assertEquals("Send failed (4)", sendFailure.display)
        val deliveryFailure = summarizeSmsParts(2, setOf("0", "1"), setOf("0"), setOf("delivery:1:2"))
        assertEquals("DELIVERY_FAILED", deliveryFailure.stored)
        assertEquals("Delivery failed (2)", deliveryFailure.display)
        assertEquals("DELIVERY_FAILED", summarizeSmsParts(2, setOf("0", "1"),
            setOf("0", "1"), setOf("delivery:1:2")).stored)
    }
}
