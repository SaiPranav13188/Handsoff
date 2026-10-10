package com.handsoff.app.nearby

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.handsoff.app.HandsoffApp
import com.handsoff.app.Notifier

/**
 * Keeps Handsoff listening for paired phones while the app is in the background,
 * so items can be received (and saved to the Gallery) without opening the app.
 */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HandsoffApp.from(this).manager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = Notifier.ready(this, "Your paired phones can send to you")
        try {
            startForeground(Notifier.READY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Missing Bluetooth/Wi-Fi permission: run while the app is open instead.
            stopSelf()
        }
        HandsoffApp.from(this).manager.start()
        return START_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.handsoff.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}
