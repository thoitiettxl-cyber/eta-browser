package com.thoitiettxl.eta.bridge

import com.thoitiettxl.eta.core.BrowserActionContract
import org.json.JSONArray
import org.json.JSONObject

/** Authenticated protocol and lease semantics, independent from socket/frame I/O. */
internal class BrowserBridgeProtocol(
    pairingStore: BrowserPairingCredentials,
    private val browserController: BrowserBridgeController,
    private val leaseManager: BrowserSessionLeaseManager = BrowserSessionLeaseManager(),
    private val endpoint: String =
        "${BrowserBridgeContract.LOOPBACK_HOST}:${BrowserBridgeContract.FIXED_PORT}",
) {
    data class Result(
        val payload: JSONObject,
        val stopBridgeAfterReply: Boolean = false,
    )

    private val dispatch = BrowserBridgeDispatch(pairingStore)

    fun process(request: JSONObject, id: String?): Result {
        dispatch.requireAuthenticated(request.optString("token"))
        val method = request.optString("method").trim()
        val clientId = request.optString("client_id").trim().take(MAX_ID_CHARS).let { value ->
            if (value.isNotBlank()) value
            else if (method.startsWith("browser.")) {
                throw BrowserBridgeFailure("CLIENT_ID_REQUIRED", "client_id is required")
            } else {
                "anonymous"
            }
        }
        BrowserBridgeRuntime.clientConnected(clientId)
        var stopBridgeAfterReply = false
        val result = try {
            when (method) {
                "health" -> healthResult()
                "pairing.rotate" -> {
                    if (leaseManager.snapshot() != null) {
                        throw BrowserBridgeFailure(
                            "SESSION_BUSY",
                            "Release the browser session before rotating credentials",
                        )
                    }
                    val pairing = dispatch.rotate()
                    stopBridgeAfterReply = pairing.stopBridgeAfterReply
                    pairing.response
                }
                "pairing.revoke" -> {
                    val pairing = dispatch.revoke()
                    clearLeaseAndInterrupt()
                    stopBridgeAfterReply = pairing.stopBridgeAfterReply
                    pairing.response
                }
                "browser.session.acquire" -> {
                    val lease = leaseManager.acquire(clientId)
                    JSONObject()
                        .put("lease_id", lease.leaseId)
                        .put("client_id", lease.clientId)
                }
                "browser.session.release" -> {
                    leaseManager.release(clientId, requiredLeaseId(request))
                    JSONObject().put("released", true)
                }
                "browser.execute" -> execute(request, id, clientId)
                "browser.stop" -> cancel(request, clientId)
                "browser.reset" -> reset(request, id, clientId)
                else -> throw BrowserBridgeFailure(
                    "UNKNOWN_METHOD",
                    "Unsupported bridge method: ${method.take(80)}",
                )
            }
        } finally {
            BrowserBridgeRuntime.clientDisconnected()
        }
        return Result(result, stopBridgeAfterReply)
    }

    fun health(): JSONObject = healthResult()

    fun close() {
        clearLeaseAndInterrupt()
    }

    private fun execute(request: JSONObject, id: String?, clientId: String): JSONObject {
        val params = request.optJSONObject("params")
            ?: throw BrowserBridgeFailure("INVALID_ARGUMENT", "browser.execute requires params")
        BrowserActionContract.validate(params)?.let { issue ->
            throw BrowserBridgeFailure("INVALID_ARGUMENT", issue.message)
        }
        val requestId = id ?: throw BrowserBridgeFailure(
            "REQUEST_ID_REQUIRED",
            "browser.execute requires id",
        )
        val operation = leaseManager.beginOperation(
            clientId = clientId,
            leaseId = requiredLeaseId(request),
            requestId = requestId,
        )
        return try {
            val result = browserController.execute(params, operation)
            JSONObject()
                .put("browser", JSONObject(result.content))
                .put(
                    "images",
                    JSONArray().also { images ->
                        result.images.forEach { image ->
                            images.put(
                                JSONObject()
                                    .put("data_url", image.dataUrl)
                                    .put("mime_type", image.mimeType)
                                    .put("bytes", image.bytes)
                                    .put("width", image.width)
                                    .put("height", image.height)
                            )
                        }
                    },
                )
        } finally {
            leaseManager.completeOperation(operation)
        }
    }

    private fun cancel(request: JSONObject, clientId: String): JSONObject {
        val targetRequestId = request.optString("request_id").trim().take(MAX_ID_CHARS)
        if (targetRequestId.isBlank()) {
            throw BrowserBridgeFailure("REQUEST_ID_REQUIRED", "browser.stop requires request_id")
        }
        val operation = leaseManager.requireCancellable(
            clientId = clientId,
            leaseId = requiredLeaseId(request),
            requestId = targetRequestId,
        )
        val cancelAccepted = operation.cancel()
        val interruptDelivered = if (cancelAccepted) {
            browserController.interrupt(operation)
        } else {
            false
        }
        return JSONObject()
            .put("stopped", cancelAccepted)
            .put("request_id", targetRequestId)
            .put("interrupt_delivered", interruptDelivered)
    }

    private fun reset(request: JSONObject, id: String?, clientId: String): JSONObject {
        val requestId = id ?: throw BrowserBridgeFailure(
            "REQUEST_ID_REQUIRED",
            "browser.reset requires id",
        )
        val operation = leaseManager.beginOperation(
            clientId = clientId,
            leaseId = requiredLeaseId(request),
            requestId = requestId,
            cancellable = false,
        )
        return try {
            val result = browserController.reset(operation)
            JSONObject()
                .put("browser", JSONObject(result.content))
                .put("images", JSONArray())
        } finally {
            leaseManager.completeOperation(operation)
        }
    }

    private fun healthResult(): JSONObject {
        val browser = browserController.snapshot()
        val bridge = BrowserBridgeRuntime.snapshots.value
        val lease = leaseManager.snapshot()
        return JSONObject()
            .put("service", "eta-browser-bridge")
            .put("protocol", BrowserBridgeContract.PROTOCOL_VERSION)
            .put("endpoint", endpoint)
            .put("active_clients", bridge.activeClients)
            .put("last_client_id", bridge.lastClientId)
            .put("session_leased", lease != null)
            .put("session_owner", lease?.clientId ?: JSONObject.NULL)
            .put("active_request_id", lease?.activeRequestId ?: JSONObject.NULL)
            .put("browser_available", browser.available)
            .put("host", browser.host)
            .put("title", browser.title)
            .put("is_loading", browser.isLoading)
            .put("is_user_controlling", browser.isUserControlling)
            .put("human_handoff_pending", browser.isHumanHandoffPending)
    }

    private fun requiredLeaseId(request: JSONObject): String {
        val leaseId = request.optString("lease_id").trim().take(MAX_ID_CHARS)
        if (leaseId.isBlank()) {
            throw BrowserBridgeFailure(
                "SESSION_REQUIRED",
                "lease_id is required; acquire the browser session first",
            )
        }
        return leaseId
    }

    private fun clearLeaseAndInterrupt() {
        leaseManager.clear()?.let { operation ->
            operation.cancel()
            browserController.interrupt(operation)
        }
    }

    private companion object {
        const val MAX_ID_CHARS = 128
    }
}
