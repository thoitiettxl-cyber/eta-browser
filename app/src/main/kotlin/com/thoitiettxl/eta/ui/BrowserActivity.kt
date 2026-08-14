package com.thoitiettxl.eta.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.thoitiettxl.eta.core.BrowserSessionEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Shows the same WebView used by the CLI, with explicit opt-in user control. */
class BrowserActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val browserExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-browser-user-action").apply { isDaemon = true }
    }

    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var container: FrameLayout
    private lateinit var userControlSwitch: Switch
    private val userActionViews = mutableListOf<View>()
    private var addressFocused = false

    private val refresh = object : Runnable {
        override fun run() {
            val snapshot = BrowserSessionEngine.snapshots.value
            if (!addressFocused) address.setText(snapshot.displayUrl)
            if (userControlSwitch.isChecked != snapshot.isUserControlling) {
                userControlSwitch.isChecked = snapshot.isUserControlling
            }
            userActionViews.forEach { it.isEnabled = snapshot.isUserControlling }
            status.text = buildString {
                append(if (snapshot.isUserControlling) "User control" else "Observing Pi")
                append(" · ")
                append(if (snapshot.isLoading) "Loading ${snapshot.progress}%" else "Ready")
                if (snapshot.host.isNotBlank()) append(" · ${snapshot.host}")
                snapshot.error?.let { append(" · $it") }
            }
            mainHandler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrowserSessionEngine.initialize(applicationContext)
        setContentView(buildContent())
        BrowserSessionEngine.setUserControlActive(false)
        BrowserSessionEngine.attachTo(container, this)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(refresh)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        BrowserSessionEngine.detachFrom(container)
        browserExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(
            LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), 0)

                address = EditText(this@BrowserActivity).apply {
                    hint = "URL or domain"
                    isSingleLine = true
                    setOnFocusChangeListener { _, focused -> addressFocused = focused }
                    setOnEditorActionListener { _, _, _ ->
                        navigate()
                        true
                    }
                }
                userActionViews += address
                addView(
                    address,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(button("Go") { navigate() }.also(userActionViews::add))
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        userControlSwitch = Switch(this@BrowserActivity).apply {
            text = "Take control (blocks Pi browser actions)"
            isChecked = false
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnCheckedChangeListener { _, checked ->
                userActionViews.forEach { it.isEnabled = checked }
                BrowserSessionEngine.setUserControlActive(checked)
            }
        }
        addView(
            userControlSwitch,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        addView(
            LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), 0)
                addView(
                    weightedButton("Back") {
                        runBrowserAction { BrowserSessionEngine.goBackFromUser() }
                    }.also(userActionViews::add)
                )
                addView(
                    weightedButton("Forward") {
                        runBrowserAction { BrowserSessionEngine.goForwardFromUser() }
                    }.also(userActionViews::add)
                )
                addView(
                    weightedButton("Reload") {
                        runBrowserAction { BrowserSessionEngine.reloadFromUser() }
                    }.also(userActionViews::add)
                )
                addView(
                    weightedButton("Stop") {
                        runBrowserAction { BrowserSessionEngine.stopFromUser() }
                    }.also(userActionViews::add)
                )
                addView(
                    weightedButton("Reset") {
                        runBrowserAction { BrowserSessionEngine.resetFromUser() }
                    }.also(userActionViews::add)
                )
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        status = TextView(this@BrowserActivity).apply {
            text = "Ready"
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        addView(
            status,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        userActionViews.forEach { it.isEnabled = false }

        container = FrameLayout(this@BrowserActivity)
        addView(
            container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
    }

    private fun navigate() {
        val target = address.text?.toString().orEmpty()
        if (target.isBlank()) return
        address.clearFocus()
        runBrowserAction {
            BrowserSessionEngine.navigateFromUser(applicationContext, target)
        }
    }

    private fun runBrowserAction(action: () -> Unit) {
        browserExecutor.execute {
            runCatching(action).onFailure {
                mainHandler.post { status.text = "Browser action failed" }
            }
        }
    }

    private fun weightedButton(label: String, action: () -> Unit): Button =
        button(label, action).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
