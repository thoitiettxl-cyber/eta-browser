package com.thoitiettxl.eta.bridge

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBridgeDispatchTest {
    @Test
    fun `rotate invalidates old credential immediately`() {
        val store = InMemoryPairingCredentials()
        val oldToken = store.pair()
        val dispatch = BrowserBridgeDispatch(store)

        val rotated = dispatch.rotate()
        val newToken = rotated.response.getString("token")

        assertTrue(rotated.response.getBoolean("rotated"))
        assertFalse(rotated.stopBridgeAfterReply)
        assertFalse(oldToken == newToken)
        assertEquals(newToken, store.token())
        assertThrows(BrowserBridgeDispatch.Failure::class.java) {
            dispatch.requireAuthenticated(oldToken)
        }
        dispatch.requireAuthenticated(newToken)
    }

    @Test
    fun `revoke clears credential and requests bridge shutdown`() {
        val store = InMemoryPairingCredentials()
        val token = store.pair()
        val dispatch = BrowserBridgeDispatch(store)

        val revoked = dispatch.revoke()

        assertTrue(revoked.response.getBoolean("revoked"))
        assertTrue(revoked.stopBridgeAfterReply)
        assertEquals(null, store.token())
        val failure = assertThrows(BrowserBridgeDispatch.Failure::class.java) {
            dispatch.requireAuthenticated(token)
        }
        assertEquals("PAIRING_REQUIRED", failure.code)
    }

    @Test
    fun `authentication rejects the wrong token without exposing expected token`() {
        val store = InMemoryPairingCredentials()
        val token = store.pair()
        val dispatch = BrowserBridgeDispatch(store)

        val failure = assertThrows(BrowserBridgeDispatch.Failure::class.java) {
            dispatch.requireAuthenticated("wrong-token")
        }

        assertEquals("UNAUTHORIZED", failure.code)
        assertFalse(failure.message.contains(token))
    }

    private class InMemoryPairingCredentials : BrowserPairingCredentials {
        private var currentToken: String? = null

        override fun token(): String? = currentToken

        fun pair(): String = currentToken ?: newToken().also { currentToken = it }

        override fun rotate(): String {
            check(currentToken != null)
            return newToken().also { currentToken = it }
        }

        override fun revoke() {
            currentToken = null
        }

        private fun newToken(): String = UUID.randomUUID().toString()
    }
}
