package com.thoitiettxl.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserDiagnosticsTest {
    @Before
    fun clear() {
        BrowserDiagnostics.clear()
    }

    @Test
    fun `network urls discard credentials query fragments and non-http contents`() {
        assertEquals(
            "https://example.com/api/items",
            BrowserDiagnostics.sanitizeUrl(
                "https://user:secret@example.com/api/items?token=secret#private",
            ),
        )
        assertEquals("file:[redacted]", BrowserDiagnostics.sanitizeUrl("file:///private/token.txt"))
        assertEquals("data:[redacted]", BrowserDiagnostics.sanitizeUrl("data:text/plain,secret"))
        assertEquals("about:blank", BrowserDiagnostics.sanitizeUrl("about:blank"))
        assertNull(BrowserDiagnostics.sanitizeUrl(null))
    }

    @Test
    fun `diagnostic snapshots are cursor based bounded and omit forbidden payload fields`() {
        repeat(205) { index ->
            BrowserDiagnostics.recordConsole(
                level = "LOG",
                message = "message-$index" + "x".repeat(2_000),
                source = "https://example.com/app.js?token=secret",
                line = index,
            )
            BrowserDiagnostics.recordNetworkRequest(
                method = "GET",
                url = "https://example.com/api/$index?authorization=secret",
                mainFrame = false,
            )
        }

        val console = BrowserDiagnostics.consoleSnapshot(since = 0, limit = 10)
        val network = BrowserDiagnostics.networkSnapshot(since = 0, limit = 10)
        val serialized = network.toString()

        assertEquals(10, console.getInt("count"))
        assertTrue(console.getBoolean("truncated"))
        assertTrue(console.getInt("dropped_before") >= 5)
        assertTrue(console.getJSONArray("entries").getJSONObject(0).getString("message").length <= 1_000)
        assertEquals(10, network.getInt("count"))
        assertFalse(network.getBoolean("complete_response_trace"))
        assertFalse(network.getBoolean("captures_headers"))
        assertFalse(network.getBoolean("captures_bodies"))
        assertFalse(serialized.contains("authorization"))
        assertFalse(serialized.contains("secret"))
        assertFalse(serialized.contains("\"headers\":"))
        assertFalse(serialized.contains("\"body\":"))
    }

    @Test
    fun `reset clear removes entries and restarts cursors`() {
        BrowserDiagnostics.recordConsole("warning", "first", null, 1)
        BrowserDiagnostics.recordNetworkFailure("GET", "https://example.com", true, -2, "failed")
        BrowserDiagnostics.clear()

        assertEquals(0, BrowserDiagnostics.consoleSnapshot(0, 50).getInt("latest_seq"))
        assertEquals(0, BrowserDiagnostics.networkSnapshot(0, 50).getInt("latest_seq"))
    }
}
