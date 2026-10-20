package com.wienerlinien.app.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedDepartureDao {

    // ── Insertions ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDepartures(departures: List<CachedDeparture>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(departures: List<CachedDeparture>): List<Long> = insertDepartures(departures)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(departure: CachedDeparture): Long

    // ── Queries by Saved Stop ID ────────────────────────────────────────────

    @Query("SELECT * FROM cached_departures WHERE stopId = :stopId ORDER BY countdown ASC")
    suspend fun getDeparturesForStop(stopId: Int): List<CachedDeparture>

    @Query("SELECT * FROM cached_departures WHERE stopId IN (:stopIds) ORDER BY countdown ASC")
    suspend fun getDeparturesForStops(stopIds: List<Int>): List<CachedDeparture>

    // ── Queries by RBL Number ───────────────────────────────────────────────

    @Query("SELECT * FROM cached_departures WHERE rblNumber = :rblNumber ORDER BY countdown ASC")
    suspend fun getDeparturesForRbl(rblNumber: Int): List<CachedDeparture>

    @Query("SELECT * FROM cached_departures WHERE rblNumber IN (:rblNumbers) ORDER BY countdown ASC")
    suspend fun getDeparturesForRbls(rblNumbers: List<Int>): List<CachedDeparture>

    // ── Reactive Flow Queries ───────────────────────────────────────────────

    @Query("SELECT * FROM cached_departures WHERE stopId IN (:stopIds) ORDER BY countdown ASC")
    fun observeDeparturesForStops(stopIds: List<Int>): Flow<List<CachedDeparture>>

    @Query("SELECT * FROM cached_departures WHERE rblNumber IN (:rblNumbers) ORDER BY countdown ASC")
    fun observeDeparturesForRbls(rblNumbers: List<Int>): Flow<List<CachedDeparture>>

    // ── Deletions & Eviction ────────────────────────────────────────────────

    @Query("DELETE FROM cached_departures WHERE stopId = :stopId")
    suspend fun deleteForStop(stopId: Int): Int

    @Query("DELETE FROM cached_departures WHERE stopId IN (:stopIds)")
    suspend fun deleteForStops(stopIds: List<Int>): Int

    @Query("DELETE FROM cached_departures WHERE rblNumber = :rblNumber")
    suspend fun deleteForRbl(rblNumber: Int): Int

    @Query("DELETE FROM cached_departures WHERE rblNumber IN (:rblNumbers)")
    suspend fun deleteForRbls(rblNumbers: List<Int>): Int

    @Query("DELETE FROM cached_departures WHERE cachedAtTimestamp < :cutoffTimestamp")
    suspend fun deleteOldDepartures(cutoffTimestamp: Long): Int

    @Query("DELETE FROM cached_departures")
    suspend fun deleteAll(): Int

    // ── Atomic Transactions ─────────────────────────────────────────────────

    @Transaction
    suspend fun replaceDeparturesForStops(stopIds: List<Int>, departures: List<CachedDeparture>) {
        deleteForStops(stopIds)
        insertDepartures(departures)
    }

    @Transaction
    suspend fun replaceDeparturesForRbls(rblNumbers: List<Int>, departures: List<CachedDeparture>) {
        deleteForRbls(rblNumbers)
        insertDepartures(departures)
    }
}
