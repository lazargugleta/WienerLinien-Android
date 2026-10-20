package com.wienerlinien.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CountdownFormatter {

    /**
     * Formats integer countdown minutes into display text.
     * Examples: 0 -> "now", 1 -> "1 min", 5 -> "5 min"
     */
    fun formatCountdown(countdown: Int): String {
        return when {
            countdown <= 0 -> "now"
            countdown == 1 -> "1 min"
            else -> "$countdown min"
        }
    }

    /**
     * Formats a time string into 24-hour "HH:mm" clock format.
     * Handles ISO timestamps ("2026-10-14T10:01:00.000+0200"), standard times ("10:01:00"),
     * and falls back to nowMillis + countdown if string is absent or invalid.
     */
    fun formatClockTime(
        timeReal: String?,
        timePlanned: String?,
        countdown: Int = 0,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val target = timeReal?.takeIf { it.isNotBlank() }
            ?: timePlanned?.takeIf { it.isNotBlank() }

        if (target != null) {
            val extracted = extractClockTime(target)
            if (extracted != null) return extracted
        }

        // Fallback: calculate from current time + countdown minutes
        val targetMillis = nowMillis + (countdown * 60_000L)
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(targetMillis))
    }

    /**
     * Extracts HH:mm from various ISO or standard time string formats.
     */
    fun extractClockTime(timeStr: String): String? {
        val trimmed = timeStr.trim()
        if (trimmed.isEmpty()) return null

        // 1. Check for standard 'T' separator (e.g. 2026-10-14T10:01:00.000+0200 or 2026-10-14T9:05:00)
        val tIndex = trimmed.indexOf('T')
        if (tIndex != -1 && trimmed.length >= tIndex + 4) {
            val timePart = trimmed.substring(tIndex + 1)
            val parts = timePart.split(":")
            if (parts.size >= 2) {
                val hour = parts[0].filter { it.isDigit() }.takeLast(2)
                val minute = parts[1].filter { it.isDigit() }.take(2)
                if (hour.isNotEmpty() && minute.isNotEmpty()) {
                    return "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}"
                }
            }
        }

        // 2. Check for HH:mm:ss, HH:mm, H:mm:ss, H:mm
        val colonParts = trimmed.split(":")
        if (colonParts.size >= 2) {
            val hour = colonParts[0].filter { it.isDigit() }.takeLast(2)
            val minute = colonParts[1].filter { it.isDigit() }.take(2)
            if (hour.isNotEmpty() && minute.isNotEmpty()) {
                return "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}"
            }
        }

        return null
    }

    /**
     * Formats the updated timestamp into a readable string.
     */
    fun formatUpdatedTime(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
