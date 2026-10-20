package com.wienerlinien.app.model

import com.google.common.truth.Truth.assertThat
import com.wienerlinien.app.api.WienerLinienApiService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import retrofit2.Response

class DepartureRepositoryTest {

    private lateinit var mockDb: AppDatabase
    private lateinit var mockSavedStopDao: SavedStopDao
    private lateinit var mockCachedDepartureDao: CachedDepartureDao
    private lateinit var mockApi: WienerLinienApiService
    private lateinit var repository: DepartureRepository

    @Before
    fun setUp() {
        mockDb = mock()
        mockSavedStopDao = mock()
        mockCachedDepartureDao = mock()
        mockApi = mock()

        whenever(mockDb.savedStopDao()).thenReturn(mockSavedStopDao)
        whenever(mockDb.cachedDepartureDao()).thenReturn(mockCachedDepartureDao)
        whenever(mockSavedStopDao.getAllStops()).thenReturn(flowOf(emptyList()))

        repository = DepartureRepository(mockDb, mockApi)
    }

    private fun createMockMonitorResponse(
        stopTitle: String = "Stephansplatz",
        rbl: Int = 4116,
        lineName: String = "U1",
        lineType: String = "ptMetro",
        towards: String = "Oberlaa",
        countdown: Int = 3,
        timePlanned: String? = "2026-10-14T10:00:00.000+0200",
        timeReal: String? = "2026-10-14T10:01:00.000+0200",
        realtimeSupported: Boolean = true
    ): MonitorResponse {
        val dep = Departure(
            departureTime = DepartureTime(
                timePlanned = timePlanned,
                timeReal = timeReal,
                countdown = countdown
            ),
            vehicle = Vehicle(
                name = lineName,
                towards = towards,
                direction = "H",
                barrierFree = true,
                realtimeSupported = realtimeSupported,
                type = lineType
            )
        )
        val line = Line(
            name = lineName,
            towards = towards,
            direction = "H",
            richtungsId = "1",
            barrierFree = true,
            realtimeSupported = realtimeSupported,
            trafficjam = false,
            type = lineType,
            departures = Departures(listOf(dep))
        )
        val monitor = Monitor(
            locationStop = LocationStop(
                properties = StopProperties(
                    name = "60200001",
                    title = stopTitle,
                    municipality = "Wien",
                    type = "stop",
                    attributes = StopAttributes(rbl = rbl)
                ),
                geometry = null
            ),
            lines = listOf(line)
        )
        return MonitorResponse(
            data = MonitorData(listOf(monitor)),
            message = ApiMessage(value = "OK", messageCode = 1, serverTime = null)
        )
    }

    @Test
    fun fetchDepartures_emptyRblList_returnsSuccessEmptyListWithoutNetworkCall() = runTest {
        val result = repository.fetchDepartures(emptyList<Int>())

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()

        // Zero network calls made -> protects against API error 321
        verify(mockApi, never()).getMonitor(any(), any())
    }

    @Test
    fun fetchDepartures_emptyStopsList_returnsSuccessEmptyListWithoutNetworkCall() = runTest {
        val result = repository.fetchDepartures(emptyList<SavedStop>())

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
        verify(mockApi, never()).getMonitor(any(), any())
    }

    @Test
    fun fetchDepartures_invalidRblsOnly_returnsSuccessEmptyListWithoutNetworkCall() = runTest {
        val result = repository.fetchDepartures(listOf(0, -1, -99))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
        verify(mockApi, never()).getMonitor(any(), any())
    }

    @Test
    fun fetchDepartures_onlineSuccess_persistsDeparturesInRoomAndReturnsFreshData() = runTest {
        val rbl = 4116
        val apiResponse = createMockMonitorResponse(
            stopTitle = "Stephansplatz",
            rbl = rbl,
            lineName = "U1",
            towards = "Oberlaa",
            countdown = 4
        )

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(1)
        val item = items[0]
        assertThat(item.lineName).isEqualTo("U1")
        assertThat(item.towards).isEqualTo("Oberlaa")
        assertThat(item.countdown).isEqualTo(4)
        assertThat(item.isCached).isFalse()

        // Verify cached atomically in Room
        verify(mockCachedDepartureDao).replaceDeparturesForRbls(eq(listOf(rbl)), any())
    }

