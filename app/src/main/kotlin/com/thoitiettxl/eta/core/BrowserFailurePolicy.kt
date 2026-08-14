package com.thoitiettxl.eta.core

internal data class BrowserFailureMapping(
    val code: String,
    val status: String,
    val message: String,
)

/** Stable browser-result status mapping shared by all engine failure paths. */
internal object BrowserFailurePolicy {
    fun map(throwable: Throwable): BrowserFailureMapping {
        val failure = throwable as? BrowserSessionFailure
        val code = failure?.code ?: "BROWSER_ERROR"
        return BrowserFailureMapping(
            code = code,
            status = failure?.status ?: defaultStatus(code),
            message = failure?.message ?: "浏览器操作失败",
        )
    }

    fun defaultStatus(code: String): String = when (code) {
        "CANCELLED", "NAVIGATION_SUPERSEDED" -> "cancelled"
        "NAVIGATION_TIMEOUT", "ACTION_TIMEOUT", "SCRIPT_TIMEOUT", "MAIN_THREAD_TIMEOUT" ->
            "timeout"
        "USER_CONTROL_ACTIVE" -> "blocked"
        else -> "error"
    }
}

internal class BrowserSessionFailure(
    val code: String,
    override val message: String,
    val status: String = BrowserFailurePolicy.defaultStatus(code),
) : RuntimeException(message)
