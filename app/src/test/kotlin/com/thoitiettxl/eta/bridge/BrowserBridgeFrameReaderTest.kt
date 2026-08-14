package com.thoitiettxl.eta.bridge

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBridgeFrameReaderTest {
    @Test
    fun `accepts a frame at the byte limit`() {
        val payload = "x".repeat(MAX_BYTES).toByteArray(StandardCharsets.UTF_8)

        val result = read(payload + byteArrayOf('\n'.code.toByte()))

        assertEquals(MAX_BYTES, result.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun `drains an oversized newline terminated frame before rejecting it`() {
        val payload = "x".repeat(70_215).toByteArray(StandardCharsets.UTF_8)
        val input = ByteArrayInputStream(payload + byteArrayOf('\n'.code.toByte()))

        val failure = assertThrows(BrowserBridgeRequestFailure::class.java) {
            read(input)
        }

        assertEquals("REQUEST_TOO_LARGE", failure.code)
        assertEquals(0, input.available())
    }

    @Test
    fun `drained tcp frame receives structured request too large response`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val handled = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val failure = assertThrows(BrowserBridgeRequestFailure::class.java) {
                        BrowserBridgeFrameReader.readBoundedLine(
                            input = socket.getInputStream(),
                            maxBytes = MAX_BYTES,
                            maxDrainBytes = MAX_DRAIN_BYTES,
                            drainTimeoutMs = DRAIN_TIMEOUT_MS,
                            updateReadTimeout = { timeoutMs -> socket.soTimeout = timeoutMs },
                        )
                    }
                    assertEquals("REQUEST_TOO_LARGE", failure.code)
                    socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                        writer.write("{\"ok\":false,\"error\":{\"code\":\"REQUEST_TOO_LARGE\"}}\n")
                    }
                }
            }

            Socket("127.0.0.1", server.localPort).use { client ->
                client.soTimeout = 5_000
                val payload = "x".repeat(70_215).toByteArray(StandardCharsets.UTF_8)
                val output = client.getOutputStream()
                output.write(payload)
                output.write('\n'.code)
                output.flush()
                client.shutdownOutput()
                val response = client.getInputStream()
                    .bufferedReader(StandardCharsets.UTF_8)
                    .readLine()
                assertEquals(
                    "{\"ok\":false,\"error\":{\"code\":\"REQUEST_TOO_LARGE\"}}",
                    response,
                )
            }
            handled.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `drains an oversized eof terminated frame before rejecting it`() {
        val input = ByteArrayInputStream("x".repeat(MAX_BYTES + 1).toByteArray())

        val failure = assertThrows(BrowserBridgeRequestFailure::class.java) {
            read(input)
        }

        assertEquals("REQUEST_TOO_LARGE", failure.code)
        assertEquals(0, input.available())
    }

    @Test
    fun `counts carriage returns and strips only the final cr in crlf framing`() {
        val result = read("a\rb\r\n".toByteArray(StandardCharsets.UTF_8))

        assertEquals("a\rb", result)
    }

    @Test
    fun `preserves a trailing carriage return at eof`() {
        val result = read("a\r".toByteArray(StandardCharsets.UTF_8))

        assertEquals("a\r", result)
    }

    @Test
    fun `rejects an empty frame`() {
        val failure = assertThrows(BrowserBridgeRequestFailure::class.java) {
            read(byteArrayOf('\n'.code.toByte()))
        }

        assertEquals("EMPTY_REQUEST", failure.code)
    }

    @Test
    fun `does not consume bytes beyond the drain ceiling`() {
        val delimiter = "|next-frame".toByteArray(StandardCharsets.UTF_8)
        val input = ByteArrayInputStream(
            "x".repeat(MAX_DRAIN_BYTES + 1).toByteArray(StandardCharsets.UTF_8) + delimiter
        )

        val failure = assertThrows(BrowserBridgeRequestFailure::class.java) {
            read(input)
        }

        assertEquals("REQUEST_TOO_LARGE", failure.code)
        assertEquals('|'.code, input.read())
    }

    @Test
    fun `bounds oversized drain bytes`() {
        val input = CountingInputStream(
            ByteArrayInputStream("x".repeat(MAX_DRAIN_BYTES + 50).toByteArray())
        )

        val failure = assertThrows(BrowserBridgeRequestFailure::class.java) {
            read(input)
        }

        assertEquals("REQUEST_TOO_LARGE", failure.code)
        assertEquals(MAX_DRAIN_BYTES + 1, input.bytesRead)
    }

    @Test
    fun `uses the short drain timeout after overflow`() {
        val timeoutUpdates = mutableListOf<Int>()
        val input = object : InputStream() {
            var reads = 0

            override fun read(): Int {
                reads += 1
                if (reads <= MAX_BYTES + 1) return 'x'.code
                throw SocketTimeoutException("simulated drain timeout")
            }
        }

        val failure = assertThrows(BrowserBridgeRequestFailure::class.java) {
            BrowserBridgeFrameReader.readBoundedLine(
                input = input,
                maxBytes = MAX_BYTES,
                maxDrainBytes = MAX_DRAIN_BYTES,
                drainTimeoutMs = DRAIN_TIMEOUT_MS,
                updateReadTimeout = timeoutUpdates::add,
            )
        }

        assertEquals("REQUEST_TOO_LARGE", failure.code)
        assertTrue(timeoutUpdates.isNotEmpty())
        assertEquals(DRAIN_TIMEOUT_MS, timeoutUpdates.first())
        assertTrue(timeoutUpdates.all { it in 1..DRAIN_TIMEOUT_MS })
    }

    private fun read(bytes: ByteArray): String = read(ByteArrayInputStream(bytes))

    private fun read(input: InputStream): String = BrowserBridgeFrameReader.readBoundedLine(
        input = input,
        maxBytes = MAX_BYTES,
        maxDrainBytes = MAX_DRAIN_BYTES,
        drainTimeoutMs = DRAIN_TIMEOUT_MS,
    )

    private class CountingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        var bytesRead: Int = 0
            private set

        override fun read(): Int = delegate.read().also { value ->
            if (value != -1) bytesRead += 1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length).also { count ->
                if (count > 0) bytesRead += count
            }
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024
        const val MAX_DRAIN_BYTES = 1024 * 1024
        const val DRAIN_TIMEOUT_MS = 2_000
    }
}
