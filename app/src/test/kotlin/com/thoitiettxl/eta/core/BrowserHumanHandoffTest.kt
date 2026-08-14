package com.thoitiettxl.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHumanHandoffTest {
    @Test
    fun `handoff exposes one in-memory request and resolves explicit continuation`() {
        val ticket = BrowserHumanHandoff.begin(
            title = "Verification",
            prompt = "Complete the challenge",
            targetSelector = "#challenge",
            timeoutMs = 5_000,
            nowMs = 1_000,
        )
        try {
            val request = BrowserHumanHandoff.requests.value
            assertTrue(BrowserHumanHandoff.isActive())
            assertEquals("Verification", request?.title)
            assertEquals("Complete the challenge", request?.prompt)
            assertEquals("#challenge", request?.targetSelector)
            assertEquals(6_000L, request?.deadlineMs)
            assertTrue(BrowserHumanHandoff.resolve(BrowserHumanHandoffOutcome.CONTINUED))
            assertEquals(BrowserHumanHandoffOutcome.CONTINUED, ticket.outcome())
            assertFalse(BrowserHumanHandoff.resolve(BrowserHumanHandoffOutcome.CANCELLED))
        } finally {
            BrowserHumanHandoff.finish(ticket)
        }
        assertFalse(BrowserHumanHandoff.isActive())
        assertNull(BrowserHumanHandoff.requests.value)
    }

    @Test
    fun `finishing a ticket clears prompt state without persisting an outcome payload`() {
        val ticket = BrowserHumanHandoff.begin(
            title = "Input",
            prompt = "Enter the OTP in the page",
            targetSelector = null,
            timeoutMs = 1_000,
            nowMs = 5_000,
        )

        BrowserHumanHandoff.finish(ticket)

        assertNull(ticket.outcome())
        assertNull(BrowserHumanHandoff.requests.value)
        assertFalse(BrowserHumanHandoff.isActive())
    }
}
