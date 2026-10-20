package com.wienerlinien.app.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Searches for stops using official Wiener Linien static data.
 * Features automatic fallback to bundled assets in offline/airplane mode.
 */
object StopSearchUtil {

    private const val TAG = "StopSearchUtil"
    private const val BASE_URL = "https://data.wien.gv.at/csv/"
    private const val URL_HALTESTELLEN = "${BASE_URL}wienerlinien-ogd-haltestellen.csv"
    private const val URL_LINES = "${BASE_URL}wienerlinien-ogd-linien.csv"
    private const val URL_STEIGE = "${BASE_URL}wienerlinien-ogd-steige.csv"

    private var cachedStops: List<StopSearchResult>? = null
    private var cacheTime: Long = 0
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Test hook and runtime asset provider
    var assetStreamProvider: (() -> InputStream?)? = null
    var forceOfflineForTesting: Boolean = false

    fun clearCache() {
        cachedStops = null
        cacheTime = 0
    }

    data class StopSearchResult(
        val rblNumber: Int,
        val stopName: String,
        val municipality: String,
        val platform: String?, // Steig ID
        val lines: String,     // Comma-separated lines
        val longitude: Double,
        val latitude: Double
    )

    data class NearbyStop(
        val stop: StopSearchResult,
        val distanceMeters: Int
    )

    fun init(context: Context) {
        val appContext = context.applicationContext
        assetStreamProvider = {
            try {
                appContext.assets.open("stops_vienna.csv")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open bundled stops_vienna.csv", e)
                null
            }
        }
    }

    suspend fun searchStops(query: String): List<StopSearchResult> = withContext(Dispatchers.IO) {
        val stops = getOrFetchStops()
        if (query.isBlank()) return@withContext stops.take(50)

        val q = query.trim().lowercase()
        stops.filter { it.stopName.lowercase().contains(q) || it.lines.lowercase().contains(q) }
            .sortedBy { it.stopName }
            .take(100)
    }

    suspend fun findNearbyStops(
        userLat: Double,
        userLon: Double,
        maxRadiusMeters: Double = 1200.0,
        limit: Int = 30
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val stops = getOrFetchStops()
        val results = mutableListOf<NearbyStop>()
        val distResults = FloatArray(1)

        for (stop in stops) {
            if (stop.latitude == 0.0 && stop.longitude == 0.0) continue
            android.location.Location.distanceBetween(
                userLat, userLon,
                stop.latitude, stop.longitude,
                distResults
            )
            val dist = distResults[0].toInt()
            if (dist <= maxRadiusMeters) {
                results.add(NearbyStop(stop, dist))
            }
        }

        results.sortBy { it.distanceMeters }
        results.take(limit)
    }

    private suspend fun getOrFetchStops(): List<StopSearchResult> {
        val now = System.currentTimeMillis()
        if (cachedStops != null && now - cacheTime < CACHE_TTL_MS) {
            return cachedStops!!
        }

        // 1. Fast load from bundled asset (complete 4,029 stops dataset, instantaneous)
        val bundledResult = loadBundledStops()
        if (bundledResult.isNotEmpty()) {
            cachedStops = bundledResult
            cacheTime = now
            return bundledResult
        }

        // 2. Fallback to online fetch if asset is missing and not forced offline
        if (!forceOfflineForTesting) {
            val liveResult = fetchEnrichedData()
            if (liveResult.isNotEmpty()) {
                cachedStops = liveResult
                cacheTime = now
                return liveResult
            }
        }

        return emptyList()
    }

