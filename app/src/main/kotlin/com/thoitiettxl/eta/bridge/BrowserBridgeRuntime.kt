package com.thoitiettxl.eta.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class BrowserBridgeSnapshot(
    val running: Boolean = false,
    val host: String = BrowserBridgeContract.LOOPBACK_HOST,
    val port: Int = BrowserBridgeContract.FIXED_PORT,
    val paired: Boolean = false,
    val token: String = "",
    val activeClients: Int = 0,
    val lastClientId: String = "",
    val error: String? = null,
)

internal object BrowserBridgeRuntime {
    private val mutableSnapshots = MutableStateFlow(BrowserBridgeSnapshot())
    val snapshots: StateFlow<BrowserBridgeSnapshot> = mutableSnapshots.asStateFlow()

    @Synchronized
    fun syncPairing(token: String?) {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            paired = !token.isNullOrBlank(),
            token = token.orEmpty(),
            error = null,
        )
    }

    @Synchronized
    fun running(token: String) {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            running = true,
            paired = true,
            token = token,
            activeClients = 0,
            error = null,
        )
    }

    @Synchronized
    fun updateToken(token: String) {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            paired = true,
            token = token,
            error = null,
        )
    }

    @Synchronized
    fun failed(message: String) {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            running = false,
            activeClients = 0,
            error = message,
        )
    }

    @Synchronized
    fun stopped(preserveError: Boolean = false) {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            running = false,
            activeClients = 0,
            lastClientId = "",
            error = if (preserveError) mutableSnapshots.value.error else null,
        )
    }

    @Synchronized
    fun clientConnected(clientId: String) {
        val current = mutableSnapshots.value
        mutableSnapshots.value = current.copy(
            activeClients = current.activeClients + 1,
            lastClientId = clientId,
        )
    }

    @Synchronized
    fun pairingRevoked() {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            running = false,
            paired = false,
            token = "",
            activeClients = 0,
            lastClientId = "",
            error = null,
        )
    }

    @Synchronized
    fun clientDisconnected() {
        val current = mutableSnapshots.value
        mutableSnapshots.value = current.copy(
            activeClients = (current.activeClients - 1).coerceAtLeast(0),
        )
    }
}
