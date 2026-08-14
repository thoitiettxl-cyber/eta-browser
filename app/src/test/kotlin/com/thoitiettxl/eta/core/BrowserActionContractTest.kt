package com.thoitiettxl.eta.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserActionContractTest {
    @Test
    fun `matches Eta compatibility core and accepted standalone extensions`() {
        assertEquals(
            setOf(
                "navigate",
                "get_readable",
                "get_text",
                "find_elements",
                "observe",
                "click",
                "type",
                "hover",
                "select",
                "press",
                "scroll",
                "screenshot",
                "get_page_info",
                "go_back",
                "go_forward",
                "reload",
                "wait_for_selector",
                "request_help",
                "console",
                "network",
            ),
            BrowserActionContract.supportedActions,
        )
    }

    @Test
    fun `accepts one valid argument set for every action`() {
        val valid = listOf(
            JSONObject().put("action", "navigate").put("url", "https://example.com"),
            JSONObject().put("action", "get_readable").put("offset", 0).put("max_chars", 8_000),
            JSONObject().put("action", "get_text").put("selector", "main"),
            JSONObject().put("action", "find_elements").put("selector", "button"),
            JSONObject().put("action", "observe"),
            JSONObject().put("action", "click").put("selector", "#submit"),
            JSONObject().put("action", "type").put("ref", "@e12")
                .put("text", "hello").put("submit", false),
            JSONObject().put("action", "hover").put("coordinate_x", 10).put("coordinate_y", 20),
            JSONObject().put("action", "select").put("selector", "select")
                .put("values", JSONArray().put("one").put("two")),
            JSONObject().put("action", "press").put("key", "Shift+Tab"),
            JSONObject().put("action", "scroll").put("direction", "down").put("amount", 600),
            JSONObject().put("action", "screenshot").put("read_image", true),
            JSONObject().put("action", "get_page_info"),
            JSONObject().put("action", "go_back"),
            JSONObject().put("action", "go_forward"),
            JSONObject().put("action", "reload"),
            JSONObject().put("action", "wait_for_selector").put("selector", "main")
                .put("timeout_ms", 5_000),
            JSONObject().put("action", "request_help")
                .put("prompt", "Complete the verification")
                .put(
                    "completion_criteria",
                    JSONObject()
                        .put("url_contains", "/dashboard")
                        .put("match", "any")
                        .put("stable_for_ms", 1_000),
                ),
            JSONObject().put("action", "console").put("since", 0).put("limit", 50),
            JSONObject().put("action", "network").put("since", 4).put("limit", 20),
        )

        valid.forEach { args ->
            assertNull(args.toString(), BrowserActionContract.validate(args))
        }
    }

    @Test
    fun `rejects missing ambiguous and wrongly typed side effect arguments`() {
        assertEquals(
            "url",
            BrowserActionContract.validate(JSONObject().put("action", "navigate"))?.field,
        )
        assertEquals(
            "text",
            BrowserActionContract.validate(
                JSONObject().put("action", "type").put("selector", "input"),
            )?.field,
        )
        assertEquals(
            "coordinate_y",
            BrowserActionContract.validate(
                JSONObject().put("action", "click").put("coordinate_x", 10),
            )?.field,
        )
        assertEquals(
            "selector",
            BrowserActionContract.validate(
                JSONObject().put("action", "click").put("selector", "button").put("ref", "@e1"),
            )?.field,
        )
        assertEquals(
            "ref",
            BrowserActionContract.validate(
                JSONObject().put("action", "hover").put("ref", "@old"),
            )?.field,
        )
        assertEquals(
            "value",
            BrowserActionContract.validate(
                JSONObject().put("action", "select").put("selector", "select"),
            )?.field,
        )
        assertEquals(
            "value",
            BrowserActionContract.validate(
                JSONObject().put("action", "select").put("selector", "select")
                    .put("value", "x".repeat(241)),
            )?.field,
        )
        assertEquals(
            "key",
            BrowserActionContract.validate(JSONObject().put("action", "press").put("key", "F13"))?.field,
        )
        assertEquals(
            "prompt",
            BrowserActionContract.validate(JSONObject().put("action", "request_help"))?.field,
        )
        assertEquals(
            "prompt",
            BrowserActionContract.validate(
                JSONObject().put("action", "request_help").put("prompt", "x".repeat(601)),
            )?.field,
        )
        assertEquals(
            "target_selector",
            BrowserActionContract.validate(
                JSONObject().put("action", "request_help").put("prompt", "Continue")
                    .put("target_selector", "x".repeat(241)),
            )?.field,
        )
        assertEquals(
            "completion_criteria",
            BrowserActionContract.validate(
                JSONObject().put("action", "request_help")
                    .put("prompt", "Continue")
                    .put("completion_criteria", JSONObject().put("headers_contain", "token")),
            )?.field,
        )
        assertEquals(
            "amount",
            BrowserActionContract.validate(
                JSONObject().put("action", "scroll").put("amount", "600"),
            )?.field,
        )
        assertEquals(
            "direction",
            BrowserActionContract.validate(
                JSONObject().put("action", "scroll").put("direction", "left"),
            )?.field,
        )
    }

    @Test
    fun `preserves core defaults and clamps bounded extensions`() {
        assertEquals(25_000L, BrowserActionContract.navigationTimeoutMs(JSONObject()))
        assertEquals(
            25_000L,
            BrowserActionContract.navigationTimeoutMs(JSONObject().put("timeout_ms", 60_000)),
        )
        assertEquals(500L, BrowserActionContract.selectorTimeoutMs(JSONObject().put("timeout_ms", 1)))
        assertEquals(
            30_000L,
            BrowserActionContract.selectorTimeoutMs(JSONObject().put("timeout_ms", 60_000)),
        )
        assertEquals(
            300_000L,
            BrowserActionContract.requestHelpTimeoutMs(JSONObject().put("timeout_ms", 900_000)),
        )
        assertEquals(1_000L, BrowserActionContract.requestHelpTimeoutMs(JSONObject().put("timeout_ms", 1)))
        assertEquals(0, BrowserActionContract.textOffset(JSONObject().put("offset", -1)))
        assertEquals(12_000, BrowserActionContract.textLimit(JSONObject().put("max_chars", 50_000)))
        assertEquals(1, BrowserActionContract.scrollAmount(JSONObject().put("amount", 0)))
        assertEquals(5_000, BrowserActionContract.scrollAmount(JSONObject().put("amount", 50_000)))
        assertEquals(0, BrowserActionContract.diagnosticSince(JSONObject().put("since", -5)))
        assertEquals(100, BrowserActionContract.diagnosticLimit(JSONObject().put("limit", 500)))
        assertEquals("Enter", BrowserActionContract.pressKey(JSONObject().put("key", "enter")))
        assertEquals(
            listOf("one", "two"),
            BrowserActionContract.selectionValues(
                JSONObject().put("value", "one").put("values", JSONArray().put("one").put("two")),
            ),
        )
    }
}
