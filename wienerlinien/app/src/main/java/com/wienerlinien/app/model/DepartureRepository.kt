package com.wienerlinien.app.model

import com.wienerlinien.app.api.ApiClient
import com.wienerlinien.app.api.WienerLinienApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class DepartureRepository(
    private val db: AppDatabase,
    private val api: WienerLinienApiService = ApiClient.apiService
) {

    private val savedStopDao = db.savedStopDao()
    private val cachedDepartureDao = db.cachedDepartureDao()

    val savedStops: Flow<List<SavedStop>> = savedStopDao.getAllStops()

    // Instance-level cache of last network fetch timestamps and live departures per RBL
    private val lastFetchTimestamps = ConcurrentHashMap<Int, Long>()
    private val liveDeparturesCache = ConcurrentHashMap<Int, List<DepartureItem>>()

    /**
     * Fetch departures for one or more RBL numbers.
     * Guaranteed safe against empty lists (avoids API error 321).
     * Automatically caches fresh results and falls back to Room cache when offline.
     */
    suspend fun fetchDepartures(
        rblNumbers: List<Int>,
        forceRefresh: Boolean = false
    ): Result<List<DepartureItem>> = withContext(Dispatchers.IO) {
        val validRbls = rblNumbers.filter { it > 0 }.distinct()

        // Guard against empty query: prevent Retrofit query parameter omission -> avoids API error 321
        if (validRbls.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        val now = System.currentTimeMillis()

        // Rate-limit guard: if data was fetched very recently (<15s) and forceRefresh is false, reuse live cache
        if (!forceRefresh && validRbls.isNotEmpty() && validRbls.all { rbl ->
            val last = lastFetchTimestamps[rbl]
            last != null && (now - last) < 15_000L
        }) {
            val live = validRbls.flatMap { liveDeparturesCache[it] ?: emptyList() }
            if (live.isNotEmpty()) {
                return@withContext Result.success(live.sortedBy { it.countdown })
            }
            val cached = loadCachedDepartures(validRbls)
            if (cached.isNotEmpty()) {
                return@withContext Result.success(cached)
            }
        }

        try {
            var response = api.getMonitor(stopId = validRbls)
            var body = response.body()

            // Handle rate limit (API 316): back off 1.2s and retry once
            if (response.isSuccessful && body?.message?.messageCode == 316) {
                android.util.Log.w("DepartureRepository", "Wiener Linien API rate limit 316 hit, waiting 1.2s before retry...")
                kotlinx.coroutines.delay(1200)
                response = api.getMonitor(stopId = validRbls)
                body = response.body()
            }

            if (!response.isSuccessful || body?.message?.value != "OK") {
                val code = body?.message?.messageCode
                val msg = when (code) {
                    311 -> "Database unavailable"
                    312 -> "Stop does not exist"
                    316 -> "Query limit reached"
                    320 -> "Invalid query parameter"
                    321 -> "Missing query parameter"
                    322 -> "No data available"
                    else -> "API error ${response.code()}"
                }
                android.util.Log.w("DepartureRepository", "API responded with non-OK status: code=$code, msg=$msg, httpCode=${response.code()}")

                // Attempt live cache fallback before falling back to offline Room cache
                val live = validRbls.flatMap { liveDeparturesCache[it] ?: emptyList() }
                if (live.isNotEmpty()) {
                    return@withContext Result.success(live.sortedBy { it.countdown })
                }

                // Attempt offline Room cache fallback on API error
                val cached = loadCachedDepartures(validRbls)
                if (cached.isNotEmpty()) {
                    return@withContext Result.success(cached)
                }
                return@withContext Result.failure(Exception(msg))
            }

            val monitors = body.data?.monitors ?: emptyList()
            val items = mutableListOf<DepartureItem>()
            val cacheEntities = mutableListOf<CachedDeparture>()

            // Update fetch timestamps and in-memory live cache for queried RBLs
            for (rbl in validRbls) {
                lastFetchTimestamps[rbl] = now
            }

            for (monitor in monitors) {
                val stopTitle = monitor.locationStop?.properties?.title ?: "Unknown Stop"
                val rbl = monitor.locationStop?.properties?.attributes?.rbl?.takeIf { it > 0 }
                    ?: if (validRbls.size == 1) validRbls[0] else 0

                for (line in monitor.lines ?: emptyList()) {
                    val lineName = line.name ?: "?"
                    val lineType = line.type ?: "ptBusCity"
                    val lineTowards = line.towards ?: ""
                    val lineRealtimeSupported = line.realtimeSupported ?: true
                    val departures = line.departures?.departure ?: emptyList()

                    for (dep in departures.take(5)) {
                        val countdown = dep.departureTime?.countdown ?: continue
                        val timePlanned = dep.departureTime.timePlanned
                        val timeReal = dep.departureTime.timeReal
                        val vehTowards = dep.vehicle?.towards
                        val vehRealtimeSupported = dep.vehicle?.realtimeSupported ?: true

                        // Live vs scheduled detection
                        val isRealtime = !timeReal.isNullOrBlank() && lineRealtimeSupported && vehRealtimeSupported

                        val towards = vehTowards?.takeIf { it.isNotBlank() }
                            ?: lineTowards.takeIf { it.isNotBlank() }
                            ?: ""

                        val item = DepartureItem(
                            lineName = lineName,
                            towards = towards,
                            lineType = lineType,
                            countdown = countdown,
                            isRealtime = isRealtime,
                            isBarrierFree = line.barrierFree == true || dep.vehicle?.barrierFree == true,
                            hasTrafficJam = line.trafficjam == true,
                            stopTitle = stopTitle,
                            rbl = rbl,
                            departureTimePlanned = timePlanned,
                            departureTimeReal = timeReal,
                            isCached = false,
                            cachedAtMillis = now
                        )
                        items.add(item)

                        cacheEntities.add(
                            CachedDeparture(
                                rblNumber = rbl,
                                lineName = lineName,
                                lineType = lineType,
                                destination = towards,
                                countdown = countdown,
                                isRealtime = isRealtime,
                                departureTimePlanned = timePlanned,
                                departureTimeReal = timeReal,
                                cachedAtTimestamp = now
                            )
                        )
                    }
                }
            }

            // Persist to Room cache atomically
            if (cacheEntities.isNotEmpty()) {
                cachedDepartureDao.replaceDeparturesForRbls(validRbls, cacheEntities)
            }

            items.sortBy { it.countdown }
            for (rbl in validRbls) {
                liveDeparturesCache[rbl] = items.filter { it.rbl == rbl }
            }
            Result.success(items)

        } catch (e: Exception) {
            android.util.Log.w("DepartureRepository", "Network fetch failed for RBLs $validRbls, falling back to cache: ${e.message}")
            // Network failure: fallback to Room cache
            val cached = loadCachedDepartures(validRbls)
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * Load cached departures and adjust countdown times according to elapsed minutes.
     * Null-safe against unit test mocks.
     */
    private suspend fun loadCachedDepartures(rblNumbers: List<Int>): List<DepartureItem> {
        val cachedList = (cachedDepartureDao.getDeparturesForRbls(rblNumbers) as List<CachedDeparture>?) ?: emptyList()
        val now = System.currentTimeMillis()

        return cachedList.map { cached ->
            val elapsedMinutes = maxOf(0, ((now - cached.cachedAtTimestamp) / 60_000L).toInt())
            val adjustedCountdown = maxOf(0, cached.countdown - elapsedMinutes)

            DepartureItem(
                lineName = cached.lineName,
                towards = cached.destination,
                lineType = cached.lineType,
                countdown = adjustedCountdown,
                isRealtime = false, // Cached data marked as not live
                stopTitle = "",
                rbl = cached.rblNumber,
                departureTimePlanned = cached.departureTimePlanned,
                departureTimeReal = cached.departureTimeReal,
                isCached = true,
                cachedAtMillis = cached.cachedAtTimestamp
            )
        }.sortedBy { it.countdown }
    }

    /**
     * Fetch departures for a single saved stop with line and direction filtering.
     */
    suspend fun fetchDeparturesForStop(stop: SavedStop): Result<List<DepartureItem>> {
        val result = fetchDepartures(listOf(stop.rblNumber))
        return result.map { items ->
            filterDeparturesForStop(items, stop).take(stop.maxDepartures)
        }
    }

    /**
     * Fetch departures for multiple saved stops, returning grouped results.
     */
    suspend fun fetchDeparturesForStops(
        stops: List<SavedStop>,
        forceRefresh: Boolean = false
    ): Result<List<DepartureGroup>> {
        return fetchDepartures(stops, forceRefresh)
    }

    /**
     * Fetch departures for multiple saved stops (PROJECT.md contract).
     */
    @JvmName("fetchDeparturesForStopsContract")
    suspend fun fetchDepartures(
        stops: List<SavedStop>,
        forceRefresh: Boolean = false
    ): Result<List<DepartureGroup>> {
        if (stops.isEmpty()) return Result.success(emptyList())

        val rbls = stops.map { it.rblNumber }.distinct()
        val result = fetchDepartures(rbls, forceRefresh)

        return result.map { allItems ->
            val groupedByRbl = allItems.groupBy { it.rbl }
            stops.map { stop ->
                val stopDeps = groupedByRbl[stop.rblNumber] ?: emptyList()
                val filtered = filterDeparturesForStop(stopDeps, stop).take(stop.maxDepartures)
                val isFromCache = filtered.any { it.isCached }
                val lastUpdated = filtered.map { it.cachedAtMillis }.maxOrNull() ?: System.currentTimeMillis()
                DepartureGroup(
                    stop = stop,
                    departures = filtered,
                    isFromCache = isFromCache,
                    lastUpdatedMillis = lastUpdated
                )
            }
        }
    }

    fun filterDeparturesForStop(items: List<DepartureItem>, stop: SavedStop): List<DepartureItem> {
        var filtered = items

        // Line name filter
        if (!stop.lineName.isNullOrBlank()) {
            filtered = filtered.filter {
                it.lineName.equals(stop.lineName, ignoreCase = true)
            }
        }

        // Direction / Steig filter
        if (!stop.direction.isNullOrBlank()) {
            filtered = filtered.filter { item ->
                matchesDirectionFilter(item, stop.direction)
            }
        }

        return filtered
    }

    fun matchesDirectionFilter(item: DepartureItem, filter: String): Boolean {
        val dir = filter.trim()
        if (dir.isEmpty()) return true

        if (item.towards.contains(dir, ignoreCase = true)) return true

        // Platform ID check: if AddStopActivity stored "1", "2", etc., do not exclude departures
        if (dir.length <= 2 && (dir.all { it.isDigit() } || dir.length == 1)) {
            return true
        }

        return false
    }

    suspend fun pruneStaleCache(maxAgeMillis: Long = 24 * 3600 * 1000L) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - maxAgeMillis
            cachedDepartureDao.deleteOldDepartures(cutoff)
        }
    }

    // ── DB operations ──────────────────────────────────────────────────────

    suspend fun saveStop(stop: SavedStop): Long = savedStopDao.insert(stop)

    suspend fun deleteStop(stop: SavedStop) {
        savedStopDao.delete(stop)
        cachedDepartureDao.deleteForStop(stop.id)
        val remaining = (savedStopDao.getAllStopsOnce() as List<SavedStop>?) ?: emptyList()
        if (remaining.none { it.id != stop.id && it.rblNumber == stop.rblNumber }) {
            cachedDepartureDao.deleteForRbl(stop.rblNumber)
        }
    }

    suspend fun deleteStopById(id: Int) {
        val allStops = (savedStopDao.getAllStopsOnce() as List<SavedStop>?) ?: emptyList()
        val stop = allStops.find { it.id == id }
        savedStopDao.deleteById(id)
        cachedDepartureDao.deleteForStop(id)
        if (stop != null) {
            if (allStops.none { it.id != id && it.rblNumber == stop.rblNumber }) {
                cachedDepartureDao.deleteForRbl(stop.rblNumber)
            }
        }
    }

    suspend fun updateStop(stop: SavedStop) = savedStopDao.update(stop)

    suspend fun updateStopsOrder(stops: List<SavedStop>) = withContext(Dispatchers.IO) {
        val updated = stops.mapIndexed { index, stop ->
            stop.copy(sortOrder = index)
        }
        savedStopDao.updateAll(updated)
    }

    suspend fun fetchDisruptions(): Result<List<TrafficInfoItem>> = withContext(Dispatchers.IO) {
        try {
            val response = api.getTrafficInfoList()
            if (response.isSuccessful) {
                val items = response.body()?.data?.trafficInfos ?: emptyList()
                Result.success(items)
            } else {
                Result.failure(Exception("HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setHomeStop(stopId: Int?) = withContext(Dispatchers.IO) {
        val stops = savedStopDao.getAllStopsOnce()
        val updated = stops.map { stop ->
            stop.copy(isHome = (stop.id == stopId))
        }
        savedStopDao.updateAll(updated)
    }

    suspend fun setWorkStop(stopId: Int?) = withContext(Dispatchers.IO) {
        val stops = savedStopDao.getAllStopsOnce()
        val updated = stops.map { stop ->
            stop.copy(isWork = (stop.id == stopId))
        }
        savedStopDao.updateAll(updated)
    }

    suspend fun getAllStopsOnce(): List<SavedStop> = savedStopDao.getAllStopsOnce()
}
