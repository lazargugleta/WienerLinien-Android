package com.wienerlinien.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wienerlinien.app.model.AppDatabase
import com.wienerlinien.app.model.DepartureRepository
import com.wienerlinien.app.ui.CountdownFormatter
import com.wienerlinien.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WienerLinienTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        updateJob?.cancel()
        updateJob = scope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val repo = DepartureRepository(db)
                val stops = repo.getAllStopsOnce()

                if (stops.isEmpty()) {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Wiener Linien"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        tile.subtitle = "No stops saved"
                    }
                    tile.updateTile()
                    return@launch
                }

                // Prioritize Home stop, or first saved stop
                val targetStop = stops.find { it.isHome } ?: stops.first()
                val result = repo.fetchDepartures(listOf(targetStop.rblNumber))
                val allDepartures = result.getOrNull() ?: emptyList()
                val departures = repo.filterDeparturesForStop(allDepartures, targetStop)

                tile.state = Tile.STATE_ACTIVE
                tile.label = targetStop.customLabel?.takeIf { it.isNotBlank() } ?: targetStop.stopName

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (departures.isNotEmpty()) {
                        val first = departures.first()
                        tile.subtitle = "${first.lineName} · ${CountdownFormatter.formatCountdown(first.countdown)}"
                    } else {
                        tile.subtitle = "No departures"
                    }
                }
                tile.updateTile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        updateJob?.cancel()
        updateJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
