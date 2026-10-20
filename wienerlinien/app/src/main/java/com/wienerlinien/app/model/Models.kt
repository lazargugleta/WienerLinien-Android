package com.wienerlinien.app.model

import com.google.gson.annotations.SerializedName
import androidx.room.Entity
import androidx.room.PrimaryKey

// ── Top-level API response ──────────────────────────────────────────────────

data class MonitorResponse(
    @SerializedName("data") val data: MonitorData?,
    @SerializedName("message") val message: ApiMessage?
)

data class MonitorData(
    @SerializedName("monitors") val monitors: List<Monitor>?
)

data class ApiMessage(
    @SerializedName("value") val value: String?,
    @SerializedName("messageCode") val messageCode: Int?,
    @SerializedName("serverTime") val serverTime: String?
)

// ── Monitor (one per RBL / platform) ───────────────────────────────────────

data class Monitor(
    @SerializedName("locationStop") val locationStop: LocationStop?,
    @SerializedName("lines") val lines: List<Line>?
)

data class LocationStop(
    @SerializedName("properties") val properties: StopProperties?,
    @SerializedName("geometry") val geometry: StopGeometry?
)

data class StopProperties(
    @SerializedName("name") val name: String?,         // DIVA number
    @SerializedName("title") val title: String?,       // Human-readable stop name e.g. "Stephansplatz"
    @SerializedName("municipality") val municipality: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("attributes") val attributes: StopAttributes?
)

data class StopAttributes(
    @SerializedName("rbl") val rbl: Int?               // RBL number used for queries
)

data class StopGeometry(
    @SerializedName("coordinates") val coordinates: List<Double>?  // [lon, lat]
)

// ── Line ───────────────────────────────────────────────────────────────────

data class Line(
    @SerializedName("name") val name: String?,         // e.g. "U1", "13A", "D"
    @SerializedName("towards") val towards: String?,   // Direction / destination
    @SerializedName("direction") val direction: String?,  // "H" = hin, "R" = retour
    @SerializedName("richtungsId") val richtungsId: String?,
    @SerializedName("barrierFree") val barrierFree: Boolean?,
    @SerializedName("realtimeSupported") val realtimeSupported: Boolean?,
    @SerializedName("trafficjam") val trafficjam: Boolean?,
    @SerializedName("type") val type: String?,         // ptMetro, ptTram, ptBusCity, ptBusNight
    @SerializedName("departures") val departures: Departures?
) {
    fun lineTypeIcon(): String = when (type) {
        "ptMetro" -> "U"
        "ptTram" -> "T"
        "ptBusCity" -> "B"
        "ptBusNight" -> "N"
        else -> "?"
    }

    fun lineColor(): Long = when {
        name?.startsWith("U1") == true -> 0xFFE2001A  // Red
        name?.startsWith("U2") == true -> 0xFF9B27AF  // Purple
        name?.startsWith("U3") == true -> 0xFFFF6600  // Orange
        name?.startsWith("U4") == true -> 0xFF009A44  // Green
        name?.startsWith("U6") == true -> 0xFF8B6914  // Brown
        type == "ptTram" -> 0xFFD41727               // Tram red
        type == "ptBusCity" -> 0xFF1565C0             // Bus blue
        type == "ptBusNight" -> 0xFF2E3B4E            // Night bus dark
        else -> 0xFF666666
    }
}

data class Departures(
    @SerializedName("departure") val departure: List<Departure>?
)

data class Departure(
    @SerializedName("departureTime") val departureTime: DepartureTime?,
    @SerializedName("vehicle") val vehicle: Vehicle?
)

data class DepartureTime(
    @SerializedName("timePlanned") val timePlanned: String?,
    @SerializedName("timeReal") val timeReal: String?,
    @SerializedName("countdown") val countdown: Int?   // Minutes until departure
)

data class Vehicle(
    @SerializedName("name") val name: String?,
    @SerializedName("towards") val towards: String?,
    @SerializedName("direction") val direction: String?,
    @SerializedName("barrierFree") val barrierFree: Boolean?,
    @SerializedName("realtimeSupported") val realtimeSupported: Boolean?,
    @SerializedName("type") val type: String?
)

// ── Local DB entity for saved stops ────────────────────────────────────────

@Entity(tableName = "saved_stops")
data class SavedStop(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val rblNumber: Int,
    val stopName: String,
    val lineName: String?,        // null = show all lines
    val lineType: String?,        // ptMetro / ptTram / ptBusCity / ptBusNight
    val direction: String?,       // Stores platform/steig info
    val platformLines: String?,   // Comma separated lines usually serving this steig
    val customLabel: String?,     // user-defined label
    val maxDepartures: Int = 3,   // how many next trains to show
    val sortOrder: Int = 0,
    val isHome: Boolean = false,
    val isWork: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

// ── Disruptions / Service Alerts (trafficInfoList) ──────────────────────────

data class TrafficInfoResponse(
    @SerializedName("data") val data: TrafficInfoData?
)

data class TrafficInfoData(
    @SerializedName("trafficInfos") val trafficInfos: List<TrafficInfoItem>?
)

data class TrafficInfoItem(
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("relatedLines") val relatedLines: List<String>? = null,
    @SerializedName("relatedStops") val relatedStops: List<Int>? = null
)

// ── Departure Group (Contract for Repository ↔ UI / Widget) ────────────────

data class DepartureGroup(
    val stop: SavedStop,
    val departures: List<DepartureItem>,
    val isFromCache: Boolean = false,
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)

// ── UI model for departure cards ───────────────────────────────────────────

data class DepartureItem(
    val lineName: String,
    val towards: String,
    val lineType: String? = "ptBusCity",
    val countdown: Int,           // minutes
    val isRealtime: Boolean = true,
    val isBarrierFree: Boolean = false,
    val hasTrafficJam: Boolean = false,
    val stopTitle: String = "",
    val rbl: Int = 0,
    val departureTimePlanned: String? = null,
    val departureTimeReal: String? = null,
    val isCached: Boolean = false,
    val cachedAtMillis: Long = 0L
) {
    /** Alias for presentation and widget layers */
    val destination: String get() = towards
}

