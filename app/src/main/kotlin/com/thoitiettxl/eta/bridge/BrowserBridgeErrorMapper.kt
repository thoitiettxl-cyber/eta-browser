package com.thoitiettxl.eta.bridge

import java.net.SocketTimeoutException
import org.json.JSONException

internal data class BrowserBridgeError(
    val code: String,
    val message: String,
)

/** Keeps transport errors deterministic and prevents exception details leaking over the bridge. */
internal object BrowserBridgeErrorMapper {
    fun from(throwable: Throwable): BrowserBridgeError = when (throwable) {
        is BrowserBridgeRequestFailure -> BrowserBridgeError(throwable.code, throwable.message)
        is BrowserBridgeDispatch.Failure -> BrowserBridgeError(throwable.code, throwable.message)
        is BrowserSessionLeaseManager.Failure -> BrowserBridgeError(throwable.code, throwable.message)
        is BrowserBridgeFailure -> BrowserBridgeError(throwable.code, throwable.message)
        is SocketTimeoutException -> BrowserBridgeError(
            "REQUEST_TIMEOUT",
            "Bridge request timed out",
        )
        is JSONException -> BrowserBridgeError("INVALID_REQUEST", "Invalid bridge request")
        else -> BrowserBridgeError("INTERNAL_ERROR", "Bridge request failed")
    }
}

internal class BrowserBridgeFailure(
    val code: String,
    override val message: String,
) : RuntimeException(message)
