package com.thoitiettxl.eta.bridge

import com.thoitiettxl.eta.core.BrowserOperationTicket
import com.thoitiettxl.eta.core.BrowserSessionSnapshot
import com.thoitiettxl.eta.core.BrowserToolResult
import org.json.JSONObject

/** Browser execution boundary used by the pure bridge protocol core. */
internal interface BrowserBridgeController {
    fun execute(args: JSONObject, operation: BrowserOperationTicket): BrowserToolResult

    fun reset(operation: BrowserOperationTicket): BrowserToolResult

    fun interrupt(operation: BrowserOperationTicket): Boolean

    fun snapshot(): BrowserSessionSnapshot
}
