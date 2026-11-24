package com.wienerlinien.app.alarm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DepartureAlarmManagerTest {

    @Test
    fun computeLeadAndDelayMinutes_normalCase_subtractsLeadTime() {
        val (lead, delay) = DepartureAlarmManager.computeLeadAndDelayMinutes(countdownMinutes = 10, minutesBefore = 3)
        assertThat(lead).isEqualTo(3)
        assertThat(delay).isEqualTo(7)
    }

    @Test
    fun computeLeadAndDelayMinutes_leadLargerThanCountdown_clampsToCountdownMinusOne() {
        val (lead, delay) = DepartureAlarmManager.computeLeadAndDelayMinutes(countdownMinutes = 3, minutesBefore = 5)
        assertThat(lead).isEqualTo(2)
        assertThat(delay).isEqualTo(1)
    }

    @Test
    fun computeLeadAndDelayMinutes_countdownOneOrZero_clampsSafelyWithoutNegativeDelay() {
        val (lead1, delay1) = DepartureAlarmManager.computeLeadAndDelayMinutes(countdownMinutes = 1, minutesBefore = 3)
        assertThat(lead1).isEqualTo(1)
        assertThat(delay1).isEqualTo(0)

        val (lead0, delay0) = DepartureAlarmManager.computeLeadAndDelayMinutes(countdownMinutes = 0, minutesBefore = 3)
        assertThat(lead0).isEqualTo(1)
        assertThat(delay0).isEqualTo(0)
    }

    @Test
    fun computeLeadAndDelayMinutes_zeroOrNegativeMinutesBefore_clampsLeadToOne() {
        val (lead, delay) = DepartureAlarmManager.computeLeadAndDelayMinutes(countdownMinutes = 5, minutesBefore = 0)
        assertThat(lead).isEqualTo(1)
        assertThat(delay).isEqualTo(4)
    }
}
