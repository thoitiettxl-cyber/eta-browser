package com.thoitiettxl.eta.core

import org.json.JSONObject

/** 浏览器文本结果的最终 UTF-8 载荷闸门。 */
internal object BrowserPayloadLimiter {
    const val MAX_BYTES = 12 * 1024

    fun serialize(envelope: JSONObject, maxBytes: Int = MAX_BYTES): String {
        require(maxBytes >= 512)

        fun encodedSize(): Int = envelope.toString().toByteArray(Charsets.UTF_8).size

        listOf(
            Triple("elements", "element_count", "elements_truncated"),
            Triple("entries", "count", "entries_truncated"),
            Triple("events", "count", "events_truncated"),
        ).forEach { (arrayKey, countKey, truncatedKey) ->
            envelope.optJSONArray(arrayKey)?.let { values ->
                if (encodedSize() > maxBytes) {
                    val originalCount = values.length()
                    envelope.put(truncatedKey, true)
                    envelope.put("truncated", true)
                    while (values.length() > 0 && encodedSize() > maxBytes) {
                        values.remove(values.length() - 1)
                        envelope.put(countKey, values.length())
                        if (arrayKey == "entries" || arrayKey == "events") {
                            val nextSince = values.optJSONObject(values.length() - 1)
                                ?.optInt("seq", envelope.optInt("since", 0))
                                ?: envelope.optInt("since", 0)
                            envelope.put("next_since", nextSince)
                        }
                    }
                    if (values.length() == originalCount) envelope.remove(truncatedKey)
                }
            }
        }

        if (encodedSize() > maxBytes && envelope.has("text")) {
            val original = envelope.optString("text")
            val offset = envelope.optInt("offset", 0).coerceAtLeast(0)
            envelope.put("payload_truncated", true)
            envelope.put("truncated", true)
            envelope.put("text", "")
            envelope.put("returned_chars", 0)
            envelope.put("next_offset", offset)

            var bestEnd = 0
            var low = 0
            var high = original.length
            while (low <= high) {
                val midpoint = (low + high) ushr 1
                val safeEnd = safePrefixEnd(original, midpoint)
                envelope.put("text", original.substring(0, safeEnd))
                envelope.put("returned_chars", safeEnd)
                envelope.put("next_offset", offset + safeEnd)
                if (encodedSize() <= maxBytes) {
                    bestEnd = safeEnd
                    low = midpoint + 1
                } else {
                    high = midpoint - 1
                }
            }
            envelope.put("text", original.substring(0, bestEnd))
            envelope.put("returned_chars", bestEnd)
            envelope.put("next_offset", offset + bestEnd)
        }

        if (encodedSize() <= maxBytes) return envelope.toString()

        val minimal = JSONObject()
        listOf(
            "ok", "tool", "action", "status", "code", "message", "content_source",
            "network_policy", "url", "display_url", "host", "title", "http_status",
            "risk_challenge",
        ).forEach { key ->
            if (!envelope.has(key)) return@forEach
            val value = envelope.opt(key)
            minimal.put(key, if (value is String) value.take(240) else value)
        }
        minimal.put("payload_truncated", true)
        return minimal.toString()
    }

    private fun safePrefixEnd(value: String, requestedEnd: Int): Int {
        var end = requestedEnd.coerceIn(0, value.length)
        if (
            end in 1 until value.length &&
            Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
        ) {
            end--
        }
        return end
    }
}
