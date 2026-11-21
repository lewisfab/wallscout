package dev.tristan.freshwall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** A quiet notification when automatic changes keep failing, cleared once one works again. */
object Notifier {
    private const val CHANNEL = "problems"
    private const val ID = 1

    fun automaticChangeFailed(context: Context, reason: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Problems", NotificationManager.IMPORTANCE_LOW).apply {
                description = "When automatic wallpaper changes keep failing"
            }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_mono)
                .setContentTitle("Wallscout couldn't change your wallpaper")
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(reason))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
