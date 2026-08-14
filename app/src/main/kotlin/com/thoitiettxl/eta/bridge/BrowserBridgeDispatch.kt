package com.thoitiettxl.eta.bridge

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

internal class BrowserBridgeDispatch(
    private val pairingStore: BrowserPairingCredentials,
) {
    data class PairingResult(
        val response: JSONObject,
        val stopBridgeAfterReply: Boolean,
    )

    fun requireAuthenticated(provided: String) {
        val expected = pairingStore.token()
            ?: throw Failure("PAIRING_REQUIRED", "No paired browser credential is active")
        val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
        val providedBytes = provided.toByteArray(StandardCharsets.UTF_8)
        if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
            throw Failure("UNAUTHORIZED", "Bridge authentication failed")
        }
    }

    fun rotate(): PairingResult {
        val token = pairingStore.rotate()
        BrowserBridgeRuntime.updateToken(token)
        return PairingResult(
            response = JSONObject()
                .put("rotated", true)
                .put("token", token),
            stopBridgeAfterReply = false,
        )
    }

    fun revoke(): PairingResult {
        pairingStore.revoke()
        BrowserBridgeRuntime.pairingRevoked()
        return PairingResult(
            response = JSONObject().put("revoked", true),
            stopBridgeAfterReply = true,
        )
    }

    class Failure(
        val code: String,
        override val message: String,
    ) : RuntimeException(message)
}
