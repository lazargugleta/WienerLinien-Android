package com.wienerlinien.app.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelMappingTest {

    @Test
    fun cachedDeparture_conversionTo_departureItem_preservesFields() {
        val cached = CachedDeparture(
            id = 1L,
            stopId = 101,
            rblNumber = 4116,
            lineName = "U1",
            lineType = "ptMetro",
            destination = "Leopoldau",
            countdown = 5,
            isRealtime = true,
            departureTimePlanned = "2026-10-14T10:00:00.000+0200",
            departureTimeReal = "2026-10-14T10:01:00.000+0200",
            cachedAtTimestamp = 1700000000000L
        )

        val item = cached.toDepartureItem(stopTitle = "Stephansplatz")

        assertThat(item.lineName).isEqualTo("U1")
        assertThat(item.towards).isEqualTo("Leopoldau")
        assertThat(item.destination).isEqualTo("Leopoldau")
        assertThat(item.lineType).isEqualTo("ptMetro")
        assertThat(item.countdown).isEqualTo(5)
        assertThat(item.isRealtime).isTrue()
        assertThat(item.stopTitle).isEqualTo("Stephansplatz")
        assertThat(item.rbl).isEqualTo(4116)
        assertThat(item.departureTimePlanned).isEqualTo("2026-10-14T10:00:00.000+0200")
        assertThat(item.departureTimeReal).isEqualTo("2026-10-14T10:01:00.000+0200")
        assertThat(item.isCached).isTrue()
        assertThat(item.cachedAtMillis).isEqualTo(1700000000000L)
    }

    @Test
    fun cachedDeparture_elapsedCountdownAdjustment_decrementsAccurately() {
        val baseTime = 1700000000000L
        val cached = CachedDeparture(
            rblNumber = 4200,
            lineName = "U2",
            destination = "Seestadt",
            countdown = 8,
            isRealtime = true,
            cachedAtTimestamp = baseTime
        )

        // 3 minutes elapsed
        val threeMinutesLater = baseTime + (3 * 60 * 1000L)
        val adjustedItem = cached.toDepartureItemWithElapsedAdjustment(
            currentTimeMillis = threeMinutesLater,
            stopTitle = "Karlsplatz"
        )

        assertThat(adjustedItem.countdown).isEqualTo(5) // 8 - 3 = 5
        assertThat(adjustedItem.isRealtime).isFalse()    // Cached data is marked scheduled/not live
        assertThat(adjustedItem.isCached).isTrue()
        assertThat(adjustedItem.stopTitle).isEqualTo("Karlsplatz")
    }

    @Test
    fun cachedDeparture_elapsedCountdownAdjustment_floorsAtZero() {
        val baseTime = 1700000000000L
        val cached = CachedDeparture(
            rblNumber = 4200,
            lineName = "U4",
            destination = "Hütteldorf",
            countdown = 4,
            isRealtime = true,
            cachedAtTimestamp = baseTime
        )

        // 10 minutes elapsed: 4 - 10 would be -6, should floor to 0
        val tenMinutesLater = baseTime + (10 * 60 * 1000L)
        val adjustedItem = cached.toDepartureItemWithElapsedAdjustment(
            currentTimeMillis = tenMinutesLater
        )

        assertThat(adjustedItem.countdown).isEqualTo(0)
    }

    @Test
    fun departureItem_conversionTo_cachedDeparture_preservesFields() {
        val item = DepartureItem(
            lineName = "13A",
            towards = "Hauptbahnhof",
            lineType = "ptBusCity",
            countdown = 6,
            isRealtime = true,
            stopTitle = "Neubaugasse",
            rbl = 5300,
            departureTimePlanned = "2026-10-14T12:00:00.000+0200",
            departureTimeReal = "2026-10-14T12:01:00.000+0200"
        )

        val cachedTime = 1700000000000L
        val cached = item.toCachedDeparture(stopId = 42, cachedAtTimestamp = cachedTime)

        assertThat(cached.stopId).isEqualTo(42)
        assertThat(cached.rblNumber).isEqualTo(5300)
        assertThat(cached.lineName).isEqualTo("13A")
        assertThat(cached.destination).isEqualTo("Hauptbahnhof")
        assertThat(cached.towards).isEqualTo("Hauptbahnhof")
        assertThat(cached.lineType).isEqualTo("ptBusCity")
        assertThat(cached.countdown).isEqualTo(6)
        assertThat(cached.isRealtime).isTrue()
        assertThat(cached.departureTimePlanned).isEqualTo("2026-10-14T12:00:00.000+0200")
        assertThat(cached.departureTimeReal).isEqualTo("2026-10-14T12:01:00.000+0200")
        assertThat(cached.cachedAtTimestamp).isEqualTo(cachedTime)
    }

    @Test
    fun departureItem_destinationAlias_equalsTowards() {
        val item = DepartureItem(
            lineName = "D",
            towards = "Nußdorf",
            countdown = 2,
            isRealtime = false
        )

        assertThat(item.destination).isEqualTo("Nußdorf")
        assertThat(item.towards).isEqualTo("Nußdorf")
    }

    @Test
    fun departureGroup_fieldsAndDefaults() {
        val stop = SavedStop(
            id = 1,
            rblNumber = 4116,
            stopName = "Stephansplatz",
            lineName = "U1",
            lineType = "ptMetro",
            direction = "1",
            platformLines = "U1, U3",
            customLabel = "Home"
        )
        val departures = listOf(
            DepartureItem("U1", "Oberlaa", "ptMetro", 2, true)
        )
        val now = System.currentTimeMillis()

        val group = DepartureGroup(
            stop = stop,
            departures = departures,
            isFromCache = false,
            lastUpdatedMillis = now
        )

        assertThat(group.stop.stopName).isEqualTo("Stephansplatz")
        assertThat(group.departures).hasSize(1)
        assertThat(group.isFromCache).isFalse()
        assertThat(group.lastUpdatedMillis).isEqualTo(now)
    }

    @Test
    fun savedStop_defaultValues_areValid() {
        val stop = SavedStop(
            rblNumber = 4100,
            stopName = "Schwedenplatz",
            lineName = null,
            lineType = null,
            direction = null,
            platformLines = null,
            customLabel = null
        )

        assertThat(stop.id).isEqualTo(0)
        assertThat(stop.maxDepartures).isEqualTo(3)
        assertThat(stop.sortOrder).isEqualTo(0)
        assertThat(stop.addedAt).isGreaterThan(0L)
    }
}
