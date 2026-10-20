package com.wienerlinien.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CountdownFormatterTest {

    @Test
    fun formatCountdown_zero_returnsNow() {
        assertThat(CountdownFormatter.formatCountdown(0)).isEqualTo("now")
    }

    @Test
    fun formatCountdown_negative_returnsNow() {
        assertThat(CountdownFormatter.formatCountdown(-1)).isEqualTo("now")
        assertThat(CountdownFormatter.formatCountdown(-10)).isEqualTo("now")
    }

    @Test
    fun formatCountdown_oneMinute_returnsSingularMin() {
        assertThat(CountdownFormatter.formatCountdown(1)).isEqualTo("1 min")
    }

    @Test
    fun formatCountdown_multipleMinutes_returnsPluralMin() {
        assertThat(CountdownFormatter.formatCountdown(2)).isEqualTo("2 min")
        assertThat(CountdownFormatter.formatCountdown(5)).isEqualTo("5 min")
        assertThat(CountdownFormatter.formatCountdown(30)).isEqualTo("30 min")
        assertThat(CountdownFormatter.formatCountdown(120)).isEqualTo("120 min")
    }

    @Test
    fun extractClockTime_isoDateTimeWithTimezone_extractsHoursMinutes() {
        val result = CountdownFormatter.extractClockTime("2026-10-14T10:01:00.000+0200")
        assertThat(result).isEqualTo("10:01")
    }

    @Test
    fun extractClockTime_isoDateTimeNoMillis_extractsHoursMinutes() {
        val result = CountdownFormatter.extractClockTime("2026-10-14T18:45:00")
        assertThat(result).isEqualTo("18:45")
    }

    @Test
    fun extractClockTime_standardTimeWithSeconds_extractsHoursMinutes() {
        val result = CountdownFormatter.extractClockTime("07:23:59")
        assertThat(result).isEqualTo("07:23")
    }

    @Test
    fun extractClockTime_singleDigitHour_padsWithLeadingZero() {
        assertThat(CountdownFormatter.extractClockTime("9:05")).isEqualTo("09:05")
        assertThat(CountdownFormatter.extractClockTime("8:30:00")).isEqualTo("08:30")
        assertThat(CountdownFormatter.extractClockTime("2026-10-14T9:05:00")).isEqualTo("09:05")
    }

    @Test
    fun extractClockTime_alreadyHourMinute_returnsAsIs() {
        val result = CountdownFormatter.extractClockTime("23:59")
        assertThat(result).isEqualTo("23:59")
    }

    @Test
    fun extractClockTime_blankOrInvalidString_returnsNull() {
        assertThat(CountdownFormatter.extractClockTime("")).isNull()
        assertThat(CountdownFormatter.extractClockTime("   ")).isNull()
        assertThat(CountdownFormatter.extractClockTime("not_a_time")).isNull()
    }

    @Test
    fun formatClockTime_prefersRealTimeOverPlannedTime() {
        val timeReal = "2026-10-14T14:35:00.000+0200"
        val timePlanned = "2026-10-14T14:30:00.000+0200"

        val formatted = CountdownFormatter.formatClockTime(timeReal, timePlanned, countdown = 5)
        assertThat(formatted).isEqualTo("14:35")
    }

    @Test
    fun formatClockTime_fallsBackToPlannedTimeWhenRealTimeIsNull() {
        val timePlanned = "2026-10-14T14:30:00.000+0200"

        val formatted = CountdownFormatter.formatClockTime(null, timePlanned, countdown = 5)
        assertThat(formatted).isEqualTo("14:30")
    }

    @Test
    fun formatClockTime_fallsBackToNowPlusCountdownWhenBothStringsNull() {
        val baseMillis = 1700000000000L // arbitrary fixed timestamp
        val countdown = 10
        val expected = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(baseMillis + 10 * 60_000L))

        val formatted = CountdownFormatter.formatClockTime(null, null, countdown = countdown, nowMillis = baseMillis)
        assertThat(formatted).isEqualTo(expected)
    }

    @Test
    fun formatUpdatedTime_validTimestamp_returnsFormattedClockTime() {
        val timestamp = 1700000000000L
        val expected = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

        val result = CountdownFormatter.formatUpdatedTime(timestamp)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun formatUpdatedTime_zeroOrNegative_returnsEmptyString() {
        assertThat(CountdownFormatter.formatUpdatedTime(0)).isEmpty()
        assertThat(CountdownFormatter.formatUpdatedTime(-1)).isEmpty()
    }
}
