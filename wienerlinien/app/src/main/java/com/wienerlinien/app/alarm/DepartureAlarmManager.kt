package com.wienerlinien.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object DepartureAlarmManager {

    fun scheduleDepartureReminder(
        context: Context,
        lineName: String,
        destination: String,
        stopName: String,
        countdownMinutes: Int,
        minutesBefore: Int = 3
    ): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val leadTime = minOf(minutesBefore, maxOf(1, countdownMinutes - 1))
        val delayMinutes = countdownMinutes - leadTime
        val triggerAtMillis = System.currentTimeMillis() + (delayMinutes * 60_000L)

        val intent = Intent(context, DepartureAlarmReceiver::class.java).apply {
            putExtra(DepartureAlarmReceiver.EXTRA_LINE, lineName)
            putExtra(DepartureAlarmReceiver.EXTRA_DESTINATION, destination)
            putExtra(DepartureAlarmReceiver.EXTRA_STOP, stopName)
            putExtra(DepartureAlarmReceiver.EXTRA_MINUTES, leadTime)
        }

        val requestCode = (lineName.hashCode() xor stopName.hashCode() xor countdownMinutes).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
