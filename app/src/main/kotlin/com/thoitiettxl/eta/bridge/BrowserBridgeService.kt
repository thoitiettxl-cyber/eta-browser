package com.thoitiettxl.eta.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.thoitiettxl.eta.ui.BrowserActivity
import com.thoitiettxl.eta.ui.MainActivity

internal class BrowserBridgeService : Service() {
    private var server: BrowserBridgeServer? = null
    private var preserveFailureOnDestroy = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        BrowserBridgeRuntime.syncPairing(BrowserPairingStore(this).token())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Starting local browser bridge"))
        if (server == null) {
            val pairingStore = BrowserPairingStore(this)
            val token = pairingStore.token()
            if (token == null) {
                failStart("Pair this device before enabling the bridge")
                return START_NOT_STICKY
            }
            runCatching {
                BrowserBridgeServer(
                    context = this,
                    pairingStore = pairingStore,
                    onPairingRevoked = { stopSelf() },
                ).also { bridge ->
                    bridge.start()
                    server = bridge
                    BrowserBridgeRuntime.running(token)
                    notificationManager().notify(
                        NOTIFICATION_ID,
                        notification(
                            "Listening on ${BrowserBridgeContract.LOOPBACK_HOST}:" +
                                BrowserBridgeContract.FIXED_PORT
                        ),
                    )
                }
            }.onFailure {
                failStart(
                    "Unable to bind ${BrowserBridgeContract.LOOPBACK_HOST}:" +
                        "${BrowserBridgeContract.FIXED_PORT}; another process may be using it"
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.close()
        server = null
        dismissHumanHelp(this)
        if (BrowserPairingStore(this).token() == null) {
            BrowserBridgeRuntime.pairingRevoked()
        } else {
            BrowserBridgeRuntime.stopped(preserveError = preserveFailureOnDestroy)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun failStart(message: String) {
        preserveFailureOnDestroy = true
        BrowserBridgeRuntime.failed(message)
        stopSelf()
    }

    private fun createNotificationChannel() {
        notificationManager().createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ID,
                    "Eta Browser bridge",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when local CLI access to Eta Browser is enabled"
                },
                NotificationChannel(
                    HANDOFF_CHANNEL_ID,
                    "Eta Browser human handoff",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Alerts when an authenticated browser request needs user input"
                },
            ),
        )
    }

    private fun notification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Eta Browser bridge enabled")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        const val ACTION_START = "com.thoitiettxl.eta.action.START_BRIDGE"
        const val ACTION_STOP = "com.thoitiettxl.eta.action.STOP_BRIDGE"

        private const val CHANNEL_ID = "eta_browser_bridge"
        private const val NOTIFICATION_ID = 1001
        private const val HANDOFF_CHANNEL_ID = "eta_browser_handoff"
        private const val HANDOFF_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, BrowserBridgeService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BrowserBridgeService::class.java))
        }

        fun canNotifyHumanHelp(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(HANDOFF_CHANNEL_ID)
            return manager.areNotificationsEnabled() &&
                channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun notifyHumanHelp(context: Context) {
            val openIntent = Intent(context, BrowserActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, HANDOFF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Eta Browser needs your help")
                .setContentText("Open Eta Browser to continue the pending browser task")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(HANDOFF_NOTIFICATION_ID, notification)
        }

        fun dismissHumanHelp(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(HANDOFF_NOTIFICATION_ID)
        }
    }
}
