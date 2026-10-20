package com.wienerlinien.app.model

import com.google.common.truth.Truth.assertThat
import com.wienerlinien.app.api.WienerLinienApiService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class DepartureRepositoryAdversarialTest {

    private lateinit var mockDb: AppDatabase
    private lateinit var mockSavedStopDao: SavedStopDao
    private lateinit var mockCachedDepartureDao: CachedDepartureDao
    private lateinit var mockApi: WienerLinienApiService
    private lateinit var repository: DepartureRepository
    private lateinit var mockWebServer: MockWebServer

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
        mockWebServer = MockWebServer()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (e: Exception) {}
    }

    // ── RBL List Boundary & Edge Cases ──────────────────────────────────────

    @Test
    fun fetchDepartures_negativeAndZeroRbls_returnsEmptyWithoutNetworkCall() = runTest {
        val result = repository.fetchDepartures(listOf(-1, 0, -9999, -42))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
        verify(mockApi, never()).getMonitor(any(), any())
    }

    @Test
    fun fetchDepartures_duplicateValidRbls_queriesOnlyDistinctPositiveRbls() = runTest {
        val rbl = 4116
        val apiResponse = MonitorResponse(
            data = MonitorData(emptyList()),
            message = ApiMessage(value = "OK", messageCode = 1, serverTime = null)
        )
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        val result = repository.fetchDepartures(listOf(rbl, -5, rbl, 0, rbl, -10))

        assertThat(result.isSuccess).isTrue()
        // Must filter non-positive and duplicates: only listOf(4116) passed to API
        verify(mockApi).getMonitor(eq(listOf(rbl)), any())
    }

    @Test
    fun fetchDeparturesForStops_stopsWithNonPositiveRbl_returnsEmptyGroupsWithoutNetworkCall() = runTest {
        val stop1 = SavedStop(id = 1, rblNumber = -1, stopName = "Invalid 1", lineName = null, lineType = null, direction = null, platformLines = null, customLabel = null)
        val stop2 = SavedStop(id = 2, rblNumber = 0, stopName = "Invalid 2", lineName = null, lineType = null, direction = null, platformLines = null, customLabel = null)

        val result = repository.fetchDeparturesForStops(listOf(stop1, stop2))

        assertThat(result.isSuccess).isTrue()
        val groups = result.getOrThrow()
        assertThat(groups).hasSize(2)
        assertThat(groups[0].departures).isEmpty()
        assertThat(groups[1].departures).isEmpty()
        verify(mockApi, never()).getMonitor(any(), any())
    }

    @Test
    fun fetchDeparturesForStops_duplicateStopsSameRbl_mappedIndependently() = runTest {
        val rbl = 4116
        val dep1 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "2026-10-14T10:01:00", 2),
            vehicle = Vehicle("U1", "Oberlaa", "H", true, true, "ptMetro")
        )
        val dep2 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "2026-10-14T10:03:00", 5),
            vehicle = Vehicle("U1", "Leopoldau", "H", true, true, "ptMetro")
        )
        val line = Line("U1", "Oberlaa", "H", "1", true, true, false, "ptMetro", Departures(listOf(dep1, dep2)))
        val monitor = Monitor(
            locationStop = LocationStop(StopProperties("1", "Stephansplatz", "Wien", "stop", StopAttributes(rbl)), null),
            lines = listOf(line)
        )
        val apiResponse = MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null))

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        val stopOberlaa = SavedStop(id = 1, rblNumber = rbl, stopName = "Stephansplatz", lineName = "U1", lineType = "ptMetro", direction = "Oberlaa", platformLines = null, customLabel = null)
        val stopLeopoldau = SavedStop(id = 2, rblNumber = rbl, stopName = "Stephansplatz", lineName = "U1", lineType = "ptMetro", direction = "Leopoldau", platformLines = null, customLabel = null)

        val result = repository.fetchDeparturesForStops(listOf(stopOberlaa, stopLeopoldau))

        assertThat(result.isSuccess).isTrue()
        val groups = result.getOrThrow()
        assertThat(groups).hasSize(2)

        val groupOberlaa = groups.first { it.stop.id == 1 }
        assertThat(groupOberlaa.departures).hasSize(1)
        assertThat(groupOberlaa.departures[0].towards).isEqualTo("Oberlaa")

        val groupLeopoldau = groups.first { it.stop.id == 2 }
        assertThat(groupLeopoldau.departures).hasSize(1)
        assertThat(groupLeopoldau.departures[0].towards).isEqualTo("Leopoldau")

        // Network queried only once for distinct RBLs
        verify(mockApi, times(1)).getMonitor(eq(listOf(rbl)), any())
    }

    // ── Network Timeouts, Resets, and HTTP Errors ───────────────────────────

    @Test
    fun fetchDepartures_networkTimeout_withCache_returnsCachedAdjustedDepartures() = runTest {
        val rbl = 4116
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any()))
            .thenThrow(RuntimeException("Connect timeout to Wiener Linien API"))

        val cached = CachedDeparture(
            rblNumber = rbl,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 7,
            cachedAtTimestamp = System.currentTimeMillis() - 120_000L // 2 min ago
        )
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(listOf(cached))

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(1)
        assertThat(items[0].countdown).isEqualTo(5) // 7 - 2 = 5
        assertThat(items[0].isCached).isTrue()
        assertThat(items[0].isRealtime).isFalse()
    }

    @Test
    fun fetchDepartures_networkTimeout_withoutCache_returnsFailure() = runTest {
        val rbl = 4116
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any()))
            .thenThrow(RuntimeException("Read timeout"))
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(emptyList())

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Read timeout")
    }

    @Test
    fun fetchDepartures_connectionReset_withCache_returnsCachedDepartures() = runTest {
        val rbl = 4116
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any()))
            .thenThrow(RuntimeException("Connection reset by peer"))

        val cached = CachedDeparture(
            rblNumber = rbl,
            lineName = "U3",
            destination = "Simmering",
            countdown = 4,
            cachedAtTimestamp = System.currentTimeMillis()
        )
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(listOf(cached))

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()[0].destination).isEqualTo("Simmering")
    }

    @Test
    fun fetchDepartures_http500_withCache_returnsCachedDepartures() = runTest {
        val rbl = 4116
        val errorResponse = Response.error<MonitorResponse>(
            500,
            "Internal Server Error".toResponseBody(null)
        )
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(errorResponse)

        val cached = CachedDeparture(
            rblNumber = rbl,
            lineName = "U1",
            destination = "Leopoldau",
            countdown = 6,
            cachedAtTimestamp = System.currentTimeMillis()
        )
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(listOf(cached))

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()[0].lineName).isEqualTo("U1")
        assertThat(result.getOrThrow()[0].isCached).isTrue()
    }

    @Test
    fun fetchDepartures_http500_withoutCache_returnsFailureWithApiErrorCode() = runTest {
        val rbl = 4116
        val errorResponse = Response.error<MonitorResponse>(
            500,
            "Internal Server Error".toResponseBody(null)
        )
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(errorResponse)
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(emptyList())

        val result = repository.fetchDepartures(listOf(rbl))

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("API error 500")
    }

    @Test
    fun fetchDepartures_http502_http503_returnsFailureWithHttpCode() = runTest {
        val rbl = 4116
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(emptyList())

        val resp502 = Response.error<MonitorResponse>(502, "Bad Gateway".toResponseBody(null))
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(resp502)
        val result502 = repository.fetchDepartures(listOf(rbl))
        assertThat(result502.isFailure).isTrue()
        assertThat(result502.exceptionOrNull()?.message).contains("API error 502")

        val resp503 = Response.error<MonitorResponse>(503, "Service Unavailable".toResponseBody(null))
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(resp503)
        val result503 = repository.fetchDepartures(listOf(rbl))
        assertThat(result503.isFailure).isTrue()
        assertThat(result503.exceptionOrNull()?.message).contains("API error 503")
    }

    // ── Wiener Linien API Specific Error Codes ──────────────────────────────

    @Test
    fun fetchDepartures_apiErrorCodes_mapsToDescriptiveMessages() = runTest {
        val rbl = 4116
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(emptyList())

        val testCases = listOf(
            311 to "Database unavailable",
            312 to "Stop does not exist",
            316 to "Query limit reached",
            320 to "Invalid query parameter",
            321 to "Missing query parameter",
            322 to "No data available",
            999 to "API error 200"
        )

        for ((code, expectedMsg) in testCases) {
            val errorBody = MonitorResponse(
                data = null,
                message = ApiMessage(value = "NOK", messageCode = code, serverTime = null)
            )
            whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(errorBody))

            val result = repository.fetchDepartures(listOf(rbl))
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).isEqualTo(expectedMsg)
        }
    }

    // ── Live vs Scheduled Permutations ──────────────────────────────────────

    @Test
    fun fetchDepartures_realtimeFlagPermutations_correctlyDeterminesIsRealtime() = runTest {
        val rbl = 4116

        // Case 1: timeReal present, both flags true -> realtime true
        val dep1 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "2026-10-14T10:01:00", 1),
            vehicle = Vehicle("U1", "A", "H", true, realtimeSupported = true, "ptMetro")
        )
        val line1 = Line("U1", "A", "H", "1", true, realtimeSupported = true, false, "ptMetro", Departures(listOf(dep1)))

        // Case 2: timeReal present, but line flag false -> realtime false
        val dep2 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "2026-10-14T10:02:00", 2),
            vehicle = Vehicle("U2", "B", "H", true, realtimeSupported = true, "ptMetro")
        )
        val line2 = Line("U2", "B", "H", "1", true, realtimeSupported = false, false, "ptMetro", Departures(listOf(dep2)))

        // Case 3: timeReal present, but vehicle flag false -> realtime false
        val dep3 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "2026-10-14T10:03:00", 3),
            vehicle = Vehicle("U3", "C", "H", true, realtimeSupported = false, "ptMetro")
        )
        val line3 = Line("U3", "C", "H", "1", true, realtimeSupported = true, false, "ptMetro", Departures(listOf(dep3)))

        // Case 4: timeReal is null -> realtime false
        val dep4 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", null, 4),
            vehicle = Vehicle("U4", "D", "H", true, realtimeSupported = true, "ptMetro")
        )
        val line4 = Line("U4", "D", "H", "1", true, realtimeSupported = true, false, "ptMetro", Departures(listOf(dep4)))

        // Case 5: timeReal is blank string -> realtime false
        val dep5 = Departure(
            departureTime = DepartureTime("2026-10-14T10:00:00", "   ", 5),
            vehicle = Vehicle("U6", "E", "H", true, realtimeSupported = true, "ptMetro")
        )
        val line5 = Line("U6", "E", "H", "1", true, realtimeSupported = true, false, "ptMetro", Departures(listOf(dep5)))

        val monitor = Monitor(
            locationStop = LocationStop(StopProperties("1", "Karlsplatz", "Wien", "stop", StopAttributes(rbl)), null),
            lines = listOf(line1, line2, line3, line4, line5)
        )
        val apiResponse = MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null))
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(Response.success(apiResponse))

        val result = repository.fetchDepartures(listOf(rbl))
        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(5)

        assertThat(items.first { it.lineName == "U1" }.isRealtime).isTrue()
        assertThat(items.first { it.lineName == "U2" }.isRealtime).isFalse()
        assertThat(items.first { it.lineName == "U3" }.isRealtime).isFalse()
        assertThat(items.first { it.lineName == "U4" }.isRealtime).isFalse()
        assertThat(items.first { it.lineName == "U6" }.isRealtime).isFalse()
    }

    // ── Direction Filter Robustness ─────────────────────────────────────────

    @Test
    fun directionFilter_matchesPlatformIndexWithoutFilteringOutDepartures() = runTest {
        val rbl = 4116
        val dep = Departure(
            departureTime = DepartureTime(null, null, 3),
            vehicle = Vehicle("U1", "Oberlaa", "H", true, true, "ptMetro")
        )
        val line = Line("U1", "Oberlaa", "H", "1", true, true, false, "ptMetro", Departures(listOf(dep)))
        val monitor = Monitor(
            locationStop = LocationStop(StopProperties("1", "Stephansplatz", "Wien", "stop", StopAttributes(rbl)), null),
            lines = listOf(line)
        )
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(
            Response.success(MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null)))
        )

        // Platform ID stored in SavedStop as "1" or "2"
        val stopWithPlatform1 = SavedStop(id = 1, rblNumber = rbl, stopName = "Stephansplatz", lineName = null, lineType = null, direction = "1", platformLines = null, customLabel = null)
        val result1 = repository.fetchDeparturesForStop(stopWithPlatform1)
        assertThat(result1.isSuccess).isTrue()
        assertThat(result1.getOrThrow()).hasSize(1)

        val stopWithPlatform2 = SavedStop(id = 2, rblNumber = rbl, stopName = "Stephansplatz", lineName = null, lineType = null, direction = "2", platformLines = null, customLabel = null)
        val result2 = repository.fetchDeparturesForStop(stopWithPlatform2)
        assertThat(result2.isSuccess).isTrue()
        assertThat(result2.getOrThrow()).hasSize(1)
    }

    @Test
    fun directionFilter_caseInsensitiveAndSubstringMatching() = runTest {
        val rbl = 4116
        val dep = Departure(
            departureTime = DepartureTime(null, null, 3),
            vehicle = Vehicle("U1", "Wien Leopoldau", "H", true, true, "ptMetro")
        )
        val line = Line("U1", "Wien Leopoldau", "H", "1", true, true, false, "ptMetro", Departures(listOf(dep)))
        val monitor = Monitor(
            locationStop = LocationStop(StopProperties("1", "Stephansplatz", "Wien", "stop", StopAttributes(rbl)), null),
            lines = listOf(line)
        )
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenReturn(
            Response.success(MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null)))
        )

        // Substring "leopoldau" lower-case should match "Wien Leopoldau"
        val stop = SavedStop(id = 1, rblNumber = rbl, stopName = "Stephansplatz", lineName = null, lineType = null, direction = "leopoldau", platformLines = null, customLabel = null)
        val result = repository.fetchDeparturesForStop(stop)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).hasSize(1)

        // Mismatched direction "Oberlaa" should filter it out
        val stopMismatch = SavedStop(id = 2, rblNumber = rbl, stopName = "Stephansplatz", lineName = null, lineType = null, direction = "Oberlaa", platformLines = null, customLabel = null)
        val resultMismatch = repository.fetchDeparturesForStop(stopMismatch)
        assertThat(resultMismatch.isSuccess).isTrue()
        assertThat(resultMismatch.getOrThrow()).isEmpty()
    }

    @Test
    fun pruneStaleCache_invokesDaoWithExpectedCutoffTimestamp() = runTest {
        val maxAge = 3600_000L // 1 hour
        val before = System.currentTimeMillis() - maxAge

        repository.pruneStaleCache(maxAge)

        val after = System.currentTimeMillis() - maxAge
        val captor = argumentCaptor<Long>()
        verify(mockCachedDepartureDao).deleteOldDepartures(captor.capture())

        assertThat(captor.firstValue).isAtLeast(before)
        assertThat(captor.firstValue).isAtMost(after)
    }

    // ── MockWebServer End-to-End Adversarial JSON Tests ─────────────────────

    private fun createRepositoryWithMockServer(): Pair<DepartureRepository, MockWebServer> {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val realService = retrofit.create(WienerLinienApiService::class.java)
        val testRepo = DepartureRepository(mockDb, realService)
        return Pair(testRepo, mockWebServer)
    }

    @Test
    fun mockWebServer_malformedJsonPayload_fallsBackToCacheOrFailsWithoutNpe() = runTest {
        val (testRepo, server) = createRepositoryWithMockServer()
        val rbl = 4116
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(emptyList())

        // Truncated, invalid JSON
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"data\": {\"monitors\": ["))

        val result = testRepo.fetchDepartures(listOf(rbl))

        assertThat(result.isFailure).isTrue()
        // Must fail with JSON syntax exception or IOException, not NullPointerException
        assertThat(result.exceptionOrNull()).isNotNull()
        assertThat(result.exceptionOrNull()).isNotInstanceOf(NullPointerException::class.java)
    }

    @Test
    fun mockWebServer_emptyMonitorsArray_returnsEmptyListWithoutCrashing() = runTest {
        val (testRepo, server) = createRepositoryWithMockServer()
        val rbl = 4116

        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {
              "data": { "monitors": [] },
              "message": { "value": "OK", "messageCode": 1 }
            }
        """.trimIndent()))

        val result = testRepo.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEmpty()
    }

    @Test
    fun mockWebServer_nullLocationStopAndLines_handledSafely() = runTest {
        val (testRepo, server) = createRepositoryWithMockServer()
        val rbl = 4116

        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {
              "data": {
                "monitors": [
                  {
                    "locationStop": null,
                    "lines": null
                  }
                ]
              },
              "message": { "value": "OK", "messageCode": 1 }
            }
        """.trimIndent()))

        val result = testRepo.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEmpty()
    }

    @Test
    fun mockWebServer_departureWithNullDepartureTime_skippedSafely() = runTest {
        val (testRepo, server) = createRepositoryWithMockServer()
        val rbl = 4116

        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {
              "data": {
                "monitors": [
                  {
                    "locationStop": {
                      "properties": {
                        "title": "Stephansplatz",
                        "attributes": { "rbl": 4116 }
                      }
                    },
                    "lines": [
                      {
                        "name": "U1",
                        "type": "ptMetro",
                        "departures": {
                          "departure": [
                            {
                              "departureTime": null,
                              "vehicle": { "name": "U1", "towards": "Oberlaa" }
                            },
                            {
                              "departureTime": { "countdown": 4, "timePlanned": "2026-10-14T10:00:00" },
                              "vehicle": { "name": "U1", "towards": "Oberlaa" }
                            }
                          ]
                        }
                      }
                    ]
                  }
                ]
              },
              "message": { "value": "OK", "messageCode": 1 }
            }
        """.trimIndent()))

        val result = testRepo.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        // The first departure had departureTime = null and was safely skipped; second was kept
        assertThat(items).hasSize(1)
        assertThat(items[0].countdown).isEqualTo(4)
    }

    @Test
    fun mockWebServer_networkSocketTimeout_handledGracefully() = runTest {
        val (testRepo, server) = createRepositoryWithMockServer()
        val rbl = 4116

        val cached = CachedDeparture(
            rblNumber = rbl,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 5,
            cachedAtTimestamp = System.currentTimeMillis()
        )
        whenever(mockCachedDepartureDao.getDeparturesForRbls(listOf(rbl))).thenReturn(listOf(cached))

        // Trigger socket timeout policy
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = testRepo.fetchDepartures(listOf(rbl))

        assertThat(result.isSuccess).isTrue()
        val items = result.getOrThrow()
        assertThat(items).hasSize(1)
        assertThat(items[0].isCached).isTrue()
    }
}