    @Test
    fun fetchDepartures_networkFailure_fallsBackToRoomCache() = runTest {
        val rbl = 4116
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any()))
            .thenThrow(RuntimeException("Network unreachable in subway tunnel"))

        val cachedDeparture = CachedDeparture(
            rblNumber = rbl,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 5,
            isRealtime = true,
            cachedAtTimestamp = System.currentTimeMillis()
        )
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl)))
            .thenReturn(listOf(cachedDeparture))

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(1)
        val item = items[0]
        assertThat(item.lineName).isEqualTo("U1")
        assertThat(item.towards).isEqualTo("Oberlaa")
        assertThat(item.isCached).isTrue()
        assertThat(item.isRealtime).isFalse() // offline cached is marked not live
    }

    @Test
    fun fetchDepartures_networkFailureWithEmptyCache_returnsFailureResult() = runTest {
        val rbl = 4116
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any()))
            .thenThrow(RuntimeException("No internet and no cache"))
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl)))
            .thenReturn(emptyList())

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("No internet and no cache")
    }

    @Test
    fun fetchDepartures_cachedCountdown_adjustsBasedOnElapsedMinutes() = runTest {
        val rbl = 4200
        val baseTime = System.currentTimeMillis() - (4 * 60 * 1000L) // cached 4 mins ago

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any()))
            .thenThrow(RuntimeException("Offline"))

        val cachedDeparture = CachedDeparture(
            rblNumber = rbl,
            lineName = "U2",
            destination = "Seestadt",
            countdown = 10,
            cachedAtTimestamp = baseTime
        )
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl)))
            .thenReturn(listOf(cachedDeparture))

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        val item = result.getOrThrow()[0]
        assertThat(item.countdown).isEqualTo(6) // 10 - 4 = 6
        assertThat(item.isCached).isTrue()
    }

    @Test
    fun fetchDeparturesForStop_filtersByLineName() = runTest {
        val rbl = 4100
        val depU1 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "2026-10-14T10:01:00", 2),
            vehicle = Vehicle("U1", "Leopoldau", "H", true, true, "ptMetro")
        )
        val lineU1 = Line("U1", "Leopoldau", "H", "1", true, true, false, "ptMetro", Departures(listOf(depU1)))

        val depU4 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "2026-10-14T10:02:00", 3),
            vehicle = Vehicle("U4", "Hütteldorf", "H", true, true, "ptMetro")
        )
        val lineU4 = Line("U4", "Hütteldorf", "H", "1", true, true, false, "ptMetro", Departures(listOf(depU4)))

        val monitor = Monitor(
            locationStop = LocationStop(StopProperties("1", "Schwedenplatz", "Wien", "stop", StopAttributes(rbl)), null),
            lines = listOf(lineU1, lineU4)
        )
        val apiResponse = MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null))

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        val stopConfiguredForU1 = SavedStop(
            id = 1,
            rblNumber = rbl,
            stopName = "Schwedenplatz",
            lineName = "U1",
            lineType = "ptMetro",
            direction = null,
            platformLines = "U1, U4",
            customLabel = null
        )

        val result = repository.fetchDeparturesForStop(stopConfiguredForU1)

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(1)
        assertThat(items[0].lineName).isEqualTo("U1")
    }

    @Test
    fun fetchDeparturesForStop_limitsToMaxDepartures() = runTest {
        val rbl = 4116
        val deps = (1..5).map { min ->
            Departure(
                departureTime = DepartureTime(null, null, min),
                vehicle = Vehicle("U1", "Oberlaa", "H", true, true, "ptMetro")
            )
        }
        val line = Line("U1", "Oberlaa", "H", "1", true, true, false, "ptMetro", Departures(deps))
        val monitor = Monitor(
            locationStop = LocationStop(StopProperties("1", "Stephansplatz", "Wien", "stop", StopAttributes(rbl)), null),
            lines = listOf(line)
        )
        val apiResponse = MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null))

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        val stopWithMax2 = SavedStop(
            id = 1,
            rblNumber = rbl,
            stopName = "Stephansplatz",
            lineName = null,
            lineType = null,
            direction = null,
            platformLines = null,
            customLabel = null,
            maxDepartures = 2
        )

        val result = repository.fetchDeparturesForStop(stopWithMax2)

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(2)
        assertThat(items.map { it.countdown }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun fetchDepartures_liveVsScheduledDetection() = runTest {
        val rbl = 4300
        val depLive = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "2026-10-14T10:01:00", 1),
            vehicle = Vehicle("18", "Burggasse", "H", true, true, "ptTram")
        )
        val depScheduled = Departure(
            departureTime = DepartureTime("2026-10-14T10:05:00", null, 5),
            vehicle = Vehicle("18", "Burggasse", "H", true, true, "ptTram")
        )
        val line = Line("18", "Burggasse", "H", "1", true, true, false, "ptTram", Departures(listOf(depLive, depScheduled)))
        val monitor = Monitor(
            locationStop = LocationStop(StopProperties("1", "Hauptbahnhof", "Wien", "stop", StopAttributes(rbl)), null),
            lines = listOf(line)
        )
        val apiResponse = MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null))

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(2)

        val liveItem = items.first { it.countdown == 1 }
        assertThat(liveItem.isRealtime).isTrue()
        assertThat(liveItem.departureTimeReal).isNotNull()

        val scheduledItem = items.first { it.countdown == 5 }
        assertThat(scheduledItem.isRealtime).isFalse()
        assertThat(scheduledItem.departureTimeReal).isNull()
    }

    @Test
    fun fetchDeparturesForStops_multiStopBatch_groupsCorrectly() = runTest {
        val rbl1 = 4116
        val rbl2 = 4200
        val resp1 = createMockMonitorResponse("Stephansplatz", rbl1, "U1", "ptMetro", "Oberlaa", 2)
        val resp2 = createMockMonitorResponse("Karlsplatz", rbl2, "U4", "ptMetro", "Hütteldorf", 4)

        val combinedResponse = MonitorResponse(
            MonitorData(listOf(resp1.data!!.monitors!![0], resp2.data!!.monitors!![0])),
            ApiMessage("OK", 1, null)
        )

        whenever(mockApi.getMonitor(eq(listOf(rbl1, rbl2)), any())).thenReturn(Response.success(combinedResponse))

        val stop1 = SavedStop(id = 1, rblNumber = rbl1, stopName = "Stephansplatz", lineName = null, lineType = null, direction = null, platformLines = null, customLabel = null)
        val stop2 = SavedStop(id = 2, rblNumber = rbl2, stopName = "Karlsplatz", lineName = null, lineType = null, direction = null, platformLines = null, customLabel = null)

        val result = repository.fetchDeparturesForStops(listOf(stop1, stop2))

        assertThat(result.isSuccess).isTrue()
        val groups = result.getOrThrow()
        assertThat(groups).hasSize(2)

        val group1 = groups.first { it.stop.id == 1 }
        assertThat(group1.departures).hasSize(1)
        assertThat(group1.departures[0].lineName).isEqualTo("U1")

        val group2 = groups.first { it.stop.id == 2 }
        assertThat(group2.departures).hasSize(1)
        assertThat(group2.departures[0].lineName).isEqualTo("U4")
    }

    @Test
    fun deleteStop_deletesSavedStopAndCleansCache() = runTest {
        val stop = SavedStop(id = 15, rblNumber = 4116, stopName = "Stephansplatz", lineName = null, lineType = null, direction = null, platformLines = null, customLabel = null)

        repository.deleteStop(stop)

        verify(mockSavedStopDao).delete(stop)
        verify(mockCachedDepartureDao).deleteForStop(15)
        verify(mockCachedDepartureDao).deleteForRbl(4116)
    }

    @Test
    fun deleteStop_whenOtherStopSharesRbl_doesNotDeleteCacheForRbl() = runTest {
        val stop1 = SavedStop(id = 15, rblNumber = 4116, stopName = "Stephansplatz", lineName = "U1", lineType = null, direction = null, platformLines = null, customLabel = null)
        val stop2 = SavedStop(id = 16, rblNumber = 4116, stopName = "Stephansplatz", lineName = "U3", lineType = null, direction = null, platformLines = null, customLabel = null)

        whenever(mockSavedStopDao.getAllStopsOnce()).thenReturn(listOf(stop2))

        repository.deleteStop(stop1)

        verify(mockSavedStopDao).delete(stop1)
        verify(mockCachedDepartureDao).deleteForStop(15)
        verify(mockCachedDepartureDao, never()).deleteForRbl(4116)
    }

    @Test
    fun deleteStopById_whenOtherStopSharesRbl_doesNotDeleteCacheForRbl() = runTest {
        val stop1 = SavedStop(id = 15, rblNumber = 4116, stopName = "Stephansplatz", lineName = "U1", lineType = null, direction = null, platformLines = null, customLabel = null)
        val stop2 = SavedStop(id = 16, rblNumber = 4116, stopName = "Stephansplatz", lineName = "U3", lineType = null, direction = null, platformLines = null, customLabel = null)

        // First call inside deleteStopById to find stop by ID, second call to check remaining stops
        whenever(mockSavedStopDao.getAllStopsOnce()).thenReturn(listOf(stop1, stop2), listOf(stop2))

        repository.deleteStopById(15)

        verify(mockSavedStopDao).deleteById(15)
        verify(mockCachedDepartureDao).deleteForStop(15)
        verify(mockCachedDepartureDao, never()).deleteForRbl(4116)
    }

    @Test
    fun fetchDepartures_whenThrottled_returnsLiveDeparturesNotMarkedAsCached() = runTest {
        val rbl = 4116
        val apiResponse = createMockMonitorResponse(
            stopTitle = "Stephansplatz",
            rbl = rbl,
            lineName = "U1",
            towards = "Oberlaa",
            countdown = 4
        )

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        // Initial fetch
        val result1 = repository.fetchDepartures(listOf(rbl))
        assertThat(result1.isSuccess).isTrue()
        assertThat(result1.getOrThrow()[0].isCached).isFalse()

        // Immediate subsequent fetch without forceRefresh (throttled)
        val result2 = repository.fetchDepartures(listOf(rbl), forceRefresh = false)
        assertThat(result2.isSuccess).isTrue()
        val items2 = result2.getOrThrow()
        assertThat(items2).hasSize(1)
        assertThat(items2[0].isCached).isFalse() // Must NOT be marked as offline/cached!
        assertThat(items2[0].isRealtime).isTrue()

        // Only 1 API call made
        verify(mockApi, times(1)).getMonitor(eq(listOf(rbl)), any())
    }

    @Test
    fun fetchDepartures_forceRefresh_bypassesThrottleAndFetchesFresh() = runTest {
        val rbl = 4116
        val apiResponse = createMockMonitorResponse(
            stopTitle = "Stephansplatz",
            rbl = rbl,
            lineName = "U1",
            towards = "Oberlaa",
            countdown = 4
        )

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        // Initial fetch
        repository.fetchDepartures(listOf(rbl))

        // Subsequent fetch with forceRefresh = true (e.g. pull to refresh)
        val result2 = repository.fetchDepartures(listOf(rbl), forceRefresh = true)
        assertThat(result2.isSuccess).isTrue()
        assertThat(result2.getOrThrow()[0].isCached).isFalse()

        // 2 API calls made because forceRefresh bypassed the 15s throttle
        verify(mockApi, times(2)).getMonitor(eq(listOf(rbl)), any())
    }

    @Test
    fun updateStopsOrder_updatesSortOrderSequentially() = runTest {
        val stop1 = SavedStop(id = 1, rblNumber = 1876, stopName = "Knöllgasse", lineName = "1", lineType = "ptTram", direction = "1", platformLines = "1", customLabel = null, sortOrder = 5)
        val stop2 = SavedStop(id = 2, rblNumber = 1398, stopName = "Starhemberg", lineName = "65A", lineType = "ptBusCity", direction = "2", platformLines = "65A", customLabel = null, sortOrder = 0)

        repository.updateStopsOrder(listOf(stop2, stop1))

        argumentCaptor<List<SavedStop>>().apply {
            verify(mockSavedStopDao).updateAll(capture())
            assertThat(firstValue).hasSize(2)
            assertThat(firstValue[0].id).isEqualTo(2)
            assertThat(firstValue[0].sortOrder).isEqualTo(0)
            assertThat(firstValue[1].id).isEqualTo(1)
            assertThat(firstValue[1].sortOrder).isEqualTo(1)
        }
    }
}
