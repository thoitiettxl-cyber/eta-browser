package com.thoitiettxl.eta.bridge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

internal class BrowserBridgeRequestFailure(
    val code: String,
    override val message: String,
) : RuntimeException(message)

/** Reads one newline-delimited bridge frame while bounding retained and discarded bytes. */
internal object BrowserBridgeFrameReader {
    fun readBoundedLine(
        input: InputStream,
        maxBytes: Int,
        maxDrainBytes: Int,
        drainTimeoutMs: Int,
        updateReadTimeout: (Int) -> Unit = {},
        nanoTime: () -> Long = { System.nanoTime() },
    ): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxDrainBytes >= maxBytes) { "maxDrainBytes must cover maxBytes" }
        require(drainTimeoutMs > 0) { "drainTimeoutMs must be positive" }

        val output = ByteArrayOutputStream(minOf(maxBytes, BUFFER_BYTES))
        val readBuffer = ByteArray(BUFFER_BYTES)
        val drainTimeoutNanos = drainTimeoutMs.toLong() * NANOS_PER_MILLISECOND
        var frameBytes = 0
        var oversized = false
        var drainStartedNanos = 0L
        var terminatedByNewline = false

        fun requestTooLarge(): BrowserBridgeRequestFailure = BrowserBridgeRequestFailure(
            "REQUEST_TOO_LARGE",
            "Bridge request exceeds $maxBytes bytes",
        )

        readFrame@ while (true) {
            if (oversized) {
                val elapsedNanos = nanoTime() - drainStartedNanos
                val remainingNanos = drainTimeoutNanos - elapsedNanos
                if (remainingNanos <= 0L) throw requestTooLarge()
                updateReadTimeout(
                    ((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                        .coerceAtLeast(1)
                )
            }

            val maxReadBytes =
                (maxDrainBytes - frameBytes + 1).coerceIn(1, readBuffer.size)
            val count = try {
                input.read(readBuffer, 0, maxReadBytes)
            } catch (timeout: SocketTimeoutException) {
                if (oversized) throw requestTooLarge()
                throw timeout
            }
            if (count == -1) break

            for (index in 0 until count) {
                val value = readBuffer[index].toInt() and 0xff
                if (value == '\n'.code) {
                    terminatedByNewline = true
                    break@readFrame
                }

                frameBytes += 1
                if (frameBytes <= maxBytes) {
                    output.write(value)
                } else {
                    if (!oversized) {
                        oversized = true
                        drainStartedNanos = nanoTime()
                        updateReadTimeout(drainTimeoutMs)
                    }
                    if (frameBytes > maxDrainBytes) throw requestTooLarge()
                }
            }
        }

        if (oversized) throw requestTooLarge()

        var bytes = output.toByteArray()
        if (
            terminatedByNewline &&
            bytes.lastOrNull() == '\r'.code.toByte()
        ) {
            bytes = bytes.copyOf(bytes.size - 1)
        }
        if (bytes.isEmpty()) {
            throw BrowserBridgeRequestFailure("EMPTY_REQUEST", "Bridge request is empty")
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private const val BUFFER_BYTES = 8 * 1024
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
