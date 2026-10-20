package com.wienerlinien.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wienerlinien.app.R
import com.wienerlinien.app.ui.MainActivity

class DepartureAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "wienerlinien_departure_alarms"
        const val EXTRA_LINE = "extra_line"
        const val EXTRA_DESTINATION = "extra_destination"
        const val EXTRA_STOP = "extra_stop"
        const val EXTRA_MINUTES = "extra_minutes"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val line = intent.getStringExtra(EXTRA_LINE) ?: "Transit"
        val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: ""
        val stop = intent.getStringExtra(EXTRA_STOP) ?: ""
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 5)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Departure Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you ahead of upcoming train, tram, and bus departures"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "🚇 $line to $destination"
        val message = if (stop.isNotBlank()) {
            "Departs from $stop in $minutes min! Time to head out."
        } else {
            "Departs in $minutes min! Time to head out."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_subway)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notification)
    }
}
