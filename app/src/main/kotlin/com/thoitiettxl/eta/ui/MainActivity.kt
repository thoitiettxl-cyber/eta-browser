package com.thoitiettxl.eta.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.thoitiettxl.eta.bridge.BrowserBridgeContract
import com.thoitiettxl.eta.bridge.BrowserBridgeRuntime
import com.thoitiettxl.eta.bridge.BrowserBridgeService
import com.thoitiettxl.eta.bridge.BrowserPairingStore
import com.thoitiettxl.eta.core.BrowserSessionEngine

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var pairingStore: BrowserPairingStore
    private lateinit var pairingTokenInput: EditText
    private lateinit var status: TextView
    private lateinit var credentials: TextView
    private lateinit var clients: TextView
    private lateinit var pageState: TextView

    private val refresh = object : Runnable {
        override fun run() {
            renderState()
            mainHandler.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingStore = BrowserPairingStore(this)
        BrowserBridgeRuntime.syncPairing(pairingStore.token())
        setContentView(buildContent())
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        BrowserBridgeRuntime.syncPairing(pairingStore.token())
        mainHandler.post(refresh)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun buildContent(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        content.addView(TextView(this).apply {
            text = "Eta Browser"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Persistent local bridge pairing"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(20))
        })
        content.addView(TextView(this).apply {
            text = "This browser intentionally preserves Eta's permissive WebView profile. " +
                "External control is opt-in, authenticated, and bound only to the fixed " +
                "same-device endpoint ${BrowserBridgeContract.LOOPBACK_HOST}:" +
                "${BrowserBridgeContract.FIXED_PORT}."
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, matchWidth())

        status = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(8))
        }
        content.addView(status, matchWidth())

        credentials = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        content.addView(credentials, matchWidth())

        clients = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(clients, matchWidth())

        pairingTokenInput = EditText(this).apply {
            id = ID_PAIRING_TOKEN_INPUT
            hint = "Token from eta-browser pair (blank = app-generated)"
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
        }
        content.addView(pairingTokenInput, matchWidth())

        content.addView(horizontalButtons(
            button(ID_PAIR, "Pair this device") { pair() },
            button(ID_ROTATE, "Rotate credential") { rotate() },
        ), matchWidth())
        content.addView(horizontalButtons(
            button(ID_REVOKE, "Revoke pairing") { revoke() },
            button(ID_COPY_ENV, "Copy CLI environment") { copyCliEnvironment() },
        ), matchWidth())
        content.addView(horizontalButtons(
            button(ID_ENABLE, "Enable bridge") { BrowserBridgeService.start(this) },
            button(ID_DISABLE, "Disable bridge") { BrowserBridgeService.stop(this) },
        ), matchWidth())
        content.addView(
            button(ID_TAKEOVER, "Open browser (observe by default)") {
                startActivity(Intent(this, BrowserActivity::class.java))
            },
            matchWidth(),
        )

        pageState = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        }
        content.addView(pageState, matchWidth())

        return ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }
    }

    private fun pair() {
        val suppliedToken = pairingTokenInput.text?.toString()?.trim().orEmpty()
        runCatching {
            if (suppliedToken.isBlank()) pairingStore.pair() else pairingStore.install(suppliedToken)
        }
            .onSuccess(BrowserBridgeRuntime::syncPairing)
            .onFailure { BrowserBridgeRuntime.failed("Unable to persist browser pairing") }
    }

    private fun rotate() {
        runCatching { pairingStore.rotate() }
            .onSuccess(BrowserBridgeRuntime::syncPairing)
            .onFailure { BrowserBridgeRuntime.failed("Unable to rotate browser pairing") }
    }

    private fun revoke() {
        runCatching { pairingStore.revoke() }
            .onSuccess {
                BrowserBridgeRuntime.pairingRevoked()
                BrowserBridgeService.stop(this)
            }
            .onFailure { BrowserBridgeRuntime.failed("Unable to revoke browser pairing") }
    }

    private fun renderState() {
        val bridge = BrowserBridgeRuntime.snapshots.value
        status.text = when {
            bridge.running -> "Bridge enabled on ${bridge.host}:${bridge.port}"
            bridge.error != null -> "Bridge error: ${bridge.error}"
            bridge.paired -> "Paired; bridge disabled"
            else -> "Not paired; bridge disabled"
        }
        credentials.text = if (bridge.paired) {
            "export ETA_BROWSER_HOST=${bridge.host}\n" +
                "export ETA_BROWSER_PORT=${bridge.port}\n" +
                "export ETA_BROWSER_TOKEN=${bridge.token}"
        } else {
            "Pair this device to create a persistent CLI credential."
        }
        clients.text = buildString {
            append("Connected clients: ${bridge.activeClients}")
            if (bridge.lastClientId.isNotBlank()) {
                append("\nLast client: ${bridge.lastClientId}")
            }
        }

        val browser = BrowserSessionEngine.snapshots.value
        pageState.text = buildString {
            append("Browser: ")
            append(if (browser.available) browser.host.ifBlank { browser.displayUrl } else "no page")
            if (browser.title.isNotBlank()) append("\nTitle: ${browser.title}")
            append("\nLoading: ${browser.isLoading}")
            append("\nUser controlling: ${browser.isUserControlling}")
            append("\nHuman handoff pending: ${browser.isHumanHandoffPending}")
            browser.error?.let { append("\nError: $it") }
        }
    }

    private fun copyCliEnvironment() {
        val bridge = BrowserBridgeRuntime.snapshots.value
        if (!bridge.paired) return
        val value = "export ETA_BROWSER_HOST=${bridge.host}\n" +
            "export ETA_BROWSER_PORT=${bridge.port}\n" +
            "export ETA_BROWSER_TOKEN=${bridge.token}"
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("Eta Browser CLI environment", value)
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun horizontalButtons(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEach { button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(8), dp(4), 0)
                    }
                )
            }
        }

    private fun button(id: Int, label: String, action: () -> Unit): Button =
        Button(this).apply {
            this.id = id
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun matchWidth(): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ID_PAIRING_TOKEN_INPUT = 0x00EB0001
        const val ID_PAIR = 0x00EB0002
        const val ID_ROTATE = 0x00EB0003
        const val ID_REVOKE = 0x00EB0004
        const val ID_COPY_ENV = 0x00EB0005
        const val ID_ENABLE = 0x00EB0006
        const val ID_DISABLE = 0x00EB0007
        const val ID_TAKEOVER = 0x00EB0008
    }
}
