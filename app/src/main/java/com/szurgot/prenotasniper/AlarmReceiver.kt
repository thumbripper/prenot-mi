package com.szurgot.prenotasniper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Fires shortly before the release window: posts a high-priority, full-screen
 * notification telling the user to open the app now, and re-arms the next alarm.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREWARM = "com.szurgot.prenotasniper.PREWARM"
        const val CHANNEL_ID = "prewarm"
        const val NOTIF_ID = 7788
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)

        // Re-arm regardless of why we were called (prewarm fire or reboot).
        if (prefs.armed) AlarmScheduler.schedule(context, prefs)

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Release alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Alerts a few minutes before a booking release" }
        nm.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("autostart", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Booking release imminent")
            .setContentText("Open Prenota Sniper now to arm the countdown.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .build()

        nm.notify(NOTIF_ID, notif)
    }
}
