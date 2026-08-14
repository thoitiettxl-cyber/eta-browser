package com.thoitiettxl.eta.bridge

import android.content.Context
import com.thoitiettxl.eta.core.BrowserOperationTicket
import com.thoitiettxl.eta.core.BrowserSessionEngine
import com.thoitiettxl.eta.core.BrowserSessionSnapshot
import com.thoitiettxl.eta.core.BrowserToolResult
import org.json.JSONObject

internal class BrowserSessionBridgeController(context: Context) : BrowserBridgeController {
    private val appContext = context.applicationContext

    override fun execute(
        args: JSONObject,
        operation: BrowserOperationTicket,
    ): BrowserToolResult = BrowserSessionEngine.execute(appContext, args, operation)

    override fun reset(operation: BrowserOperationTicket): BrowserToolResult =
        BrowserSessionEngine.resetFromExternal(operation)

    override fun interrupt(operation: BrowserOperationTicket): Boolean =
        BrowserSessionEngine.interruptExternalOperation(operation)

    override fun snapshot(): BrowserSessionSnapshot = BrowserSessionEngine.snapshots.value
}
