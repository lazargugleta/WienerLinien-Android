package com.wienerlinien.app.model

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an offline cached transit departure.
 *
 * Correlates departure records with both the user's saved stop ([stopId])
 * and the physical platform RBL number ([rblNumber]).
 */
@Entity(
    tableName = "cached_departures",
    indices = [
        Index(value = ["stopId"], name = "index_cached_departures_stopId"),
        Index(value = ["rblNumber"], name = "index_cached_departures_rblNumber"),
        Index(value = ["cachedAtTimestamp"], name = "index_cached_departures_cachedAtTimestamp")
    ]
)
data class CachedDeparture(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val stopId: Int? = null,
    val rblNumber: Int,
    val lineName: String,
    val lineType: String? = null,
    val destination: String,
    val countdown: Int,
    val isRealtime: Boolean = false,
    val departureTimePlanned: String? = null,
    val departureTimeReal: String? = null,
    val cachedAtTimestamp: Long = System.currentTimeMillis()
) {
    /** Alias for backwards compatibility */
    @get:Ignore
    val towards: String get() = destination
}

// ── Model Mapping & Domain Extensions ────────────────────────────────────────

/**
 * Converts a [CachedDeparture] entity to a domain [DepartureItem].
 */
fun CachedDeparture.toDepartureItem(stopTitle: String = ""): DepartureItem {
    return DepartureItem(
        lineName = lineName,
        towards = destination,
        lineType = lineType ?: "ptBusCity",
        countdown = countdown,
        isRealtime = isRealtime,
        stopTitle = stopTitle,
        rbl = rblNumber,
        departureTimePlanned = departureTimePlanned,
        departureTimeReal = departureTimeReal,
        isCached = true,
        cachedAtMillis = cachedAtTimestamp
    )
}

/**
 * Converts a [CachedDeparture] entity to a domain [DepartureItem] with elapsed time adjustment.
 *
 * When reading cached departures offline, this calculates the elapsed minutes since caching
 * and decrements the remaining countdown accordingly (flooring at 0).
 */
fun CachedDeparture.toDepartureItemWithElapsedAdjustment(
    currentTimeMillis: Long = System.currentTimeMillis(),
    stopTitle: String = ""
): DepartureItem {
    val elapsedMinutes = maxOf(0, ((currentTimeMillis - cachedAtTimestamp) / 60_000L).toInt())
    val adjustedCountdown = maxOf(0, countdown - elapsedMinutes)
    return DepartureItem(
        lineName = lineName,
        towards = destination,
        lineType = lineType ?: "ptBusCity",
        countdown = adjustedCountdown,
        isRealtime = false,
        stopTitle = stopTitle,
        rbl = rblNumber,
        departureTimePlanned = departureTimePlanned,
        departureTimeReal = departureTimeReal,
        isCached = true,
        cachedAtMillis = cachedAtTimestamp
    )
}

/**
 * Maps a domain [DepartureItem] into a persistent [CachedDeparture] entity.
 */
fun DepartureItem.toCachedDeparture(
    stopId: Int? = null,
    cachedAtTimestamp: Long = System.currentTimeMillis()
): CachedDeparture {
    return CachedDeparture(
        stopId = stopId,
        rblNumber = rbl,
        lineName = lineName,
        lineType = lineType,
        destination = towards,
        countdown = countdown,
        isRealtime = isRealtime,
        departureTimePlanned = departureTimePlanned,
        departureTimeReal = departureTimeReal,
        cachedAtTimestamp = cachedAtTimestamp
    )
}
