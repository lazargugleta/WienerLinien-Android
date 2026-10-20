package com.wienerlinien.app.ui

import android.graphics.Color
import com.wienerlinien.app.R

object VehicleBadgeHelper {

    enum class VehicleType(val label: String) {
        METRO("U-Bahn"),
        TRAM("Tram"),
        BUS("Bus"),
        NIGHT_BUS("Night Bus"),
        TRAIN("S-Bahn"),
        UNKNOWN("Transit")
    }

    /**
     * Determines the vehicle type based on lineType string and line name.
     */
    fun getVehicleType(lineType: String?, lineName: String = ""): VehicleType {
        val trimmedLine = lineName.trim().uppercase()
        val trimmedType = lineType?.trim() ?: ""

        return when {
            trimmedType.equals("ptMetro", ignoreCase = true) ||
                    trimmedLine.startsWith("U1") || trimmedLine.startsWith("U2") ||
                    trimmedLine.startsWith("U3") || trimmedLine.startsWith("U4") ||
                    trimmedLine.startsWith("U5") || trimmedLine.startsWith("U6") -> VehicleType.METRO

            trimmedType.equals("ptBusNight", ignoreCase = true) ||
                    trimmedLine.startsWith("N") -> VehicleType.NIGHT_BUS

            trimmedType.equals("ptTram", ignoreCase = true) ||
                    isTramLine(trimmedLine) -> VehicleType.TRAM

            trimmedType.equals("ptBusCity", ignoreCase = true) ||
                    trimmedLine.endsWith("A") || trimmedLine.endsWith("B") -> VehicleType.BUS

            trimmedType.equals("ptTrainS", ignoreCase = true) ||
                    trimmedLine.startsWith("S") -> VehicleType.TRAIN

            else -> VehicleType.UNKNOWN
        }
    }

    /**
     * Returns the appropriate vector icon drawable resource ID for a line.
     */
    fun getVehicleIcon(lineType: String?, lineName: String = ""): Int {
        return when (getVehicleType(lineType, lineName)) {
            VehicleType.METRO -> R.drawable.ic_subway
            VehicleType.TRAM -> R.drawable.ic_tram
            VehicleType.BUS -> R.drawable.ic_bus
            VehicleType.NIGHT_BUS -> R.drawable.ic_night_bus
            VehicleType.TRAIN -> R.drawable.ic_subway
            VehicleType.UNKNOWN -> R.drawable.ic_bus
        }
    }

    /**
     * Returns the official Wiener Linien hex color code as an Int.
     */
    fun getLineColor(lineName: String, lineType: String? = null): Int {
        val trimmed = lineName.trim().uppercase()
        val type = lineType?.trim() ?: ""

        return when {
            trimmed.startsWith("U1") -> Color.parseColor("#E2001A")
            trimmed.startsWith("U2") -> Color.parseColor("#9B27AF")
            trimmed.startsWith("U3") -> Color.parseColor("#FF6600")
            trimmed.startsWith("U4") -> Color.parseColor("#009A44")
            trimmed.startsWith("U5") -> Color.parseColor("#00A59B")
            trimmed.startsWith("U6") -> Color.parseColor("#8B6914")
            type.equals("ptBusNight", ignoreCase = true) || trimmed.startsWith("N") -> Color.parseColor("#2E3B4E")
            type.equals("ptTram", ignoreCase = true) || isTramLine(trimmed) -> Color.parseColor("#D41727")
            type.equals("ptBusCity", ignoreCase = true) || trimmed.endsWith("A") || trimmed.endsWith("B") -> Color.parseColor("#1565C0")
            type.equals("ptTrainS", ignoreCase = true) || trimmed.startsWith("S") -> Color.parseColor("#003D88")
            else -> Color.parseColor("#607D8B")
        }
    }

    private fun isTramLine(name: String): Boolean {
        if (name == "D" || name == "O" || name == "WBR" || name == "WLB" || name == "BB") return true
        val num = name.toIntOrNull() ?: return false
        // Vienna tram lines are numeric without letter suffixes
        return num in 1..71
    }
}
