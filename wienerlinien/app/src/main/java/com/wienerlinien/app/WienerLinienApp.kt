package com.wienerlinien.app

import android.app.Application
import com.wienerlinien.app.model.AppDatabase
import com.wienerlinien.app.model.DepartureRepository
import com.wienerlinien.app.util.StopSearchUtil
import com.wienerlinien.app.widget.WidgetRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WienerLinienApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Pre-warm StopSearchUtil and prune stale offline cache (>24h) asynchronously
        StopSearchUtil.init(this)
        applicationScope.launch {
            try {
                StopSearchUtil.searchStops("")
            } catch (_: Exception) {
                // Ignore initialization failures in background
            }
            try {
                DepartureRepository(AppDatabase.getDatabase(this@WienerLinienApp)).pruneStaleCache()
            } catch (_: Exception) {
                // Ignore cache pruning failures
            }
        }

        // Schedule periodic widget background updates (every 15 min via WorkManager)
        // The 30-second refresh happens via broadcasts from the foreground app
        WidgetRefreshWorker.schedule(this)
    }
}
