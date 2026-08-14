package com.thoitiettxl.eta.core

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal enum class BrowserAction(val wireName: String) {
    NAVIGATE("navigate"),
    GET_READABLE("get_readable"),
    GET_TEXT("get_text"),
    FIND_ELEMENTS("find_elements"),
    OBSERVE("observe"),
    CLICK("click"),
    TYPE("type"),
    HOVER("hover"),
    SELECT("select"),
    PRESS("press"),
    SCROLL("scroll"),
    SCREENSHOT("screenshot"),
    GET_PAGE_INFO("get_page_info"),
    GO_BACK("go_back"),
    GO_FORWARD("go_forward"),
    RELOAD("reload"),
    WAIT_FOR_SELECTOR("wait_for_selector"),
    REQUEST_HELP("request_help"),
    CONSOLE("console"),
    NETWORK("network");

    companion object {
        fun fromWireName(value: String): BrowserAction? =
            entries.firstOrNull { it.wireName == value.trim().lowercase(Locale.ROOT) }
    }
}

/**
 * Executable argument contract for Eta Browser's protocol-v2 action vocabulary.
 *
 * The original 13 Eta-compatible actions remain unchanged. Standalone actions
 * add bounded semantic observation, explicit human handoff, interaction
 * primitives, and read-only diagnostics without exposing arbitrary JavaScript.
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
        ARRAY("array"),
        OBJECT("object"),
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
        Field("ref", Kind.STRING),
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
        Field("key", Kind.STRING),
        Field("value", Kind.STRING),
        Field("values", Kind.ARRAY),
        Field("prompt", Kind.STRING),
        Field("title", Kind.STRING),
        Field("target_selector", Kind.STRING),
        Field("completion_criteria", Kind.OBJECT),
        Field("since", Kind.INTEGER),
        Field("limit", Kind.INTEGER),
    )

    private val supportedPressKeys = linkedMapOf(
        "enter" to "Enter",
        "escape" to "Escape",
        "tab" to "Tab",
        "shift+tab" to "Shift+Tab",
        "arrowup" to "ArrowUp",
        "arrowdown" to "ArrowDown",
        "arrowleft" to "ArrowLeft",
        "arrowright" to "ArrowRight",
        "home" to "Home",
        "end" to "End",
        "pageup" to "PageUp",
        "pagedown" to "PageDown",
        "space" to "Space",
        "backspace" to "Backspace",
        "delete" to "Delete",
        "ctrl+a" to "Ctrl+A",
    )

    fun action(args: JSONObject): BrowserAction? =
        BrowserAction.fromWireName(args.optString("action"))

    fun navigationTimeoutMs(args: JSONObject): Long =
        args.optLong("timeout_ms", NAVIGATION_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, NAVIGATION_TIMEOUT_MS)

    fun selectorTimeoutMs(args: JSONObject): Long =
        args.optLong("timeout_ms", DEFAULT_SELECTOR_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_SELECTOR_TIMEOUT_MS)

    fun requestHelpTimeoutMs(args: JSONObject): Long =
        args.optLong("timeout_ms", DEFAULT_HELP_TIMEOUT_MS)
            .coerceIn(MIN_HELP_TIMEOUT_MS, MAX_HELP_TIMEOUT_MS)

    fun textOffset(args: JSONObject): Int =
        args.optInt("offset", 0).coerceIn(0, MAX_DOCUMENT_CHARS)

    fun textLimit(args: JSONObject): Int =
        args.optInt("max_chars", DEFAULT_TEXT_CHARS).coerceIn(MIN_TEXT_CHARS, MAX_TEXT_CHARS)

    fun scrollAmount(args: JSONObject): Int =
        args.optInt("amount", DEFAULT_SCROLL_AMOUNT).coerceIn(1, MAX_SCROLL_AMOUNT)

    fun diagnosticSince(args: JSONObject): Int =
        args.optInt("since", 0).coerceAtLeast(0)

    fun diagnosticLimit(args: JSONObject): Int =
        args.optInt("limit", DEFAULT_DIAGNOSTIC_LIMIT).coerceIn(1, MAX_DIAGNOSTIC_LIMIT)

    fun pressKey(args: JSONObject): String? =
        supportedPressKeys[args.optString("key").trim().lowercase(Locale.ROOT)]

    fun selectionValues(args: JSONObject): List<String> {
        val values = mutableListOf<String>()
        if (args.has("value") && !args.isNull("value")) values += args.optString("value")
        args.optJSONArray("values")?.let { array ->
            repeat(array.length().coerceAtMost(MAX_SELECTION_VALUES)) { index ->
                values += array.optString(index)
            }
        }
        return values.distinct().take(MAX_SELECTION_VALUES)
    }

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
            BrowserAction.CLICK,
            BrowserAction.HOVER -> validateTarget(args)
            BrowserAction.TYPE ->
                requirePresent(args, "text", "type requires text") ?: validateTarget(args)
            BrowserAction.SELECT ->
                validateSelection(args) ?: validateTarget(args)
            BrowserAction.PRESS ->
                validatePress(args)
            BrowserAction.WAIT_FOR_SELECTOR -> requireNonBlank(
                args,
                "selector",
                "wait_for_selector requires selector",
            )
            BrowserAction.REQUEST_HELP -> validateRequestHelp(args)
            else -> null
        }
    }

    private fun validatePress(args: JSONObject): Issue? {
        requireNonBlank(args, "key", "press requires key")?.let { return it }
        if (pressKey(args) == null) {
            return Issue(
                "key",
                "press key is unsupported; use ${supportedPressKeys.values.joinToString("/")}",
            )
        }
        return validateOptionalTarget(args)
    }

    private fun validateSelection(args: JSONObject): Issue? {
        val hasValue = args.has("value") && !args.isNull("value")
        val array = args.optJSONArray("values")
        if (!hasValue && array == null) {
            return Issue("value", "select requires value or values")
        }
        if (hasValue && args.optString("value").length > MAX_SELECTION_VALUE_CHARS) {
            return Issue("value", "select value must not exceed $MAX_SELECTION_VALUE_CHARS characters")
        }
        if (array != null) {
            if (array.length() == 0 || array.length() > MAX_SELECTION_VALUES) {
                return Issue("values", "select values must contain 1 to $MAX_SELECTION_VALUES strings")
            }
            repeat(array.length()) { index ->
                val value = array.opt(index)
                if (value !is String) {
                    return Issue("values", "select values must contain only strings")
                }
                if (value.length > MAX_SELECTION_VALUE_CHARS) {
                    return Issue(
                        "values",
                        "select values must not exceed $MAX_SELECTION_VALUE_CHARS characters",
                    )
                }
            }
        }
        return null
    }

    private fun validateRequestHelp(args: JSONObject): Issue? {
        requireNonBlank(args, "prompt", "request_help requires prompt")?.let { return it }
        if (args.optString("prompt").length > MAX_HELP_PROMPT_CHARS) {
            return Issue("prompt", "request_help prompt must not exceed $MAX_HELP_PROMPT_CHARS characters")
        }
        if (args.optString("title").length > MAX_HELP_TITLE_CHARS) {
            return Issue("title", "request_help title must not exceed $MAX_HELP_TITLE_CHARS characters")
        }
        if (args.has("target_selector") && args.optString("target_selector").isBlank()) {
            return Issue("target_selector", "request_help target_selector must not be blank")
        }
        if (args.optString("target_selector").length > MAX_HELP_SELECTOR_CHARS) {
            return Issue(
                "target_selector",
                "request_help target_selector must not exceed $MAX_HELP_SELECTOR_CHARS characters",
            )
        }
        val criteria = args.optJSONObject("completion_criteria") ?: return null
        val allowed = setOf("url_contains", "selector_exists", "match", "stable_for_ms")
        criteria.keys().forEach { key ->
            if (key !in allowed) {
                return Issue("completion_criteria", "unsupported completion criterion: $key")
            }
        }
        val urlContains = criteria.opt("url_contains")
        if (urlContains != null && urlContains !== JSONObject.NULL && urlContains !is String) {
            return Issue("completion_criteria", "url_contains must be string")
        }
        val selectorExists = criteria.opt("selector_exists")
        if (selectorExists != null && selectorExists !== JSONObject.NULL && selectorExists !is String) {
            return Issue("completion_criteria", "selector_exists must be string")
        }
        if (
            (urlContains as? String).isNullOrBlank() &&
            (selectorExists as? String).isNullOrBlank()
        ) {
            return Issue(
                "completion_criteria",
                "completion_criteria requires url_contains or selector_exists",
            )
        }
        if ((urlContains as? String)?.length?.let { it > MAX_HELP_URL_CRITERION_CHARS } == true) {
            return Issue(
                "completion_criteria",
                "url_contains must not exceed $MAX_HELP_URL_CRITERION_CHARS characters",
            )
        }
        if ((selectorExists as? String)?.length?.let { it > MAX_HELP_SELECTOR_CHARS } == true) {
            return Issue(
                "completion_criteria",
                "selector_exists must not exceed $MAX_HELP_SELECTOR_CHARS characters",
            )
        }
        val match = criteria.opt("match")
        if (
            match != null && match !== JSONObject.NULL &&
            (match !is String || match.lowercase(Locale.ROOT) !in setOf("any", "all"))
        ) {
            return Issue("completion_criteria", "completion match only supports any/all")
        }
        val stable = criteria.opt("stable_for_ms")
        if (stable != null && stable !== JSONObject.NULL && !matchesKind(stable, Kind.INTEGER)) {
            return Issue("completion_criteria", "stable_for_ms must be integer")
        }
        return null
    }

    private fun validateOptionalTarget(args: JSONObject): Issue? {
        val hasTarget = args.optString("selector").isNotBlank() ||
            args.optString("ref").isNotBlank() ||
            (args.has("coordinate_x") && !args.isNull("coordinate_x")) ||
            (args.has("coordinate_y") && !args.isNull("coordinate_y"))
        return if (hasTarget) validateTarget(args) else null
    }

    private fun validateTarget(args: JSONObject): Issue? {
        val selector = args.optString("selector").trim().takeIf(String::isNotBlank)
        val ref = args.optString("ref").trim().takeIf(String::isNotBlank)
        val hasX = args.has("coordinate_x") && !args.isNull("coordinate_x")
        val hasY = args.has("coordinate_y") && !args.isNull("coordinate_y")
        if (hasX != hasY) {
            return Issue(
                if (hasX) "coordinate_y" else "coordinate_x",
                "coordinate_x and coordinate_y must be provided together",
            )
        }
        if (ref != null && !REF_PATTERN.matches(ref)) {
            return Issue("ref", "element ref must use the @eN format from the latest observe result")
        }
        if (listOf(selector != null, ref != null, hasX).count { it } > 1) {
            return Issue("selector", "provide exactly one target: selector, ref, or coordinates")
        }
        if (selector == null && ref == null && !hasX) {
            return Issue("selector", "browser interaction requires selector, ref, or coordinates")
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
        Kind.ARRAY -> value is JSONArray
        Kind.OBJECT -> value is JSONObject
        Kind.INTEGER -> value is Number && value.toDouble().let { number ->
            number.isFinite() && number % 1.0 == 0.0 &&
                number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble()
        }
    }

    private val REF_PATTERN = Regex("^@e[1-9][0-9]{0,8}$")

    private const val MIN_TIMEOUT_MS = 500L
    private const val NAVIGATION_TIMEOUT_MS = 25_000L
    private const val DEFAULT_SELECTOR_TIMEOUT_MS = 5_000L
    private const val MAX_SELECTOR_TIMEOUT_MS = 30_000L
    private const val DEFAULT_HELP_TIMEOUT_MS = 300_000L
    private const val MIN_HELP_TIMEOUT_MS = 1_000L
    private const val MAX_HELP_TIMEOUT_MS = 300_000L
    private const val DEFAULT_TEXT_CHARS = 8_000
    private const val MIN_TEXT_CHARS = 256
    private const val MAX_TEXT_CHARS = 12_000
    private const val MAX_DOCUMENT_CHARS = 200_000
    private const val DEFAULT_SCROLL_AMOUNT = 600
    private const val MAX_SCROLL_AMOUNT = 5_000
    private const val DEFAULT_DIAGNOSTIC_LIMIT = 50
    private const val MAX_DIAGNOSTIC_LIMIT = 100
    private const val MAX_SELECTION_VALUES = 16
    private const val MAX_SELECTION_VALUE_CHARS = 240
    private const val MAX_HELP_PROMPT_CHARS = 600
    private const val MAX_HELP_TITLE_CHARS = 120
    private const val MAX_HELP_SELECTOR_CHARS = 240
    private const val MAX_HELP_URL_CRITERION_CHARS = 320
}
