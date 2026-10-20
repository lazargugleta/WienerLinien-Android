package com.wienerlinien.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.wienerlinien.app.model.*
import com.wienerlinien.app.widget.DepartureWidgetProvider
import kotlinx.coroutines.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = DepartureRepository(db)

    val savedStops: LiveData<List<SavedStop>> = repository.savedStops.asLiveData()

    private val _departures = MutableLiveData<Map<Int, List<DepartureItem>>>()
    val departures: LiveData<Map<Int, List<DepartureItem>>> = _departures

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _lastUpdated = MutableLiveData<Long>(0)
    val lastUpdated: LiveData<Long> = _lastUpdated

    enum class FilterMode { ALL, HOME, WORK, NEARBY }

    private val _currentFilter = MutableLiveData(FilterMode.ALL)
    val currentFilter: LiveData<FilterMode> = _currentFilter

    private val _disruptions = MutableLiveData<List<TrafficInfoItem>>(emptyList())
    val disruptions: LiveData<List<TrafficInfoItem>> = _disruptions

    private val _nearbyStops = MutableLiveData<List<com.wienerlinien.app.util.StopSearchUtil.NearbyStop>>(emptyList())
    val nearbyStops: LiveData<List<com.wienerlinien.app.util.StopSearchUtil.NearbyStop>> = _nearbyStops

    fun setFilterMode(mode: FilterMode) {
        _currentFilter.value = mode
    }

    fun loadDisruptions() {
        viewModelScope.launch {
            val result = repository.fetchDisruptions()
            result.onSuccess { items ->
                _disruptions.postValue(items)
            }
        }
    }

    fun toggleHomeStop(stop: SavedStop) {
        viewModelScope.launch {
            val newHomeId = if (stop.isHome) null else stop.id
            repository.setHomeStop(newHomeId)
            broadcastWidgetUpdate()
        }
    }

    fun setHomeStop(stopId: Int?) {
        viewModelScope.launch {
            repository.setHomeStop(stopId)
            broadcastWidgetUpdate()
        }
    }

    fun toggleWorkStop(stop: SavedStop) {
        viewModelScope.launch {
            val newWorkId = if (stop.isWork) null else stop.id
            repository.setWorkStop(newWorkId)
            broadcastWidgetUpdate()
        }
    }

    fun setWorkStop(stopId: Int?) {
        viewModelScope.launch {
            repository.setWorkStop(stopId)
            broadcastWidgetUpdate()
        }
    }

    fun searchNearbyStops(lat: Double, lon: Double) {
        viewModelScope.launch {
            _isLoading.postValue(true)
            val nearby = com.wienerlinien.app.util.StopSearchUtil.findNearbyStops(lat, lon)
            _nearbyStops.postValue(nearby)
            if (nearby.isNotEmpty()) {
                val rbls = nearby.take(6).map { it.stop.rblNumber }
                val result = repository.fetchDepartures(rbls, forceRefresh = false)
                result.onSuccess { allItems ->
                    val currentMap = _departures.value?.toMutableMap() ?: mutableMapOf()
                    currentMap.putAll(allItems.groupBy { it.rbl })
                    _departures.postValue(currentMap)
                }
            }
            _isLoading.postValue(false)
        }
    }

    // 30-second auto-refresh (Wiener Linien fair-use minimum = 15s)
    private val REFRESH_INTERVAL_MS = 30_000L
    private var refreshJob: Job? = null

    fun startAutoRefresh() {
        stopAutoRefresh()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                fetchDepartures()
                loadDisruptions()
                // Broadcast to home screen widgets while app is in foreground
                broadcastWidgetUpdate()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun manualRefresh() {
        viewModelScope.launch {
            fetchDepartures(forceRefresh = true)
            loadDisruptions()
            broadcastWidgetUpdate()
        }
    }

    private suspend fun fetchDepartures(forceRefresh: Boolean = false) {
        val stops = repository.getAllStopsOnce()
        if (stops.isEmpty()) {
            _departures.postValue(emptyMap())
            return
        }

        _isLoading.postValue(true)
        _error.postValue(null)

        // Batch all RBL numbers into one API call — the monitor endpoint supports
        // multiple stopId query params: ?stopId=252&stopId=4116
        val rblNumbers = stops.map { it.rblNumber }.distinct()

        try {
            val result = repository.fetchDepartures(rblNumbers, forceRefresh = forceRefresh)
            result.onSuccess { allItems ->
                val grouped = allItems.groupBy { it.rbl }
                _departures.postValue(grouped)
                val isOffline = allItems.any { it.isCached }
                if (isOffline) {
                    _error.postValue("Offline. Showing cached departures.")
                    val lastUpdatedMillis = allItems.map { it.cachedAtMillis }.maxOrNull() ?: System.currentTimeMillis()
                    _lastUpdated.postValue(lastUpdatedMillis)
                } else {
                    _error.postValue(null)
                    _lastUpdated.postValue(System.currentTimeMillis())
                }
            }
            result.onFailure { e ->
                _error.postValue(e.message ?: "Failed to load departures")
            }
        } finally {
            _isLoading.postValue(false)
        }
    }

    private fun broadcastWidgetUpdate() {
        DepartureWidgetProvider.updateAllWidgets(getApplication(), forceRefresh = false)
    }

    fun deleteStop(stop: SavedStop) {
        viewModelScope.launch { repository.deleteStop(stop) }
    }

    fun saveStop(stop: SavedStop) {
        viewModelScope.launch {
            repository.saveStop(stop)
            fetchDepartures(forceRefresh = true)
            broadcastWidgetUpdate()
        }
    }

    fun reorderStops(stops: List<SavedStop>) {
        viewModelScope.launch {
            repository.updateStopsOrder(stops)
            broadcastWidgetUpdate()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoRefresh()
    }
}
