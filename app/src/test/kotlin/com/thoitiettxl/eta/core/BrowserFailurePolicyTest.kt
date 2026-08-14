package com.thoitiettxl.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserFailurePolicyTest {
    @Test
    fun `maps cancellation timeout blocked and generic errors deterministically`() {
        assertEquals(
            "cancelled",
            BrowserFailurePolicy.map(BrowserSessionFailure("CANCELLED", "cancelled")).status,
        )
        assertEquals(
            "timeout",
            BrowserFailurePolicy.map(
                BrowserSessionFailure("NAVIGATION_TIMEOUT", "timed out"),
            ).status,
        )
        assertEquals(
            "blocked",
            BrowserFailurePolicy.map(
                BrowserSessionFailure("USER_CONTROL_ACTIVE", "blocked"),
            ).status,
        )
        assertEquals(
            "error",
            BrowserFailurePolicy.map(BrowserSessionFailure("NO_PAGE", "no page")).status,
        )
    }

    @Test
    fun `unexpected exceptions do not expose their messages`() {
        val mapping = BrowserFailurePolicy.map(
            IllegalStateException("sensitive implementation detail"),
        )

        assertEquals("BROWSER_ERROR", mapping.code)
        assertEquals("error", mapping.status)
        assertFalse(mapping.message.contains("sensitive"))
    }
}
