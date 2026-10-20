package com.wienerlinien.app.ui

import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.wienerlinien.app.R
import org.junit.Test

class VehicleBadgeTest {

    @Test
    fun getVehicleType_ubahnLines_detectedAsMetro() {
        assertThat(VehicleBadgeHelper.getVehicleType("ptMetro", "U1")).isEqualTo(VehicleBadgeHelper.VehicleType.METRO)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "U2")).isEqualTo(VehicleBadgeHelper.VehicleType.METRO)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "U3")).isEqualTo(VehicleBadgeHelper.VehicleType.METRO)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "U4")).isEqualTo(VehicleBadgeHelper.VehicleType.METRO)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "U5")).isEqualTo(VehicleBadgeHelper.VehicleType.METRO)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "U6")).isEqualTo(VehicleBadgeHelper.VehicleType.METRO)
    }

    @Test
    fun getVehicleType_tramLines_detectedAsTram() {
        assertThat(VehicleBadgeHelper.getVehicleType("ptTram", "1")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAM)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "D")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAM)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "O")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAM)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "18")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAM)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "71")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAM)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "WLB")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAM)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "BB")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAM)
    }

    @Test
    fun getVehicleType_cityBusLines_detectedAsBus() {
        assertThat(VehicleBadgeHelper.getVehicleType("ptBusCity", "13A")).isEqualTo(VehicleBadgeHelper.VehicleType.BUS)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "59A")).isEqualTo(VehicleBadgeHelper.VehicleType.BUS)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "7A")).isEqualTo(VehicleBadgeHelper.VehicleType.BUS)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "35A")).isEqualTo(VehicleBadgeHelper.VehicleType.BUS)
    }

    @Test
    fun getVehicleType_nightBusLines_detectedAsNightBus() {
        assertThat(VehicleBadgeHelper.getVehicleType("ptBusNight", "N8")).isEqualTo(VehicleBadgeHelper.VehicleType.NIGHT_BUS)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "N60")).isEqualTo(VehicleBadgeHelper.VehicleType.NIGHT_BUS)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "N25")).isEqualTo(VehicleBadgeHelper.VehicleType.NIGHT_BUS)
    }

    @Test
    fun getVehicleType_trainLines_detectedAsTrain() {
        assertThat(VehicleBadgeHelper.getVehicleType("ptTrainS", "S1")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAIN)
        assertThat(VehicleBadgeHelper.getVehicleType(null, "S45")).isEqualTo(VehicleBadgeHelper.VehicleType.TRAIN)
    }

    @Test
    fun getVehicleIcon_mapsVehicleTypesToCorrectDrawables() {
        assertThat(VehicleBadgeHelper.getVehicleIcon("ptMetro", "U1")).isEqualTo(R.drawable.ic_subway)
        assertThat(VehicleBadgeHelper.getVehicleIcon("ptTram", "D")).isEqualTo(R.drawable.ic_tram)
        assertThat(VehicleBadgeHelper.getVehicleIcon("ptBusCity", "13A")).isEqualTo(R.drawable.ic_bus)
        assertThat(VehicleBadgeHelper.getVehicleIcon("ptBusNight", "N8")).isEqualTo(R.drawable.ic_night_bus)
    }

    @Test
    fun getLineColor_officialWienerLinienColors() {
        // U-Bahn
        assertThat(VehicleBadgeHelper.getLineColor("U1")).isEqualTo(Color.parseColor("#E2001A"))
        assertThat(VehicleBadgeHelper.getLineColor("U2")).isEqualTo(Color.parseColor("#9B27AF"))
        assertThat(VehicleBadgeHelper.getLineColor("U3")).isEqualTo(Color.parseColor("#FF6600"))
        assertThat(VehicleBadgeHelper.getLineColor("U4")).isEqualTo(Color.parseColor("#009A44"))
        assertThat(VehicleBadgeHelper.getLineColor("U5")).isEqualTo(Color.parseColor("#00A59B"))
        assertThat(VehicleBadgeHelper.getLineColor("U6")).isEqualTo(Color.parseColor("#8B6914"))

        // Tram
        assertThat(VehicleBadgeHelper.getLineColor("1", "ptTram")).isEqualTo(Color.parseColor("#D41727"))
        assertThat(VehicleBadgeHelper.getLineColor("D")).isEqualTo(Color.parseColor("#D41727"))

        // City Bus
        assertThat(VehicleBadgeHelper.getLineColor("13A")).isEqualTo(Color.parseColor("#1565C0"))

        // Night Bus
        assertThat(VehicleBadgeHelper.getLineColor("N8")).isEqualTo(Color.parseColor("#2E3B4E"))

        // S-Bahn
        assertThat(VehicleBadgeHelper.getLineColor("S1")).isEqualTo(Color.parseColor("#003D88"))

        // Fallback
        assertThat(VehicleBadgeHelper.getLineColor("UNKNOWN_LINE")).isEqualTo(Color.parseColor("#607D8B"))
    }
}
