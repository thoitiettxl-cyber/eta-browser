package com.thoitiettxl.eta.bridge

internal interface BrowserPairingCredentials {
    fun token(): String?
    fun rotate(): String
    fun revoke()
}
