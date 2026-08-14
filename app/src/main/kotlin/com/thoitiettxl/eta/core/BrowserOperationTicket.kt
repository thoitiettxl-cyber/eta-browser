package com.thoitiettxl.eta.core

/** Exact operation identity shared by lease admission and the WebView engine. */
internal class BrowserOperationTicket(
    val clientId: String,
    val leaseId: String,
    val requestId: String,
    val cancellable: Boolean = true,
) {
    private var state: State = State.PENDING

    @Synchronized
    fun tryStart(): Boolean = when (state) {
        State.PENDING -> {
            state = State.RUNNING
            true
        }
        State.RUNNING -> false
        State.CANCELLED, State.COMPLETED -> false
    }

    @Synchronized
    fun cancel(): Boolean {
        if (!cancellable || state == State.COMPLETED) return false
        state = State.CANCELLED
        return true
    }

    @Synchronized
    fun complete() {
        state = State.COMPLETED
    }

    @Synchronized
    fun isCancelled(): Boolean = state == State.CANCELLED

    @Synchronized
    fun isCompleted(): Boolean = state == State.COMPLETED

    private enum class State {
        PENDING,
        RUNNING,
        CANCELLED,
        COMPLETED,
    }
}
