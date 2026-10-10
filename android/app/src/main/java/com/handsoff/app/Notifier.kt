package com.handsoff.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.handsoff.app.data.ReceivedEntry
import com.handsoff.app.nearby.TransferService

object Notifier {
    private const val CHANNEL_READY = "ready"
    private const val CHANNEL_RECEIVED = "received"
    const val READY_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_READY, "Ready to receive", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Handsoff can receive from your paired phones"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECEIVED, "Received items", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Photos and files sent to you from a paired phone"
            },
        )
    }

    private fun openApp(context: Context, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun ready(context: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            context,
            2,
            Intent(context, TransferService::class.java).setAction(TransferService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_stat_hand)
            .setContentTitle("Handsoff is ready")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context, 1))
            .addAction(0, "Turn off", stop)
            .build()
    }

    /** A one-line heads-up (reactions, clipboard, unsend). [open] is what tapping it opens. */
    fun simple(context: Context, title: String, text: String, open: Intent? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val tap = if (open != null) {
            PendingIntent.getActivity(context, 4, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else openApp(context, 5)
        val n = NotificationCompat.Builder(context, CHANNEL_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat_hand)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100000).toInt() + 200000, n)
        } catch (e: SecurityException) {
            /* notifications not allowed */
        }
    }

    fun received(context: Context, from: String, entries: List<ReceivedEntry>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val photos = entries.count { it.mime.startsWith("image/") }
        val videos = entries.count { it.mime.startsWith("video/") }
        val files = entries.size - photos - videos
        val what = listOfNotNull(
            photos.takeIf { it > 0 }?.let { "$it photo${if (it > 1) "s" else ""}" },
            videos.takeIf { it > 0 }?.let { "$it video${if (it > 1) "s" else ""}" },
            files.takeIf { it > 0 }?.let { "$it file${if (it > 1) "s" else ""}" },
        ).joinToString(", ")
        val n = NotificationCompat.Builder(context, CHANNEL_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat_hand)
            .setContentTitle("$from sent you $what")
            .setContentText("Saved to your Gallery · tap to view")
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 3))
            .build()
        try {
            NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
        } catch (e: SecurityException) {
            /* notifications not allowed */
        }
    }
}
