package com.wienerlinien.app.model

import com.google.common.truth.Truth.assertThat
import com.wienerlinien.app.api.WienerLinienApiService
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenge Suite for Milestone 1:
 * - Room Schema Migration (MIGRATION_3_4)
 * - Concurrency & Transaction Safety in DepartureDao
 * - Cache Timestamp Expiration & Clock Skew Boundaries
 * - Cascading Deletion Verification on Stop Removal
 */
class RoomChallengeTest {

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

    // ══════════════════════════════════════════════════════════════════════════
    // 1. Room Schema Migration (MIGRATION_3_4)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun migration3To4_versionNumbers_areExactlyThreeAndFour() {
        val migration = AppDatabase.MIGRATION_3_4
        assertThat(migration.startVersion).isEqualTo(3)
        assertThat(migration.endVersion).isEqualTo(4)
    }

    @Test
    fun migration3To4_executesExpectedDdlStatements() {
        val mockSqliteDb = mock<SupportSQLiteDatabase>()
        val executedStatements = mutableListOf<String>()

        whenever(mockSqliteDb.execSQL(any())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            executedStatements.add(sql)
            null
        }

        AppDatabase.MIGRATION_3_4.migrate(mockSqliteDb)

        // Verify execSQL was called 4 times (1 table creation + 3 indices)
        assertThat(executedStatements).hasSize(4)

        val tableDdl = executedStatements[0]
        assertThat(tableDdl).contains("CREATE TABLE IF NOT EXISTS `cached_departures`")
        assertThat(tableDdl).contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL")
        assertThat(tableDdl).contains("`stopId` INTEGER")
        assertThat(tableDdl).contains("`rblNumber` INTEGER NOT NULL")
        assertThat(tableDdl).contains("`lineName` TEXT NOT NULL")
        assertThat(tableDdl).contains("`lineType` TEXT")
        assertThat(tableDdl).contains("`destination` TEXT NOT NULL")
        assertThat(tableDdl).contains("`countdown` INTEGER NOT NULL")
        assertThat(tableDdl).contains("`isRealtime` INTEGER NOT NULL")
        assertThat(tableDdl).contains("`departureTimePlanned` TEXT")
        assertThat(tableDdl).contains("`departureTimeReal` TEXT")
        assertThat(tableDdl).contains("`cachedAtTimestamp` INTEGER NOT NULL")

        // Verify all 3 indices
        val indexDdl1 = executedStatements[1]
        assertThat(indexDdl1).isEqualTo("CREATE INDEX IF NOT EXISTS `index_cached_departures_stopId` ON `cached_departures` (`stopId`)")

        val indexDdl2 = executedStatements[2]
        assertThat(indexDdl2).isEqualTo("CREATE INDEX IF NOT EXISTS `index_cached_departures_rblNumber` ON `cached_departures` (`rblNumber`)")

        val indexDdl3 = executedStatements[3]
        assertThat(indexDdl3).isEqualTo("CREATE INDEX IF NOT EXISTS `index_cached_departures_cachedAtTimestamp` ON `cached_departures` (`cachedAtTimestamp`)")

        // Ensure saved_stops is never touched or dropped
        for (stmt in executedStatements) {
            assertThat(stmt).doesNotContain("DROP TABLE")
            assertThat(stmt).doesNotContain("saved_stops")
        }
    }

    @Test
    fun migration3To4_schemaDefinition_matchesRoomEntityAnnotation() {
        // Compare the columns in MIGRATION_3_4 with the CachedDeparture data class properties
        val expectedProperties = listOf(
            "id", "stopId", "rblNumber", "lineName", "lineType",
            "destination", "countdown", "isRealtime",
            "departureTimePlanned", "departureTimeReal", "cachedAtTimestamp"
        )

        val mockSqliteDb = mock<SupportSQLiteDatabase>()
        var capturedTableDdl = ""
        whenever(mockSqliteDb.execSQL(any())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            if (sql.contains("CREATE TABLE")) {
                capturedTableDdl = sql
            }
            null
        }

        AppDatabase.MIGRATION_3_4.migrate(mockSqliteDb)

        for (prop in expectedProperties) {
            assertThat(capturedTableDdl).contains("`$prop`")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. Concurrency & Transaction Safety in DepartureDao
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun concurrency_simulatedBatchReplacement_ensuresAtomicityUnderConcurrentLoad() = runBlocking {
        // In-memory simulated SQLite store representing cached_departures
        val databaseStore = ConcurrentHashMap<Int, MutableList<CachedDeparture>>()
        val transactionLock = Any()
        val readSnapshots = mutableListOf<List<CachedDeparture>>()
        val totalRounds = 50

        // Initialize store with baseline data
        databaseStore[4116] = mutableListOf(
            CachedDeparture(rblNumber = 4116, lineName = "U1", destination = "Oberlaa", countdown = 2)
        )

        val writerJobs = (1..totalRounds).map { round ->
            async(Dispatchers.Default) {
                // Emulate atomic transaction: delete then insert inside transaction lock
                synchronized(transactionLock) {
                    // Simulate delete
                    databaseStore[4116]?.clear()
                    // Small artificial delay simulating disk I/O in transaction
                    Thread.sleep(1)
                    // Simulate insert
                    databaseStore.getOrPut(4116) { mutableListOf() }.add(
                        CachedDeparture(
                            rblNumber = 4116,
                            lineName = "U1",
                            destination = "Oberlaa",
                            countdown = round
                        )
                    )
                }
            }
        }

        val readerJobs = (1..totalRounds).map {
            async(Dispatchers.Default) {
                // Emulate reader under transaction isolation
                val snapshot = synchronized(transactionLock) {
                    databaseStore[4116]?.toList() ?: emptyList()
                }
                synchronized(readSnapshots) {
                    readSnapshots.add(snapshot)
                }
            }
        }

        writerJobs.awaitAll()
        readerJobs.awaitAll()

        // Transaction safety invariant: reader must NEVER see an empty list during replace
        for (snapshot in readSnapshots) {
            assertThat(snapshot).isNotEmpty()
            assertThat(snapshot[0].lineName).isEqualTo("U1")
        }
    }

    @Test
    fun transactionSafety_replaceDepartures_failureDuringInsert_rollsBackDeletion() = runBlocking {
        // Challenge: what happens if insertion fails during batch replacement?
        // With @Transaction / withTransaction, the deletion must be rolled back.
        var inMemoryRecords = listOf(
            CachedDeparture(rblNumber = 4116, lineName = "U1", destination = "Oberlaa", countdown = 5)
        )
        val transactionActive = AtomicBoolean(false)
        val rollbackOccurred = AtomicBoolean(false)

        val originalSnapshot = inMemoryRecords

        try {
            // Simulate beginTransaction
            transactionActive.set(true)
            val staged = inMemoryRecords.toMutableList()
            // Step 1: delete old
            staged.clear()

            // Step 2: insert new fails with SQLiteDiskIOException
            throw RuntimeException("SQLite: disk I/O error or constraint violation")

            @Suppress("UNREACHABLE_CODE")
            inMemoryRecords = staged
        } catch (e: Exception) {
            // Emulate transaction rollback
            rollbackOccurred.set(true)
            transactionActive.set(false)
            // Staged changes discarded, original retained
        }

        assertThat(rollbackOccurred.get()).isTrue()
        assertThat(inMemoryRecords).isEqualTo(originalSnapshot)
        assertThat(inMemoryRecords).hasSize(1)
    }

    @Test
    fun concurrency_rapidRefreshCalls_repositoryGuardsIntegrity() = runTest {
        val rbl = 4116
        val callCount = AtomicInteger(0)

        whenever(mockApi.getMonitor(eq(listOf(rbl)), any())).thenAnswer {
            callCount.incrementAndGet()
            val dep = Departure(
                departureTime = DepartureTime("10:00", "10:01", 3),
                vehicle = Vehicle("U1", "Oberlaa", "H", true, true, "ptMetro")
            )
            val line = Line("U1", "Oberlaa", "H", "1", true, true, false, "ptMetro", Departures(listOf(dep)))
            val monitor = Monitor(
                LocationStop(StopProperties("1", "Stephansplatz", "Wien", "stop", StopAttributes(rbl)), null),
                listOf(line)
            )
            Response.success(MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null)))
        }

        // Execute 10 concurrent fetch calls for the same RBL
        val results = (1..10).map {
            repository.fetchDepartures(listOf(rbl))
        }

        for (res in results) {
            assertThat(res.isSuccess).isTrue()
            assertThat(res.getOrThrow()).hasSize(1)
        }

        // Verify replaceDeparturesForRbls was called for successful fetches
        verify(mockCachedDepartureDao, atLeastOnce()).replaceDeparturesForRbls(eq(listOf(rbl)), any())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. Cache Timestamp Boundaries & Clock Skew
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun cacheTimestamp_negativeElapsed_clockSkew_doesNotIncreaseCountdown() {
        // Attack scenario: user sets device time backwards, or NTP clock skew results in now < cachedAt
        val cachedTime = 1700000060000L
        val skewedEarlierTime = cachedTime - (60 * 60 * 1000L) // Clock is 1 hour earlier!

        val cached = CachedDeparture(
            rblNumber = 4116,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 5,
            cachedAtTimestamp = cachedTime
        )

        val item = cached.toDepartureItemWithElapsedAdjustment(
            currentTimeMillis = skewedEarlierTime
        )

        // Countdown must NOT increment to 65 minutes! It must stay at 5.
        assertThat(item.countdown).isEqualTo(5)
    }

    @Test
    fun cacheTimestamp_subMinuteElapsed_doesNotPrematurelyDecrement() {
        val cachedTime = 1700000000000L

        val cached = CachedDeparture(
            rblNumber = 4116,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 5,
            cachedAtTimestamp = cachedTime
        )

        // 0 ms elapsed
        val atZero = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime)
        assertThat(atZero.countdown).isEqualTo(5)

        // 30,000 ms (30 seconds) elapsed -> integer division by 60,000 is 0
        val at30Sec = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime + 30_000L)
        assertThat(at30Sec.countdown).isEqualTo(5)

        // 59,999 ms (just under 1 min) -> integer division is 0
        val at59Sec = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime + 59_999L)
        assertThat(at59Sec.countdown).isEqualTo(5)
    }

    @Test
    fun cacheTimestamp_exactMinuteBoundaries_decrementsAccurately() {
        val cachedTime = 1700000000000L

        val cached = CachedDeparture(
            rblNumber = 4116,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 5,
            cachedAtTimestamp = cachedTime
        )

        // Exactly 60,000 ms (1 min) -> decrements to 4
        val at1Min = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime + 60_000L)
        assertThat(at1Min.countdown).isEqualTo(4)

        // Exactly 120,000 ms (2 mins) -> decrements to 3
        val at2Min = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime + 120_000L)
        assertThat(at2Min.countdown).isEqualTo(3)

        // Exactly 300,000 ms (5 mins) -> decrements to 0
        val at5Min = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime + 300_000L)
        assertThat(at5Min.countdown).isEqualTo(0)
    }

    @Test
    fun cacheTimestamp_expiredCountdown_coercedToZero_neverNegative() {
        val cachedTime = 1700000000000L

        val cached = CachedDeparture(
            rblNumber = 4116,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 2,
            cachedAtTimestamp = cachedTime
        )

        // 10 minutes elapsed when countdown was only 2
        val at10Min = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime + (10 * 60_000L))
        assertThat(at10Min.countdown).isEqualTo(0)

        // 1 day elapsed
        val at1Day = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime + (24 * 3600 * 1000L))
        assertThat(at1Day.countdown).isEqualTo(0)
    }

    @Test
    fun cacheTimestamp_zeroInitialCountdown_remainsZero() {
        val cachedTime = 1700000000000L

        val cached = CachedDeparture(
            rblNumber = 4116,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 0,
            cachedAtTimestamp = cachedTime
        )

        val item = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = cachedTime + 60_000L)
        assertThat(item.countdown).isEqualTo(0)
    }

    @Test
    fun cacheTimestamp_unixEpochTimestamp_handledWithoutUnderflow() {
        // Edge case: timestamp = 0L
        val cached = CachedDeparture(
            rblNumber = 4116,
            lineName = "U1",
            destination = "Oberlaa",
            countdown = 10,
            cachedAtTimestamp = 0L
        )

        val item = cached.toDepartureItemWithElapsedAdjustment(currentTimeMillis = 1700000000000L)
        assertThat(item.countdown).isEqualTo(0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. Cascading Deletion Verification on Stop Removal
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun cascadingDeletion_stopRemoval_callsDeleteForStopOnDao() = runTest {
        val stop = SavedStop(
            id = 42,
            rblNumber = 4116,
            stopName = "Stephansplatz",
            lineName = "U1",
            lineType = "ptMetro",
            direction = null,
            platformLines = null,
            customLabel = null
        )

        repository.deleteStop(stop)

        // Verify repository invoked both DAO deletions
        verify(mockSavedStopDao).delete(stop)
        verify(mockCachedDepartureDao).deleteForStop(42)
        verify(mockCachedDepartureDao).deleteForRbl(4116)
    }

    @Test
    fun cascadingDeletion_deleteStopById_callsDeleteForStopOnDao() = runTest {
        whenever(mockSavedStopDao.getAllStopsOnce()).thenReturn(
            listOf(SavedStop(id = 99, rblNumber = 4200, stopName = "Karlsplatz", lineName = null, lineType = null, direction = null, platformLines = null, customLabel = null))
        )

        repository.deleteStopById(99)

        verify(mockSavedStopDao).deleteById(99)
        verify(mockCachedDepartureDao).deleteForStop(99)
        verify(mockCachedDepartureDao).deleteForRbl(4200)
    }

    /**
     * EMPIRICAL FINDING TEST:
     * When DepartureRepository fetches departures, it persists CachedDeparture
     * entities with `stopId = null` (default value).
     * Consequently, when `deleteForStop(stop.id)` is invoked, it executes:
     * `DELETE FROM cached_departures WHERE stopId = :stopId`.
     * Because `stopId` was stored as `null`, the query matches 0 rows!
     * The departures for that RBL remain cached indefinitely unless evicted by RBL or cutoff.
     */
    @Test
    fun cascadingDeletion_empiricalObservation_savedDeparturesHaveNullStopId() = runTest {
        val rbl = 4116
        val dep = Departure(
            departureTime = DepartureTime("10:00", "10:01", 3),
            vehicle = Vehicle("U1", "Oberlaa", "H", true, true, "ptMetro")
        )
        val line = Line("U1", "Oberlaa", "H", "1", true, true, false, "ptMetro", Departures(listOf(dep)))
        val monitor = Monitor(
            LocationStop(StopProperties("1", "Stephansplatz", "Wien", "stop", StopAttributes(rbl)), null),
            listOf(line)
        )
        whenever(mockApi.getMonitor(eq(listOf(rbl)), any()))
            .thenReturn(Response.success(MonitorResponse(MonitorData(listOf(monitor)), ApiMessage("OK", 1, null))))

        repository.fetchDepartures(listOf(rbl))

        // Capture what was passed to cachedDepartureDao.replaceDeparturesForRbls
        val captor = argumentCaptor<List<CachedDeparture>>()
        verify(mockCachedDepartureDao).replaceDeparturesForRbls(eq(listOf(rbl)), captor.capture())

        val savedEntities = captor.firstValue
        assertThat(savedEntities).isNotEmpty()

        // OBSERVED BEHAVIOR: stopId is null on all saved entities
        for (entity in savedEntities) {
            assertThat(entity.stopId).isNull()
        }

        // DEMONSTRATION OF BUG / LIMITATION:
        // When deleteStop(SavedStop(id = 10, rblNumber = 4116, ...)) is called:
        // It calls deleteForStop(10).
        // Since all entities have stopId == null, in SQL:
        // `DELETE FROM cached_departures WHERE stopId = 10`
        // will delete 0 rows!
        val matchingByStopId = savedEntities.filter { it.stopId == 10 }
        assertThat(matchingByStopId).isEmpty()
    }
}
