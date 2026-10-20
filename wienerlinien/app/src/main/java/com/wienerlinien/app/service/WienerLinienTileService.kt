package com.wienerlinien.app.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.wienerlinien.app.model.AppDatabase
import com.wienerlinien.app.model.DepartureRepository
import com.wienerlinien.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class WienerLinienTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        scope.launch {
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
                val departures = result.getOrNull() ?: emptyList()

                tile.state = Tile.STATE_ACTIVE
                tile.label = targetStop.customLabel?.takeIf { it.isNotBlank() } ?: targetStop.stopName

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (departures.isNotEmpty()) {
                        val first = departures.first()
                        tile.subtitle = "${first.lineName} · ${first.countdown} min"
                    } else {
                        tile.subtitle = "Checking departures…"
                    }
                }
                tile.updateTile()
            } catch (e: Exception) {
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
