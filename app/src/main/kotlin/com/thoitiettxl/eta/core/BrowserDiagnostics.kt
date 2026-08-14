package com.thoitiettxl.eta.core

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, memory-only WebView diagnostics. No headers, bodies, cookies, or timing data. */
internal object BrowserDiagnostics {
    private const val MAX_ENTRIES = 200
    private const val MAX_MESSAGE_CHARS = 1_000
    private const val MAX_DESCRIPTION_CHARS = 240
    private const val MAX_PATH_CHARS = 512

    private data class Entry(
        val sequence: Int,
        val value: JSONObject,
    )

    private val consoleEntries = ArrayDeque<Entry>()
    private val networkEntries = ArrayDeque<Entry>()
    private var consoleSequence = 0
    private var networkSequence = 0

    @Synchronized
    fun recordConsole(level: String, message: String, source: String?, line: Int) {
        val sequence = ++consoleSequence
        append(
            consoleEntries,
            Entry(
                sequence,
                JSONObject()
                    .put("seq", sequence)
                    .put("level", bounded(level.lowercase(Locale.ROOT), 24))
                    .put("message", bounded(message, MAX_MESSAGE_CHARS))
                    .put("source", sanitizeUrl(source))
                    .put("line", line.coerceAtLeast(0)),
            ),
        )
    }

    @Synchronized
    fun recordNetworkRequest(method: String, url: String?, mainFrame: Boolean) {
        recordNetwork(
            kind = "request",
            method = method,
            url = url,
            mainFrame = mainFrame,
        )
    }

    @Synchronized
    fun recordNetworkHttpError(method: String, url: String?, mainFrame: Boolean, status: Int) {
        recordNetwork(
            kind = "http_error",
            method = method,
            url = url,
            mainFrame = mainFrame,
            status = status,
        )
    }

    @Synchronized
    fun recordNetworkFailure(
        method: String,
        url: String?,
        mainFrame: Boolean,
        errorCode: Int,
        description: String,
    ) {
        recordNetwork(
            kind = "network_error",
            method = method,
            url = url,
            mainFrame = mainFrame,
            errorCode = errorCode,
            description = description,
        )
    }

    @Synchronized
    fun consoleSnapshot(since: Int, limit: Int): JSONObject = snapshot(
        entries = consoleEntries,
        sequence = consoleSequence,
        since = since,
        limit = limit,
        arrayKey = "entries",
    ).put("coverage", "webview_console_callbacks")

    @Synchronized
    fun networkSnapshot(since: Int, limit: Int): JSONObject = snapshot(
        entries = networkEntries,
        sequence = networkSequence,
        since = since,
        limit = limit,
        arrayKey = "events",
    )
        .put("coverage", "webview_callback_requests_and_errors")
        .put("complete_response_trace", false)
        .put("captures_headers", false)
        .put("captures_bodies", false)

    @Synchronized
    fun clear() {
        consoleEntries.clear()
        networkEntries.clear()
        consoleSequence = 0
        networkSequence = 0
    }

    internal fun sanitizeUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val parsed = runCatching { URI(value) }.getOrNull() ?: return "[redacted]"
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return "[inline]"
        if (scheme == "about" && value.equals("about:blank", ignoreCase = true)) return "about:blank"
        if (scheme !in setOf("http", "https")) return "$scheme:[redacted]"
        val host = parsed.host?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) return "$scheme://[redacted]"
        val port = parsed.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
        val path = bounded(parsed.rawPath.orEmpty(), MAX_PATH_CHARS)
            .filterNot(Char::isISOControl)
        return "$scheme://$host$port$path"
    }

    private fun recordNetwork(
        kind: String,
        method: String,
        url: String?,
        mainFrame: Boolean,
        status: Int? = null,
        errorCode: Int? = null,
        description: String? = null,
    ) {
        val sequence = ++networkSequence
        val value = JSONObject()
            .put("seq", sequence)
            .put("kind", kind)
            .put("method", bounded(method.uppercase(Locale.ROOT), 16))
            .put("url", sanitizeUrl(url))
            .put("main_frame", mainFrame)
        status?.let { value.put("status", it) }
        errorCode?.let { value.put("error_code", it) }
        description?.let { value.put("description", bounded(it, MAX_DESCRIPTION_CHARS)) }
        append(networkEntries, Entry(sequence, value))
    }

    private fun snapshot(
        entries: ArrayDeque<Entry>,
        sequence: Int,
        since: Int,
        limit: Int,
        arrayKey: String,
    ): JSONObject {
        val boundedSince = since.coerceAtLeast(0)
        val boundedLimit = limit.coerceIn(1, 100)
        val selected = entries.asSequence()
            .filter { it.sequence > boundedSince }
            .take(boundedLimit)
            .toList()
        val firstAvailable = entries.firstOrNull()?.sequence ?: (sequence + 1)
        val nextSince = selected.lastOrNull()?.sequence ?: boundedSince.coerceAtMost(sequence)
        return JSONObject()
            .put("since", boundedSince)
            .put("next_since", nextSince)
            .put("latest_seq", sequence)
            .put("dropped_before", (firstAvailable - 1).coerceAtLeast(0))
            .put("count", selected.size)
            .put("truncated", entries.count { it.sequence > boundedSince } > selected.size)
            .put(
                arrayKey,
                JSONArray().also { array -> selected.forEach { array.put(it.value) } },
            )
    }

    private fun append(target: ArrayDeque<Entry>, entry: Entry) {
        target.addLast(entry)
        while (target.size > MAX_ENTRIES) target.removeFirst()
    }

    private fun bounded(value: String, limit: Int): String =
        value.filterNot(Char::isISOControl).take(limit)
}
