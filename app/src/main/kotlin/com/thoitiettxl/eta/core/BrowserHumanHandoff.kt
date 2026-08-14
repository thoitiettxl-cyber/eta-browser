package com.thoitiettxl.eta.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class BrowserHumanHandoffOutcome(val wireName: String) {
    CONTINUED("continued"),
    CANCELLED("cancelled"),
}

internal data class BrowserHumanHandoffRequest(
    val generation: Long,
    val title: String,
    val prompt: String,
    val targetSelector: String?,
    val startedAtMs: Long,
    val deadlineMs: Long,
)

/** In-memory coordination between one blocking request_help action and BrowserActivity. */
internal object BrowserHumanHandoff {
    internal class Ticket(
        val request: BrowserHumanHandoffRequest,
        private val completion: CompletableFuture<BrowserHumanHandoffOutcome>,
    ) {
        fun outcome(): BrowserHumanHandoffOutcome? = completion.getNow(null)
        fun complete(outcome: BrowserHumanHandoffOutcome): Boolean = completion.complete(outcome)
    }

    private val generation = AtomicLong(0L)
    private val mutableRequests = MutableStateFlow<BrowserHumanHandoffRequest?>(null)
    val requests: StateFlow<BrowserHumanHandoffRequest?> = mutableRequests.asStateFlow()

    private var activeTicket: Ticket? = null

    @Synchronized
    fun begin(
        title: String,
        prompt: String,
        targetSelector: String?,
        timeoutMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Ticket {
        check(activeTicket == null) { "human handoff is already active" }
        val request = BrowserHumanHandoffRequest(
            generation = generation.incrementAndGet(),
            title = title,
            prompt = prompt,
            targetSelector = targetSelector,
            startedAtMs = nowMs,
            deadlineMs = nowMs + timeoutMs,
        )
        return Ticket(request, CompletableFuture()).also { ticket ->
            activeTicket = ticket
            mutableRequests.value = request
        }
    }

    @Synchronized
    fun resolve(outcome: BrowserHumanHandoffOutcome): Boolean {
        val ticket = activeTicket ?: return false
        val completed = ticket.complete(outcome)
        return completed
    }

    @Synchronized
    fun finish(ticket: Ticket) {
        if (activeTicket !== ticket) return
        activeTicket = null
        mutableRequests.value = null
    }

    @Synchronized
    fun isActive(): Boolean = activeTicket != null
}
