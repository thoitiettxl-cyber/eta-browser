package com.thoitiettxl.eta.bridge

import java.net.SocketTimeoutException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserBridgeErrorMapperTest {
    @Test
    fun `maps known failures to stable codes`() {
        assertEquals(
            BrowserBridgeError("REQUEST_TOO_LARGE", "too large"),
            BrowserBridgeErrorMapper.from(
                BrowserBridgeRequestFailure("REQUEST_TOO_LARGE", "too large"),
            ),
        )
        assertEquals(
            BrowserBridgeError("STALE_CLIENT", "stale"),
            BrowserBridgeErrorMapper.from(
                BrowserSessionLeaseManager.Failure("STALE_CLIENT", "stale"),
            ),
        )
        assertEquals(
            BrowserBridgeError("INVALID_ARGUMENT", "bad action"),
            BrowserBridgeErrorMapper.from(
                BrowserBridgeFailure("INVALID_ARGUMENT", "bad action"),
            ),
        )
    }

    @Test
    fun `maps socket timeout to a stable request timeout`() {
        assertEquals(
            BrowserBridgeError("REQUEST_TIMEOUT", "Bridge request timed out"),
            BrowserBridgeErrorMapper.from(SocketTimeoutException("secret timeout detail")),
        )
    }

    @Test
    fun `maps malformed json without exposing parser detail`() {
        assertEquals(
            BrowserBridgeError("INVALID_REQUEST", "Invalid bridge request"),
            BrowserBridgeErrorMapper.from(JSONException("secret parser detail")),
        )
    }

    @Test
    fun `maps unexpected exceptions to deterministic internal error`() {
        assertEquals(
            BrowserBridgeError("INTERNAL_ERROR", "Bridge request failed"),
            BrowserBridgeErrorMapper.from(IllegalStateException("sensitive internal detail")),
        )
    }
}
