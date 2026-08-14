package com.thoitiettxl.eta.bridge

import com.thoitiettxl.eta.core.BrowserOperationTicket
import com.thoitiettxl.eta.core.BrowserSessionSnapshot
import com.thoitiettxl.eta.core.BrowserToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBridgeProtocolTest {
    @Test
    fun `health reports protocol and idle lease state`() {
        val fixture = fixture()

        val health = fixture.process("health").payload

        assertEquals(2, health.getInt("protocol"))
        assertFalse(health.getBoolean("session_leased"))
    }

    @Test
    fun `execution requires a lease and valid action arguments`() {
        val fixture = fixture()
        val noLease = assertThrows(BrowserBridgeFailure::class.java) {
            fixture.process(
                method = "browser.execute",
                extra = JSONObject().put(
                    "params",
                    JSONObject().put("action", "get_page_info"),
                ),
            )
        }
        assertEquals("SESSION_REQUIRED", noLease.code)

        val lease = fixture.acquire("client-a")
        val invalid = assertThrows(BrowserBridgeFailure::class.java) {
            fixture.process(
                method = "browser.execute",
                clientId = "client-a",
                extra = JSONObject()
                    .put("lease_id", lease)
                    .put("params", JSONObject().put("action", "navigate")),
            )
        }

        assertEquals("INVALID_ARGUMENT", invalid.code)
        assertFalse(fixture.controller.executeCalled)
    }

    @Test
    fun `single client lease rejects competitors and stale generations`() {
        val fixture = fixture()
        val first = fixture.acquire("client-a")
        val competitor = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            fixture.acquire("client-b")
        }
        assertEquals("SESSION_BUSY", competitor.code)

        val staleRelease = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            fixture.process(
                method = "browser.session.release",
                clientId = "client-a",
                extra = JSONObject().put("lease_id", "stale-lease"),
            )
        }
        assertEquals("STALE_CLIENT", staleRelease.code)

        fixture.release("client-a", first)
        val second = fixture.acquire("client-a")
        assertNotEquals(first, second)
    }

    @Test
    fun `same lease cannot interleave and cancel is exact-client exact-request scoped`() {
        val fixture = fixture()
        val lease = fixture.acquire("client-a")
        fixture.controller.blockExecute = true
        val result = arrayOfNulls<BrowserBridgeProtocol.Result>(1)
        val execution = Thread {
            result[0] = fixture.process(
                method = "browser.execute",
                clientId = "client-a",
                id = "wait-request",
                extra = JSONObject()
                    .put("lease_id", lease)
                    .put(
                        "params",
                        JSONObject()
                            .put("action", "navigate")
                            .put("url", "https://example.invalid"),
                    ),
            )
        }.apply { start() }
        assertTrue(fixture.controller.awaitExecuteStarted())

        val interleaving = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            fixture.process(
                method = "browser.execute",
                clientId = "client-a",
                id = "interleaving-request",
                extra = JSONObject()
                    .put("lease_id", lease)
                    .put("params", JSONObject().put("action", "get_page_info")),
            )
        }
        val wrongClient = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            fixture.cancel("client-b", lease, "wait-request")
        }
        val wrongRequest = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            fixture.cancel("client-a", lease, "other-request")
        }
        val cancelled = fixture.cancel("client-a", lease, "wait-request").payload

        assertEquals("SESSION_BUSY", interleaving.code)
        assertEquals("STALE_CLIENT", wrongClient.code)
        assertEquals("STALE_REQUEST", wrongRequest.code)
        assertTrue(cancelled.getBoolean("stopped"))
        assertTrue(cancelled.getBoolean("interrupt_delivered"))

        fixture.controller.releaseExecute()
        execution.join(5_000)
        assertFalse(execution.isAlive)
        val browser = result[0]!!.payload.getJSONObject("browser")
        assertFalse(browser.getBoolean("ok"))
        assertEquals("CANCELLED", browser.getString("code"))
        assertEquals("cancelled", browser.getString("status"))
    }

    @Test
    fun `reset is leased and non cancellable`() {
        val fixture = fixture()
        val lease = fixture.acquire("client-a")
        fixture.controller.blockReset = true
        val result = arrayOfNulls<BrowserBridgeProtocol.Result>(1)
        val reset = Thread {
            result[0] = fixture.process(
                method = "browser.reset",
                clientId = "client-a",
                id = "reset-request",
                extra = JSONObject().put("lease_id", lease),
            )
        }.apply { start() }
        assertTrue(fixture.controller.awaitResetStarted())

        val cancel = assertThrows(BrowserSessionLeaseManager.Failure::class.java) {
            fixture.cancel("client-a", lease, "reset-request")
        }
        assertEquals("REQUEST_NOT_CANCELLABLE", cancel.code)

        fixture.controller.releaseReset()
        reset.join(5_000)
        assertTrue(result[0]!!.payload.getJSONObject("browser").getBoolean("ok"))
    }

    private fun fixture(): Fixture = Fixture(
        controller = FakeBrowserController(),
        credentials = FixedCredentials(TOKEN),
    )

    private class Fixture(
        val controller: FakeBrowserController,
        credentials: BrowserPairingCredentials,
    ) {
        private val protocol = BrowserBridgeProtocol(
            pairingStore = credentials,
            browserController = controller,
            leaseManager = BrowserSessionLeaseManager(sequence("lease")),
        )
        private var requestSequence = 0

        fun process(
            method: String,
            clientId: String = "test-client",
            id: String = "request-${++requestSequence}",
            extra: JSONObject = JSONObject(),
        ): BrowserBridgeProtocol.Result {
            val request = JSONObject()
                .put("token", TOKEN)
                .put("client_id", clientId)
                .put("method", method)
            extra.keys().forEach { key -> request.put(key, extra.opt(key)) }
            return protocol.process(request, id)
        }

        fun acquire(clientId: String): String =
            process("browser.session.acquire", clientId).payload.getString("lease_id")

        fun release(clientId: String, leaseId: String) {
            process(
                "browser.session.release",
                clientId,
                extra = JSONObject().put("lease_id", leaseId),
            )
        }

        fun cancel(clientId: String, leaseId: String, requestId: String) = process(
            method = "browser.stop",
            clientId = clientId,
            extra = JSONObject()
                .put("lease_id", leaseId)
                .put("request_id", requestId),
        )

        private fun sequence(prefix: String): () -> String {
            var next = 0
            return { "$prefix-${++next}" }
        }
    }

    private class FakeBrowserController : BrowserBridgeController {
        private val executeStarted = CountDownLatch(1)
        private val executeRelease = CountDownLatch(1)
        private val resetStarted = CountDownLatch(1)
        private val resetRelease = CountDownLatch(1)
        @Volatile var executeCalled: Boolean = false
        @Volatile var blockExecute: Boolean = false
        @Volatile var blockReset: Boolean = false

        override fun execute(
            args: JSONObject,
            operation: BrowserOperationTicket,
        ): BrowserToolResult {
            executeCalled = true
            executeStarted.countDown()
            if (blockExecute) executeRelease.await(5, TimeUnit.SECONDS)
            return result(args.optString("action"), operation.isCancelled())
        }

        override fun reset(operation: BrowserOperationTicket): BrowserToolResult {
            resetStarted.countDown()
            if (blockReset) resetRelease.await(5, TimeUnit.SECONDS)
            return result("reset", operation.isCancelled())
        }

        override fun interrupt(operation: BrowserOperationTicket): Boolean = true

        override fun snapshot(): BrowserSessionSnapshot = BrowserSessionSnapshot()

        fun awaitExecuteStarted(): Boolean = executeStarted.await(3, TimeUnit.SECONDS)
        fun releaseExecute() = executeRelease.countDown()
        fun awaitResetStarted(): Boolean = resetStarted.await(3, TimeUnit.SECONDS)
        fun releaseReset() = resetRelease.countDown()

        private fun result(action: String, cancelled: Boolean): BrowserToolResult =
            BrowserToolResult(
                JSONObject()
                    .put("ok", !cancelled)
                    .put("tool", "browser_use")
                    .put("action", action)
                    .put("status", if (cancelled) "cancelled" else "ok")
                    .also { payload ->
                        if (cancelled) {
                            payload.put("code", "CANCELLED").put("message", "cancelled")
                        }
                    }
                    .toString(),
            )
    }

    private class FixedCredentials(private var value: String?) : BrowserPairingCredentials {
        override fun token(): String? = value
        override fun rotate(): String = "rotated-token".also { value = it }
        override fun revoke() {
            value = null
        }
    }

    private companion object {
        const val TOKEN = "test-token-that-is-long-enough-for-bridge"
    }
}
