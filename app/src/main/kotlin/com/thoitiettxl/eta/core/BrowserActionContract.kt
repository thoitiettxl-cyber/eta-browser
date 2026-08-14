package com.thoitiettxl.eta.core

import java.util.Locale
import org.json.JSONObject

internal enum class BrowserAction(val wireName: String) {
    NAVIGATE("navigate"),
    GET_READABLE("get_readable"),
    GET_TEXT("get_text"),
    FIND_ELEMENTS("find_elements"),
    CLICK("click"),
    TYPE("type"),
    SCROLL("scroll"),
    SCREENSHOT("screenshot"),
    GET_PAGE_INFO("get_page_info"),
    GO_BACK("go_back"),
    GO_FORWARD("go_forward"),
    RELOAD("reload"),
    WAIT_FOR_SELECTOR("wait_for_selector");

    companion object {
        fun fromWireName(value: String): BrowserAction? =
            entries.firstOrNull { it.wireName == value.trim().lowercase(Locale.ROOT) }
    }
}

/**
 * Executable argument contract for the action vocabulary published by Eta's
 * AgentBrowserToolCatalog. The WebView engine keeps Eta's defaults and clamps;
 * this boundary rejects missing or wrongly typed values before they can be
 * silently converted by JSONObject opt accessors.
 */
internal object BrowserActionContract {
    data class Issue(
        val field: String,
        val message: String,
    )

    val supportedActions: Set<String> = BrowserAction.entries
        .mapTo(linkedSetOf(), BrowserAction::wireName)

    private enum class Kind(val label: String) {
        STRING("string"),
        INTEGER("integer"),
        BOOLEAN("boolean"),
    }

    private data class Field(
        val name: String,
        val kind: Kind,
        val values: Set<String> = emptySet(),
    )

    private val fields = listOf(
        Field("action", Kind.STRING),
        Field("url", Kind.STRING),
        Field("selector", Kind.STRING),
        Field("text", Kind.STRING),
        Field("submit", Kind.BOOLEAN),
        Field("coordinate_x", Kind.INTEGER),
        Field("coordinate_y", Kind.INTEGER),
        Field("amount", Kind.INTEGER),
        Field("direction", Kind.STRING, values = setOf("up", "down")),
        Field("offset", Kind.INTEGER),
        Field("max_chars", Kind.INTEGER),
        Field("read_image", Kind.BOOLEAN),
        Field("timeout_ms", Kind.INTEGER),
    )

    fun action(args: JSONObject): BrowserAction? =
        BrowserAction.fromWireName(args.optString("action"))

    fun navigationTimeoutMs(args: JSONObject): Long =
        args.optLong("timeout_ms", NAVIGATION_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, NAVIGATION_TIMEOUT_MS)

    fun selectorTimeoutMs(args: JSONObject): Long =
        args.optLong("timeout_ms", DEFAULT_SELECTOR_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_SELECTOR_TIMEOUT_MS)

    fun textOffset(args: JSONObject): Int =
        args.optInt("offset", 0).coerceIn(0, MAX_DOCUMENT_CHARS)

    fun textLimit(args: JSONObject): Int =
        args.optInt("max_chars", DEFAULT_TEXT_CHARS).coerceIn(MIN_TEXT_CHARS, MAX_TEXT_CHARS)

    fun scrollAmount(args: JSONObject): Int =
        args.optInt("amount", DEFAULT_SCROLL_AMOUNT).coerceIn(1, MAX_SCROLL_AMOUNT)

    fun validate(args: JSONObject): Issue? {
        if (!args.has("action") || args.isNull("action")) {
            return Issue("action", "browser action is required")
        }
        fields.forEach { field ->
            if (!args.has(field.name) || args.isNull(field.name)) return@forEach
            val value = args.opt(field.name)
            if (!matchesKind(value, field.kind)) {
                return Issue(field.name, "browser argument ${field.name} must be ${field.kind.label}")
            }
            if (
                field.values.isNotEmpty() &&
                (value as String).lowercase(Locale.ROOT) !in field.values
            ) {
                return Issue(
                    field.name,
                    "browser argument ${field.name} only supports ${field.values.joinToString("/")}",
                )
            }
        }

        return when (action(args)) {
            null -> Issue("action", "browser action is invalid or unsupported")
            BrowserAction.NAVIGATE -> requireNonBlank(args, "url", "navigate requires url")
            BrowserAction.CLICK -> validateTarget(args)
            BrowserAction.TYPE ->
                requirePresent(args, "text", "type requires text") ?: validateTarget(args)
            BrowserAction.WAIT_FOR_SELECTOR -> requireNonBlank(
                args,
                "selector",
                "wait_for_selector requires selector",
            )
            else -> null
        }
    }

    private fun validateTarget(args: JSONObject): Issue? {
        val selector = args.optString("selector").trim().takeIf(String::isNotBlank)
        val hasX = args.has("coordinate_x") && !args.isNull("coordinate_x")
        val hasY = args.has("coordinate_y") && !args.isNull("coordinate_y")
        if (hasX != hasY) {
            return Issue(
                if (hasX) "coordinate_y" else "coordinate_x",
                "coordinate_x and coordinate_y must be provided together",
            )
        }
        if (selector == null && !hasX) {
            return Issue("selector", "click/type requires selector or coordinate_x/coordinate_y")
        }
        return null
    }

    private fun requirePresent(args: JSONObject, field: String, message: String): Issue? =
        if (!args.has(field) || args.isNull(field)) Issue(field, message) else null

    private fun requireNonBlank(args: JSONObject, field: String, message: String): Issue? =
        requirePresent(args, field, message)
            ?: if (args.optString(field).isBlank()) Issue(field, message) else null

    private fun matchesKind(value: Any?, kind: Kind): Boolean = when (kind) {
        Kind.STRING -> value is String
        Kind.BOOLEAN -> value is Boolean
        Kind.INTEGER -> value is Number && value.toDouble().let { number ->
            number.isFinite() && number % 1.0 == 0.0 &&
                number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble()
        }
    }

    private const val MIN_TIMEOUT_MS = 500L
    private const val NAVIGATION_TIMEOUT_MS = 25_000L
    private const val DEFAULT_SELECTOR_TIMEOUT_MS = 5_000L
    private const val MAX_SELECTOR_TIMEOUT_MS = 30_000L
    private const val DEFAULT_TEXT_CHARS = 8_000
    private const val MIN_TEXT_CHARS = 256
    private const val MAX_TEXT_CHARS = 12_000
    private const val MAX_DOCUMENT_CHARS = 200_000
    private const val DEFAULT_SCROLL_AMOUNT = 600
    private const val MAX_SCROLL_AMOUNT = 5_000
}
