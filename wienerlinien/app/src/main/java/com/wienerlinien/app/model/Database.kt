package com.wienerlinien.app.model

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ── DAO ────────────────────────────────────────────────────────────────────

@Dao
interface SavedStopDao {

    @Query("SELECT * FROM saved_stops ORDER BY sortOrder ASC, addedAt ASC")
    fun getAllStops(): Flow<List<SavedStop>>

    @Query("SELECT * FROM saved_stops ORDER BY sortOrder ASC, addedAt ASC")
    suspend fun getAllStopsOnce(): List<SavedStop>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stop: SavedStop): Long

    @Delete
    suspend fun delete(stop: SavedStop)

    @Query("DELETE FROM saved_stops WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Update
    suspend fun update(stop: SavedStop)

    @Update
    suspend fun updateAll(stops: List<SavedStop>)

    @Query("SELECT * FROM saved_stops WHERE id = :id")
    suspend fun getById(id: Int): SavedStop?

    @Query("SELECT * FROM saved_stops WHERE isHome = 1 LIMIT 1")
    suspend fun getHomeStop(): SavedStop?

    @Query("SELECT * FROM saved_stops WHERE isWork = 1 LIMIT 1")
    suspend fun getWorkStop(): SavedStop?
}

// ── Database ───────────────────────────────────────────────────────────────

@Database(entities = [SavedStop::class, CachedDeparture::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun savedStopDao(): SavedStopDao
    abstract fun cachedDepartureDao(): CachedDepartureDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_departures` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `stopId` INTEGER,
                        `rblNumber` INTEGER NOT NULL,
                        `lineName` TEXT NOT NULL,
                        `lineType` TEXT,
                        `destination` TEXT NOT NULL,
                        `countdown` INTEGER NOT NULL,
                        `isRealtime` INTEGER NOT NULL,
                        `departureTimePlanned` TEXT,
                        `departureTimeReal` TEXT,
                        `cachedAtTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_departures_stopId` ON `cached_departures` (`stopId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_departures_rblNumber` ON `cached_departures` (`rblNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_departures_cachedAtTimestamp` ON `cached_departures` (`cachedAtTimestamp`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `saved_stops` ADD COLUMN `isHome` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `saved_stops` ADD COLUMN `isWork` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wienerlinien_db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
