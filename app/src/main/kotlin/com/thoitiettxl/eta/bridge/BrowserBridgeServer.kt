package com.thoitiettxl.eta.bridge

import android.content.Context
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * One newline-delimited JSON request per loopback TCP connection. The server binds only to
 * the fixed local endpoint and authenticates each request against the current paired token.
 */
internal class BrowserBridgeServer(
    context: Context,
    private val pairingStore: BrowserPairingCredentials,
    private val onPairingRevoked: () -> Unit,
    private val endpoint: InetSocketAddress = InetSocketAddress(
        InetAddress.getByName(BrowserBridgeContract.LOOPBACK_HOST),
        BrowserBridgeContract.FIXED_PORT,
    ),
    browserController: BrowserBridgeController? = null,
) : Closeable {
    private val controller = browserController ?: BrowserSessionBridgeController(context)
    private val protocol = BrowserBridgeProtocol(
        pairingStore = pairingStore,
        browserController = controller,
        endpoint = "${endpoint.address.hostAddress}:${endpoint.port}",
    )
    private val closed = AtomicBoolean(false)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val requestExecutor: ExecutorService = ThreadPoolExecutor(
        MAX_CONCURRENT_REQUESTS,
        MAX_CONCURRENT_REQUESTS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_REQUESTS),
        { runnable ->
            Thread(runnable, "eta-browser-bridge-request").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val serverSocket = ServerSocket()
    private var acceptThread: Thread? = null

    fun start() {
        check(acceptThread == null) { "Browser bridge already started" }
        check(pairingStore.token() != null) { "Pair this device before enabling the bridge" }
        try {
            serverSocket.reuseAddress = false
            serverSocket.bind(endpoint, BACKLOG)
            protocol.health()
            acceptThread = Thread(::acceptLoop, "eta-browser-bridge-accept").apply {
                isDaemon = true
                start()
            }
        } catch (throwable: Throwable) {
            close()
            throw throwable
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        acceptThread?.interrupt()
        protocol.close()
        activeSockets.forEach { socket -> runCatching { socket.close() } }
        activeSockets.clear()
        requestExecutor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                if (closed.get()) return
                continue
            }
            activeSockets += socket
            try {
                requestExecutor.execute { handle(socket) }
            } catch (_: RejectedExecutionException) {
                activeSockets -= socket
                rejectBusy(socket)
            }
        }
    }

    private fun handle(socket: Socket) {
        var stopBridgeAfterReply = false
        try {
            socket.use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                var requestId: String? = null
                val response = runCatching {
                    val request = JSONObject(
                        BrowserBridgeFrameReader.readBoundedLine(
                            input = client.getInputStream(),
                            maxBytes = MAX_REQUEST_BYTES,
                            maxDrainBytes = MAX_DRAINABLE_FRAME_BYTES,
                            drainTimeoutMs = OVERSIZED_DRAIN_TIMEOUT_MS,
                            updateReadTimeout = { timeoutMs -> client.soTimeout = timeoutMs },
                        )
                    )
                    requestId = request.optString("id").trim().take(MAX_ID_CHARS)
                        .ifBlank { null }
                    protocol.process(request, requestId).also { result ->
                        stopBridgeAfterReply = result.stopBridgeAfterReply
                    }.payload.let { result -> successResponse(requestId, result) }
                }.getOrElse { throwable ->
                    val error = BrowserBridgeErrorMapper.from(throwable)
                    errorResponse(
                        id = requestId,
                        code = error.code,
                        message = error.message,
                    )
                }
                client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(response.toString())
                    writer.write("\n")
                    writer.flush()
                }
            }
        } finally {
            activeSockets -= socket
        }
        if (stopBridgeAfterReply) onPairingRevoked()
    }

    private fun rejectBusy(socket: Socket) {
        runCatching {
            socket.use { client ->
                client.soTimeout = REJECT_TIMEOUT_MS
                val response = errorResponse(
                    id = null,
                    code = "SERVER_BUSY",
                    message = "Bridge request capacity reached",
                )
                client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(response.toString())
                    writer.write("\n")
                    writer.flush()
                }
            }
        }
    }

    private fun successResponse(id: String?, result: JSONObject): JSONObject =
        JSONObject()
            .put("id", id ?: JSONObject.NULL)
            .put("ok", true)
            .put("result", result)

    private fun errorResponse(
        id: String?,
        code: String,
        message: String,
    ): JSONObject = JSONObject()
        .put("id", id ?: JSONObject.NULL)
        .put("ok", false)
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message.take(MAX_ERROR_CHARS)),
        )

    private companion object {
        const val BACKLOG = 8
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_QUEUED_REQUESTS = 8
        const val SOCKET_TIMEOUT_MS = 40_000
        const val REJECT_TIMEOUT_MS = 2_000
        const val OVERSIZED_DRAIN_TIMEOUT_MS = 2_000
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val MAX_DRAINABLE_FRAME_BYTES = 1024 * 1024
        const val MAX_ID_CHARS = 128
        const val MAX_ERROR_CHARS = 240
    }
}
