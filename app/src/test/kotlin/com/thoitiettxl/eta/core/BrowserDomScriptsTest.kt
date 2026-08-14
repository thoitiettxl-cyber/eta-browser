package com.thoitiettxl.eta.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomScriptsTest {
    @Test
    fun `readable extraction is bounded and preserves absolute urls`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.readable(offset = 0, maxChars = 8_000))

        assertTrue(script.contains("!visible(node)"))
        assertTrue(script.contains("remainingNodes: 8000"))
        assertTrue(script.contains("deadline: Date.now() + 750"))
        assertTrue(script.contains("return boundedString(parsed.href"))
        assertFalse(script.contains("parsed.protocol !== 'https:'"))
        assertFalse(script.contains("innerText"))
        assertFalse(script.contains("textContent"))
    }

    @Test
    fun `semantic observation replaces refs and never returns password values`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.observe(documentEpoch = 42L))

        assertTrue(script.contains("window[REF_STATE_KEY] ="))
        assertTrue(script.contains("refs: refs"))
        assertTrue(script.contains("document_epoch: 42"))
        assertTrue(script.contains("observation_id: installed.generation"))
        assertTrue(script.contains("function valueBearing(element)"))
        assertTrue(script.contains("description.text = exposesValue ? null : description.text"))
        assertTrue(script.contains("allowOwnText ? visibleText"))
        assertFalse(script.contains("element.value"))
        assertFalse(script.contains("value_length"))
        assertFalse(script.contains("description.value ="))
    }

    @Test
    fun `ref target resolution fails closed when observation is stale`() {
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.click(
                selector = null,
                ref = "@e12",
                x = null,
                y = null,
                documentEpoch = 9L,
            ),
        )

        assertTrue(script.contains("state.documentEpoch !== documentEpoch"))
        assertTrue(script.contains("state.refs[ref]"))
        assertTrue(script.contains("throw new Error('STALE_ELEMENT_REF')"))
        assertTrue(script.contains("resolveTarget(null, \"@e12\", null, null, 9)"))
    }

    @Test
    fun `select hover and press stay bounded to declared primitives`() {
        val select = BrowserDomScripts.wrap(
            BrowserDomScripts.select("select", null, null, null, listOf("one"), 1L),
        )
        val hover = BrowserDomScripts.wrap(
            BrowserDomScripts.hover("#menu", null, null, null, 1L),
        )
        val press = BrowserDomScripts.wrap(
            BrowserDomScripts.press(null, "@e1", null, null, "Enter", 1L),
        )

        assertTrue(select.contains("target instanceof HTMLSelectElement"))
        assertTrue(select.contains("SELECT_VALUE_NOT_FOUND"))
        assertTrue(hover.contains("synthetic_pointer_events"))
        assertTrue(press.contains("enter_activation"))
        assertFalse(select.contains("eval("))
        assertFalse(hover.contains("eval("))
        assertFalse(press.contains("eval("))
    }

    @Test
    fun `human handoff target only highlights and completion reads bounded signals`() {
        val highlight = BrowserDomScripts.wrap(BrowserDomScripts.highlightHelpTarget("#otp"))
        val completion = BrowserDomScripts.wrap(
            BrowserDomScripts.completionState("/dashboard", "#account", "any"),
        )

        assertTrue(highlight.contains("3px solid #ff9800"))
        assertTrue(highlight.contains("HELP_TARGET_KEY"))
        assertTrue(completion.contains("window.location.href"))
        assertTrue(completion.contains("document.querySelector(selectorExists)"))
        assertFalse(completion.contains("localStorage"))
        assertFalse(completion.contains("document.cookie"))
    }

    @Test
    fun `selector target resolution keeps legacy click behavior`() {
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.click(
                selector = "#submit",
                ref = null,
                x = null,
                y = null,
                documentEpoch = 1L,
            ),
        )

        assertTrue(script.contains("document.querySelector(selector);"))
        assertFalse(script.contains("requireHitTarget"))
        assertFalse(script.contains("TARGET_OCCLUDED"))
    }
}
