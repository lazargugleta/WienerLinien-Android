package com.wienerlinien.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object DepartureAlarmManager {

    internal fun computeLeadAndDelayMinutes(countdownMinutes: Int, minutesBefore: Int): Pair<Int, Int> {
        val safeCountdown = maxOf(1, countdownMinutes)
        val leadTime = minOf(maxOf(1, minutesBefore), maxOf(1, safeCountdown - 1))
        val delayMinutes = maxOf(0, safeCountdown - leadTime)
        return leadTime to delayMinutes
    }

    fun scheduleDepartureReminder(
        context: Context,
        lineName: String,
        destination: String,
        stopName: String,
        countdownMinutes: Int,
        minutesBefore: Int = 3
    ): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false

        val (leadTime, delayMinutes) = computeLeadAndDelayMinutes(countdownMinutes, minutesBefore)
        val triggerAtMillis = System.currentTimeMillis() + (delayMinutes * 60_000L)

        val intent = Intent(context, DepartureAlarmReceiver::class.java).apply {
            putExtra(DepartureAlarmReceiver.EXTRA_LINE, lineName)
            putExtra(DepartureAlarmReceiver.EXTRA_DESTINATION, destination)
            putExtra(DepartureAlarmReceiver.EXTRA_STOP, stopName)
            putExtra(DepartureAlarmReceiver.EXTRA_MINUTES, leadTime)
        }

        val requestCode = lineName.hashCode() xor stopName.hashCode() xor countdownMinutes
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                // Graceful fallback on Android 12+ when exact alarm permission is not granted
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            true
        } catch (_: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