    fun loadBundledStops(): List<StopSearchResult> {
        val stream = assetStreamProvider?.invoke()
            ?: javaClass.classLoader?.getResourceAsStream("stops_vienna.csv")
            ?: javaClass.classLoader?.getResourceAsStream("assets/stops_vienna.csv")
            ?: return emptyList()

        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                parseStopsCsv(reader)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse bundled stops", e)
            emptyList()
        }
    }

    fun parseStopsCsv(reader: BufferedReader): List<StopSearchResult> {
        val results = mutableListOf<StopSearchResult>()
        reader.readLine() // skip header row
        reader.forEachLine { line ->
            // Format: rblNumber;stopName;municipality;platform;lines;longitude;latitude
            val parts = line.split(";")
            if (parts.size >= 7) {
                val rbl = parts[0].trim().clean().toIntOrNull() ?: return@forEachLine
                if (rbl <= 0) return@forEachLine

                val stopName = parts[1].trim().clean()
                val municipality = parts[2].trim().clean()
                val platform = parts[3].trim().clean().takeIf { it.isNotBlank() }
                val lines = parts[4].trim().clean()
                val lon = parts[5].trim().clean().replace(",", ".").toDoubleOrNull() ?: 0.0
                val lat = parts[6].trim().clean().replace(",", ".").toDoubleOrNull() ?: 0.0

                results.add(
                    StopSearchResult(
                        rblNumber = rbl,
                        stopName = stopName,
                        municipality = municipality,
                        platform = platform,
                        lines = lines,
                        longitude = lon,
                        latitude = lat
                    )
                )
            }
        }
        return results.sortedBy { it.stopName }
    }

    private fun fetchEnrichedData(): List<StopSearchResult> {
        return try {
            val lineMap = fetchLines()
            val stopMap = fetchHaltestellen()

            val response = client.newCall(Request.Builder().url(URL_STEIGE).build()).execute()
            val body = response.body?.string() ?: return emptyList()

            val rblMap = mutableMapOf<Int, TempStop>()
            BufferedReader(StringReader(body)).use { reader ->
                reader.readLine()
                reader.forEachLine { line ->
                    val parts = line.split(";")
                    if (parts.size >= 8) {
                        val lineId = parts[1].trim().clean().toIntOrNull() ?: return@forEachLine
                        val stopId = parts[2].trim().clean().toIntOrNull() ?: return@forEachLine
                        val rbl = parts[5].trim().clean().toIntOrNull() ?: return@forEachLine
                        if (rbl <= 0) return@forEachLine

                        val steig = parts[7].trim().clean().takeIf { it.isNotBlank() }
                        val lat = if (parts.size >= 9) parts[8].trim().clean().replace(",", ".").toDoubleOrNull() ?: 0.0 else 0.0
                        val lon = if (parts.size >= 10) parts[9].trim().clean().replace(",", ".").toDoubleOrNull() ?: 0.0 else 0.0

                        val stopInfo = stopMap[stopId]
                        val stopName = stopInfo?.first ?: "Stop"
                        val muni = stopInfo?.second ?: "Wien"
                        val lineName = lineMap[lineId]

                        val entry = rblMap.getOrPut(rbl) {
                            TempStop(rbl, stopName, muni, steig, lat, lon)
                        }
                        if (lineName != null) {
                            entry.lines.add(lineName)
                        }
                    }
                }
            }

            rblMap.values.map {
                StopSearchResult(
                    rblNumber = it.rbl,
                    stopName = it.name,
                    municipality = it.muni,
                    platform = it.steig,
                    lines = it.lines.sorted().joinToString(", "),
                    longitude = it.lon,
                    latitude = it.lat
                )
            }.sortedBy { it.stopName }
        } catch (e: Exception) {
            Log.w(TAG, "fetchEnrichedData failed (falling back to bundled data): ${e.message}")
            emptyList()
        }
    }

    private fun fetchHaltestellen(): Map<Int, Pair<String, String>> {
        val map = mutableMapOf<Int, Pair<String, String>>()
        try {
            val response = client.newCall(Request.Builder().url(URL_HALTESTELLEN).build()).execute()
            val body = response.body?.string() ?: return map
            BufferedReader(StringReader(body)).use { reader ->
                reader.readLine()
                reader.forEachLine { line ->
                    val parts = line.split(";")
                    if (parts.size >= 4) {
                        val id = parts[0].trim().clean().toIntOrNull() ?: return@forEachLine
                        val name = parts[3].trim().clean()
                        val muni = if (parts.size >= 5) parts[4].trim().clean() else "Wien"
                        map[id] = Pair(name, muni)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchHaltestellen failed", e)
        }
        return map
    }

    private fun fetchLines(): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        try {
            val response = client.newCall(Request.Builder().url(URL_LINES).build()).execute()
            val body = response.body?.string() ?: return map
            BufferedReader(StringReader(body)).use { reader ->
                reader.readLine()
                reader.forEachLine { line ->
                    val parts = line.split(";")
                    if (parts.size >= 2) {
                        val id = parts[0].trim().clean().toIntOrNull() ?: return@forEachLine
                        val name = parts[1].trim().clean()
                        map[id] = name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLines failed", e)
        }
        return map
    }

    private fun String.clean() = this.replace("\"", "").replace("'", "")

    private class TempStop(
        val rbl: Int,
        val name: String,
        val muni: String,
        val steig: String?,
        val lat: Double,
        val lon: Double,
        val lines: MutableSet<String> = mutableSetOf()
    )
}
