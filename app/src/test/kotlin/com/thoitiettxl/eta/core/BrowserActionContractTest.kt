package com.thoitiettxl.eta.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserActionContractTest {
    @Test
    fun `matches every action published by AgentBrowserToolCatalog`() {
        assertEquals(
            setOf(
                "navigate",
                "get_readable",
                "get_text",
                "find_elements",
                "click",
                "type",
                "scroll",
                "screenshot",
                "get_page_info",
                "go_back",
                "go_forward",
                "reload",
                "wait_for_selector",
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
            JSONObject().put("action", "click").put("selector", "#submit"),
            JSONObject().put("action", "type").put("coordinate_x", 10).put("coordinate_y", 20)
                .put("text", "hello").put("submit", false),
            JSONObject().put("action", "scroll").put("direction", "down").put("amount", 600),
            JSONObject().put("action", "screenshot").put("read_image", true),
            JSONObject().put("action", "get_page_info"),
            JSONObject().put("action", "go_back"),
            JSONObject().put("action", "go_forward"),
            JSONObject().put("action", "reload"),
            JSONObject().put("action", "wait_for_selector").put("selector", "main")
                .put("timeout_ms", 5_000),
        )

        valid.forEach { args ->
            assertNull(args.toString(), BrowserActionContract.validate(args))
        }
    }

    @Test
    fun `rejects missing and wrongly typed side effect arguments`() {
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
    fun `preserves Eta defaults and clamps`() {
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
        assertEquals(0, BrowserActionContract.textOffset(JSONObject().put("offset", -1)))
        assertEquals(12_000, BrowserActionContract.textLimit(JSONObject().put("max_chars", 50_000)))
        assertEquals(1, BrowserActionContract.scrollAmount(JSONObject().put("amount", 0)))
        assertEquals(5_000, BrowserActionContract.scrollAmount(JSONObject().put("amount", 50_000)))
    }
}
