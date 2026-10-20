package com.wienerlinien.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wienerlinien.app.BuildConfig
import com.wienerlinien.app.R
import com.wienerlinien.app.alarm.DepartureAlarmManager
import com.wienerlinien.app.databinding.ActivityMainBinding
import com.wienerlinien.app.model.DepartureItem
import com.wienerlinien.app.model.SavedStop
import com.wienerlinien.app.update.AppUpdateManager
import com.wienerlinien.app.update.UpdateDialogHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: DepartureGroupAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var isDragging = false
    private val updateManager by lazy { AppUpdateManager(this) }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchNearbyStopsWithLocation()
        } else {
            Snackbar.make(binding.root, "Location permission needed to discover nearby stops", Snackbar.LENGTH_LONG).show()
            binding.chipAll.isChecked = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupObservers()
        setupClickListeners()

        viewModel.startAutoRefresh()
        checkAppUpdate(isManual = false)
    }

    private fun setupRecyclerView() {
        adapter = DepartureGroupAdapter(
            onDeleteStop = { stop -> deleteStopWithUndo(stop) },
            onEditStop = { stop ->
                if (stop.id > 0) {
                    val intent = Intent(this, AddStopActivity::class.java).apply {
                        putExtra("EXTRA_STOP_ID", stop.id)
                    }
                    startActivity(intent)
                } else {
                    // Temporary nearby stop: save directly
                    viewModel.saveStop(stop.copy(id = 0))
                    Snackbar.make(binding.root, "Added ${stop.stopName} to saved stops", Snackbar.LENGTH_SHORT).show()
                }
            },
            onStartDrag = { holder ->
                itemTouchHelper.startDrag(holder)
            },
            onDepartureClick = { dep, stop ->
                showDepartureReminderDialog(dep, stop)
            },
            onToggleCommuteTag = { stop ->
                showCommuteTagDialog(stop)
            }
        )

        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                    adapter.moveItem(fromPos, toPos)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    isDragging = true
                    viewHolder?.itemView?.let { view ->
                        view.alpha = 0.85f
                        view.scaleX = 1.02f
                        view.scaleY = 1.02f
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                isDragging = false
                viewHolder.itemView.let { view ->
                    view.alpha = 1.0f
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                }
                viewModel.reorderStops(adapter.getOrderedStops())
            }

            override fun isLongPressDragEnabled(): Boolean = false
        }

        itemTouchHelper = ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(false)
        }
    }

    private fun deleteStopWithUndo(stop: SavedStop) {
        if (stop.id <= 0) return
        viewModel.deleteStop(stop)
        val stopLabel = stop.customLabel?.takeIf { it.isNotBlank() } ?: stop.stopName
        Snackbar.make(binding.root, "Removed $stopLabel", Snackbar.LENGTH_LONG)
            .setAction("UNDO") {
                viewModel.saveStop(stop)
            }
            .show()
    }

    private fun setupObservers() {
        viewModel.savedStops.observe(this) { updateListDisplay() }
        viewModel.departures.observe(this) { updateListDisplay() }
        viewModel.currentFilter.observe(this) { updateListDisplay() }
        viewModel.disruptions.observe(this) { updateListDisplay() }
        viewModel.nearbyStops.observe(this) { updateListDisplay() }

        viewModel.isLoading.observe(this) { loading ->
            binding.swipeRefresh.isRefreshing = loading
            binding.refreshBtn.isEnabled = !loading
        }

        // Error banner with retry
        viewModel.error.observe(this) { error ->
            if (!error.isNullOrBlank()) {
                binding.errorBannerContainer.visibility = View.VISIBLE
                binding.errorBannerText.text = error
            } else {
                binding.errorBannerContainer.visibility = View.GONE
            }
        }

        viewModel.lastUpdated.observe(this) { timestamp ->
            if (timestamp > 0) {
                val time = CountdownFormatter.formatUpdatedTime(timestamp)
                binding.lastUpdatedText.text = "Updated $time"
                binding.lastUpdatedText.visibility = View.VISIBLE
            }
        }
    }

    private fun updateListDisplay() {
        if (isDragging) return
        val filter = viewModel.currentFilter.value ?: MainViewModel.FilterMode.ALL
        val allStops = viewModel.savedStops.value ?: emptyList()
        val departures = viewModel.departures.value
        val disruptions = viewModel.disruptions.value ?: emptyList()

        val displayStops: List<SavedStop> = when (filter) {
            MainViewModel.FilterMode.ALL -> allStops
            MainViewModel.FilterMode.HOME -> allStops.filter { it.isHome }
            MainViewModel.FilterMode.WORK -> allStops.filter { it.isWork }
            MainViewModel.FilterMode.NEARBY -> {
                val nearby = viewModel.nearbyStops.value ?: emptyList()
                nearby.map { nb ->
                    SavedStop(
                        id = -nb.stop.rblNumber,
                        rblNumber = nb.stop.rblNumber,
                        stopName = nb.stop.stopName,
                        lineName = null,
                        lineType = null,
                        direction = nb.stop.platform,
                        platformLines = nb.stop.lines,
                        customLabel = "${nb.stop.stopName} (${nb.distanceMeters}m)",
                        maxDepartures = 3
                    )
                }
            }
        }

        if (displayStops.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            when (filter) {
                MainViewModel.FilterMode.HOME -> {
                    binding.emptyStateTitle.text = "No Home station set"
                    binding.emptyStateSubtitle.text = "Tag your primary station as Home to view its departures here."
                    binding.emptyStateAddBtn.text = "Add a Stop"
                }
                MainViewModel.FilterMode.WORK -> {
                    binding.emptyStateTitle.text = "No Work station set"
                    binding.emptyStateSubtitle.text = "Tag your office station as Work to view its departures here."
                    binding.emptyStateAddBtn.text = "Add a Stop"
                }
                MainViewModel.FilterMode.NEARBY -> {
                    binding.emptyStateTitle.text = "No nearby stations found"
                    binding.emptyStateSubtitle.text = "Ensure GPS is active to find departures within walking distance."
                    binding.emptyStateAddBtn.text = "Retry Location"
                }
                MainViewModel.FilterMode.ALL -> {
                    binding.emptyStateTitle.text = "No stops added yet"
                    binding.emptyStateSubtitle.text = "Add your favorite Vienna U-Bahn, Tram, and Bus stops to see real-time departures."
                    binding.emptyStateAddBtn.text = "Add a Stop"
                }
            }
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }

        adapter.submitData(displayStops, departures, disruptions)
    }

    private fun setupClickListeners() {
        // Filter chip selection
        binding.filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipHome) -> viewModel.setFilterMode(MainViewModel.FilterMode.HOME)
                checkedIds.contains(R.id.chipWork) -> viewModel.setFilterMode(MainViewModel.FilterMode.WORK)
                checkedIds.contains(R.id.chipNearby) -> {
                    viewModel.setFilterMode(MainViewModel.FilterMode.NEARBY)
                    fetchNearbyStopsWithLocation()
                }
                else -> viewModel.setFilterMode(MainViewModel.FilterMode.ALL)
            }
        }

        // Top Toolbar action buttons
        binding.refreshBtn.setOnClickListener {
            viewModel.manualRefresh()
        }

        binding.addStopBtn.setOnClickListener {
            startActivity(Intent(this, AddStopActivity::class.java))
        }

        // SwipeRefresh
        binding.swipeRefresh.apply {
            setColorSchemeResources(R.color.accent)
            setOnRefreshListener {
                viewModel.manualRefresh()
            }
        }

        // Empty state action button
        binding.emptyStateAddBtn.setOnClickListener {
            val filter = viewModel.currentFilter.value ?: MainViewModel.FilterMode.ALL
            when (filter) {
                MainViewModel.FilterMode.NEARBY -> fetchNearbyStopsWithLocation()
                else -> startActivity(Intent(this, AddStopActivity::class.java))
            }
        }

        // Error banner retry button
        binding.errorBannerRetry.setOnClickListener {
            viewModel.manualRefresh()
        }

        // More options / Update menu
        binding.menuBtn.setOnClickListener { view ->
            showOptionsMenu(view)
        }
    }

    private fun fetchNearbyStopsWithLocation() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }

        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

        if (location != null) {
            viewModel.searchNearbyStops(location.latitude, location.longitude)
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    locationManager.removeUpdates(this)
                    viewModel.searchNearbyStops(loc.latitude, loc.longitude)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            val isGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val provider = if (isGps) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            try {
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Unable to acquire current location", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCommuteTagDialog(stop: SavedStop) {
        if (stop.id <= 0) {
            // Unsaved nearby stop
            MaterialAlertDialogBuilder(this)
                .setTitle(stop.stopName)
                .setMessage("Save this nearby stop to your stations list?")
                .setPositiveButton("Save Stop") { _, _ ->
                    viewModel.saveStop(stop.copy(id = 0, customLabel = null))
                    Snackbar.make(binding.root, "Saved ${stop.stopName}", Snackbar.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val checkedItems = booleanArrayOf(stop.isHome, stop.isWork)
        val options = arrayOf(
            "🏠 Home Station",
            "💼 Work Station"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(stop.customLabel?.takeIf { it.isNotBlank() } ?: stop.stopName)
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val newIsHome = checkedItems[0]
                val newIsWork = checkedItems[1]
                viewModel.updateStopCommute(stop.id, newIsHome, newIsWork)
                val labels = mutableListOf<String>()
                if (newIsHome) labels.add("Home 🏠")
                if (newIsWork) labels.add("Work 💼")
                val msg = if (labels.isNotEmpty()) "Set as ${labels.joinToString(" & ")}" else "Commute tags cleared"
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDepartureReminderDialog(dep: DepartureItem, stop: SavedStop) {
        if (dep.countdown <= 1) {
            Snackbar.make(binding.root, "${dep.lineName} to ${dep.towards} is arriving now!", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                return
            }
        }

        val options = mutableListOf<Pair<String, Int>>()
        if (dep.countdown > 1) options.add("1 min before departure" to 1)
        if (dep.countdown > 3) options.add("3 min before departure" to 3)
        if (dep.countdown > 5) options.add("5 min before departure" to 5)
        if (dep.countdown > 10) options.add("10 min before departure" to 10)

        if (options.isEmpty()) {
            options.add("1 min before departure" to 1)
        }

        val labels = options.map { it.first }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Set Departure Alarm")
            .setMessage("${dep.lineName} → ${dep.towards}\nDeparts in ${dep.countdown} min from ${stop.stopName}")
            .setItems(labels) { _, which ->
                val minutesBefore = options[which].second
                val scheduled = DepartureAlarmManager.scheduleDepartureReminder(
                    context = this,
                    lineName = dep.lineName,
                    destination = dep.towards,
                    stopName = stop.stopName,
                    countdownMinutes = dep.countdown,
                    minutesBefore = minutesBefore
                )
                if (scheduled) {
                    Snackbar.make(
                        binding.root,
                        "⏰ Alarm set for $minutesBefore min before departure",
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    Snackbar.make(binding.root, "Could not schedule alarm", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAppUpdate(isManual: Boolean) {
        lifecycleScope.launch {
            if (isManual) {
                Snackbar.make(binding.root, "Checking for updates…", Snackbar.LENGTH_SHORT).show()
            } else if (!updateManager.shouldCheckAutomatically()) {
                return@launch
            }

            val result = updateManager.checkForUpdate()
            result.onSuccess { updateInfo ->
                if (updateInfo != null) {
                    UpdateDialogHelper.showUpdateDialog(
                        this@MainActivity,
                        lifecycleScope,
                        updateInfo,
                        updateManager
                    )
                } else if (isManual) {
                    Snackbar.make(
                        binding.root,
                        "Wiener Linien is up to date (v${BuildConfig.VERSION_NAME})",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            result.onFailure { error ->
                if (isManual) {
                    Snackbar.make(
                        binding.root,
                        "Could not check for updates: ${error.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showOptionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Check for updates")
        popup.menu.add(0, 2, 1, "Update server settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    checkAppUpdate(isManual = true)
                    true
                }
                2 -> {
                    showUpdateSettingsDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showUpdateSettingsDialog() {
        val input = EditText(this).apply {
            setText(updateManager.getUpdateUrl())
            setSelection(text.length)
            hint = "Enter URL to version.json"
        }

        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Update Server URL")
            .setMessage("The app checks this JSON endpoint over the internet for new releases.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = input.text.toString().trim()
                updateManager.setUpdateUrl(newUrl.ifBlank { null })
                Snackbar.make(binding.root, "Update URL updated", Snackbar.LENGTH_SHORT).show()
            }
            .setNeutralButton("Reset Default") { _, _ ->
                updateManager.setUpdateUrl(null)
                Snackbar.make(binding.root, "Reset to default update URL", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopAutoRefresh()
    }
}
