package com.thoitiettxl.eta.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionLeaseManagerTest {
    @Test
    fun `only one client can own the browser session`() {
        val manager = manager()
        val first = manager.acquire("client-a")

        val busy = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.acquire("client-b")
        }

        assertEquals("lease-1", first.leaseId)
        assertEquals("SESSION_BUSY", busy.code)
        assertEquals("client-a", manager.snapshot()?.clientId)
    }

    @Test
    fun `released lease stays stale even when client id is reused`() {
        val manager = manager()
        val oldLease = manager.acquire("same-client")
        manager.release("same-client", oldLease.leaseId)
        val newLease = manager.acquire("same-client")

        val stale = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.requireOwner("same-client", oldLease.leaseId)
        }

        assertEquals("lease-2", newLease.leaseId)
        assertEquals("STALE_CLIENT", stale.code)
    }

    @Test
    fun `only the exact active request can be cancelled`() {
        val manager = manager()
        val lease = manager.acquire("client-a")
        val operation = manager.beginOperation("client-a", lease.leaseId, "request-1")

        val wrongClient = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.requireCancellable("client-b", lease.leaseId, "request-1")
        }
        val wrongRequest = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.requireCancellable("client-a", lease.leaseId, "request-2")
        }

        assertEquals("STALE_CLIENT", wrongClient.code)
        assertEquals("STALE_REQUEST", wrongRequest.code)
        assertTrue(manager.requireCancellable("client-a", lease.leaseId, "request-1") === operation)
    }

    @Test
    fun `cancelled operation cannot be confused with a later request`() {
        val manager = manager()
        val lease = manager.acquire("client-a")
        val first = manager.beginOperation("client-a", lease.leaseId, "request-1")
        first.cancel()
        manager.completeOperation(first)
        val second = manager.beginOperation("client-a", lease.leaseId, "request-2")

        val stale = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.requireCancellable("client-a", lease.leaseId, "request-1")
        }

        assertFalse(first.isCancelled())
        assertTrue(first.isCompleted())
        assertFalse(second.isCancelled())
        assertEquals("STALE_REQUEST", stale.code)
        assertTrue(manager.requireCancellable("client-a", lease.leaseId, "request-2") === second)
    }

    @Test
    fun `operation ticket starts only once`() {
        val manager = manager()
        val lease = manager.acquire("client-a")
        val operation = manager.beginOperation("client-a", lease.leaseId, "request-1")

        assertTrue(operation.tryStart())
        assertFalse(operation.tryStart())
    }

    @Test
    fun `cancellation before engine start prevents stale work from starting`() {
        val manager = manager()
        val lease = manager.acquire("client-a")
        val operation = manager.beginOperation("client-a", lease.leaseId, "request-1")

        assertTrue(operation.cancel())
        assertFalse(operation.tryStart())
    }

    @Test
    fun `duplicate request ids are rejected for the same lease`() {
        val manager = manager()
        val lease = manager.acquire("client-a")
        val first = manager.beginOperation("client-a", lease.leaseId, "request-1")
        manager.completeOperation(first)

        val duplicate = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.beginOperation("client-a", lease.leaseId, "request-1")
        }

        assertEquals("DUPLICATE_REQUEST", duplicate.code)
    }

    @Test
    fun `reset style operation cannot be cancelled`() {
        val manager = manager()
        val lease = manager.acquire("client-a")
        manager.beginOperation(
            clientId = "client-a",
            leaseId = lease.leaseId,
            requestId = "reset-1",
            cancellable = false,
        )

        val failure = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.requireCancellable("client-a", lease.leaseId, "reset-1")
        }

        assertEquals("REQUEST_NOT_CANCELLABLE", failure.code)
    }

    @Test
    fun `same lease cannot interleave operations`() {
        val manager = manager()
        val lease = manager.acquire("client-a")
        manager.beginOperation("client-a", lease.leaseId, "request-1")

        val failure = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.beginOperation("client-a", lease.leaseId, "request-2")
        }

        assertEquals("SESSION_BUSY", failure.code)
    }

    @Test
    fun `lease cannot be released while work is active`() {
        val manager = manager()
        val lease = manager.acquire("client-a")
        val operation = manager.beginOperation("client-a", lease.leaseId, "request-1")

        val failure = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            manager.release("client-a", lease.leaseId)
        }

        assertEquals("SESSION_BUSY", failure.code)
        manager.completeOperation(operation)
        manager.release("client-a", lease.leaseId)
        assertEquals(null, manager.snapshot())
    }

    private fun manager(): BrowserSessionLeaseManager {
        var next = 0
        return BrowserSessionLeaseManager { "lease-${++next}" }
    }
}
