package com.thoitiettxl.eta.bridge

import com.thoitiettxl.eta.core.BrowserOperationTicket
import java.util.UUID

/**
 * Owns the single external browser session. A lease id is an opaque generation
 * token: reusing the same client id after release cannot control the new lease.
 */
internal class BrowserSessionLeaseManager(
    private val newLeaseId: () -> String = { UUID.randomUUID().toString() },
) {
    data class Lease(
        val clientId: String,
        val leaseId: String,
        val activeRequestId: String? = null,
    )

    private var current: Lease? = null
    private var activeOperation: BrowserOperationTicket? = null
    private val usedRequestIds = mutableSetOf<String>()

    @Synchronized
    fun acquire(clientId: String): Lease {
        require(clientId.isNotBlank()) { "client_id is required" }
        if (current != null) {
            throw Failure(
                code = "SESSION_BUSY",
                message = "Browser session is leased by another client",
            )
        }
        usedRequestIds.clear()
        activeOperation = null
        return Lease(clientId = clientId, leaseId = newLeaseId()).also { current = it }
    }

    @Synchronized
    fun requireOwner(clientId: String, leaseId: String): Lease =
        requireOwnerLocked(clientId, leaseId)

    @Synchronized
    fun beginOperation(
        clientId: String,
        leaseId: String,
        requestId: String,
        cancellable: Boolean = true,
    ): BrowserOperationTicket {
        val lease = requireOwnerLocked(clientId, leaseId)
        if (requestId.isBlank()) {
            throw Failure("REQUEST_ID_REQUIRED", "Browser operations require a request id")
        }
        if (requestId in usedRequestIds) {
            throw Failure(
                code = "DUPLICATE_REQUEST",
                message = "The browser request id was already used by this lease",
            )
        }
        if (activeOperation != null) {
            throw Failure(
                code = "SESSION_BUSY",
                message = "The leased browser session already has an active request",
            )
        }
        return BrowserOperationTicket(clientId, leaseId, requestId, cancellable).also { operation ->
            usedRequestIds += requestId
            activeOperation = operation
            current = lease.copy(activeRequestId = requestId)
        }
    }

    @Synchronized
    fun completeOperation(operation: BrowserOperationTicket) {
        if (activeOperation !== operation) return
        operation.complete()
        activeOperation = null
        current = current?.copy(activeRequestId = null)
    }

    @Synchronized
    fun requireCancellable(
        clientId: String,
        leaseId: String,
        requestId: String,
    ): BrowserOperationTicket {
        requireOwnerLocked(clientId, leaseId)
        val operation = activeOperation
        if (operation == null || operation.requestId != requestId) {
            throw Failure(
                code = "STALE_REQUEST",
                message = "The target browser request is not active for this lease",
            )
        }
        if (!operation.cancellable) {
            throw Failure(
                code = "REQUEST_NOT_CANCELLABLE",
                message = "The target browser request cannot be cancelled",
            )
        }
        return operation
    }

    @Synchronized
    fun release(clientId: String, leaseId: String): Lease {
        val lease = requireOwnerLocked(clientId, leaseId)
        if (activeOperation != null) {
            throw Failure(
                code = "SESSION_BUSY",
                message = "Cancel or wait for the active browser request before releasing the lease",
            )
        }
        current = null
        usedRequestIds.clear()
        return lease
    }

    @Synchronized
    fun clear(): BrowserOperationTicket? = activeOperation.also {
        current = null
        activeOperation = null
        usedRequestIds.clear()
    }

    @Synchronized
    fun snapshot(): Lease? = current

    private fun requireOwnerLocked(clientId: String, leaseId: String): Lease {
        if (clientId.isBlank() || leaseId.isBlank()) {
            throw Failure(
                code = "SESSION_REQUIRED",
                message = "client_id and lease_id are required",
            )
        }
        val lease = current ?: throw Failure(
            code = "SESSION_REQUIRED",
            message = "Acquire the browser session before controlling it",
        )
        if (lease.clientId != clientId || lease.leaseId != leaseId) {
            throw Failure(
                code = "STALE_CLIENT",
                message = "The browser session lease is stale or owned by another client",
            )
        }
        return lease
    }

    class Failure(
        val code: String,
        override val message: String,
    ) : RuntimeException(message)
}
