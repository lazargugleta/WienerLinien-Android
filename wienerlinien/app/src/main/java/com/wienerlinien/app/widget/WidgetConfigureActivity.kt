package com.wienerlinien.app.widget

import android.appwidget.AppWidgetManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wienerlinien.app.R
import com.wienerlinien.app.databinding.ActivityWidgetConfigureBinding
import com.wienerlinien.app.model.AppDatabase
import com.wienerlinien.app.model.DepartureRepository
import com.wienerlinien.app.ui.DepartureRowView
import com.wienerlinien.app.ui.StopSearchAdapter
import com.wienerlinien.app.ui.VehicleBadgeHelper
import com.wienerlinien.app.util.StopSearchUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WidgetConfigureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetConfigureBinding
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val selectedStops = mutableListOf<WidgetPrefs.StopConfig>()
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var isUpdatingFromSelection = false

    private val repository by lazy {
        DepartureRepository(AppDatabase.getDatabase(this))
    }

    private lateinit var searchAdapter: StopSearchAdapter
    private lateinit var savedStopsAdapter: SavedStopsAdapter
    private lateinit var selectedStopsAdapter: SelectedStopsAdapter

    // Temporary selection holder
    private var pendingRbl: Int? = null
    private var pendingName: String = ""
    private var pendingPlatform: String? = null
    private var pendingServedLines: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        widgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setResult(RESULT_CANCELED)
        binding = ActivityWidgetConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAdapters()
        setupSearch()
        setupLineFilterWatcher()
        setupAddButton()
        setupSaveButton()

        loadExistingConfig()
        loadSavedStops()
    }

    private fun setupAdapters() {
        // 1. Saved stops (chips)
        savedStopsAdapter = SavedStopsAdapter { stop ->
            // Click chip: review/edit before adding to widget
            onStopPicked(stop.rblNumber, stop.stopName, stop.direction, stop.platformLines, stop.lineName)
        }
        binding.savedStopsList.apply {
            layoutManager = LinearLayoutManager(this@WidgetConfigureActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = savedStopsAdapter
        }

        // 2. Search results
        searchAdapter = StopSearchAdapter { result ->
            onStopPicked(result.rblNumber, result.stopName, result.platform, result.lines, null)
        }
        binding.searchResultsList.apply {
            layoutManager = LinearLayoutManager(this@WidgetConfigureActivity)
            adapter = searchAdapter
        }

        // 3. Current selection for widget
        selectedStopsAdapter = SelectedStopsAdapter { index ->
            selectedStops.removeAt(index)
            updateSelectedStopsUi()
        }
        binding.selectedStopsList.apply {
            layoutManager = LinearLayoutManager(this@WidgetConfigureActivity)
            adapter = selectedStopsAdapter
        }
    }

    private fun loadSavedStops() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@WidgetConfigureActivity)
            val repo = DepartureRepository(db)
            val stops = repo.getAllStopsOnce()
            if (stops.isNotEmpty()) {
                binding.savedStopsTitle.visibility = View.VISIBLE
                binding.savedStopsList.visibility = View.VISIBLE
                savedStopsAdapter.submitList(stops)
            }
        }
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromSelection) return
                val q = s?.toString() ?: return
                searchJob?.cancel()

                // Hide review card when starting new search
                previewJob?.cancel()
                binding.selectedCard.visibility = View.GONE

                if (q.length >= 2) {
                    searchJob = lifecycleScope.launch {
                        delay(250)
                        val results = StopSearchUtil.searchStops(q)
                        searchAdapter.submitList(results)
                        binding.searchResultsList.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
                    }
                } else {
                    binding.searchResultsList.visibility = View.GONE
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupLineFilterWatcher() {
        binding.lineFilterInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromSelection) return
                val currentFilter = s?.toString()?.trim()
                val linesList = pendingServedLines?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                renderLineChips(linesList, currentFilter)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun onStopPicked(rbl: Int, name: String, platform: String?, servedLines: String?, lineFilter: String?) {
        isUpdatingFromSelection = true
        pendingRbl = rbl
        pendingName = name
        pendingPlatform = platform
        pendingServedLines = servedLines

        binding.searchInput.setText(name)
        binding.searchResultsList.visibility = View.GONE
        binding.selectedCard.visibility = View.VISIBLE
        binding.selectedName.text = name

        binding.selectedPlatformBadge.text = if (!platform.isNullOrBlank()) {
            "Steig $platform · RBL $rbl"
        } else {
            "RBL $rbl"
        }

        val details = mutableListOf<String>()
        if (!servedLines.isNullOrBlank()) details.add("Lines: $servedLines")
        details.add("Steig ${platform ?: "?"} (RBL $rbl)")
        binding.selectedDetail.text = details.joinToString(" · ")

        binding.lineFilterInput.setText(lineFilter ?: "")

        val linesList = servedLines?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        renderLineChips(linesList, lineFilter)
        loadLivePreview(rbl)

        isUpdatingFromSelection = false
    }

    private fun renderLineChips(lines: List<String>, activeFilter: String?) {
        val container = binding.selectedLineChipsContainer
        container.removeAllViews()
        val d = resources.displayMetrics.density

        if (lines.isEmpty()) {
            binding.linesHeading.visibility = View.GONE
            container.visibility = View.GONE
            return
        }

        binding.linesHeading.visibility = View.VISIBLE
        container.visibility = View.VISIBLE

        // 1. "All Lines" chip
        val isAllSelected = activeFilter.isNullOrBlank()
        val allChip = TextView(this).apply {
            text = "All Lines"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(
                if (isAllSelected) ContextCompat.getColor(this@WidgetConfigureActivity, R.color.white)
                else ContextCompat.getColor(this@WidgetConfigureActivity, R.color.text_primary)
            )
            background = GradientDrawable().apply {
                cornerRadius = 8f * d
                if (isAllSelected) {
                    setColor(ContextCompat.getColor(this@WidgetConfigureActivity, R.color.accent))
                } else {
                    setColor(ContextCompat.getColor(this@WidgetConfigureActivity, R.color.separator))
                }
            }
            val padH = (12f * d).toInt()
            val padV = (6f * d).toInt()
            setPadding(padH, padV, padH, padV)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8f * d).toInt()
            }
            layoutParams = lp
            setOnClickListener {
                binding.lineFilterInput.setText("")
                renderLineChips(lines, "")
            }
        }
        container.addView(allChip)

        // 2. Specific Line chips
        for (line in lines) {
            val isLineSelected = activeFilter?.equals(line, ignoreCase = true) == true
            val lineColor = VehicleBadgeHelper.getLineColor(line)
            val chip = TextView(this).apply {
                text = line
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(this@WidgetConfigureActivity, R.color.white))
                background = GradientDrawable().apply {
                    cornerRadius = 8f * d
                    setColor(lineColor)
                    if (isLineSelected) {
                        setStroke((2.5f * d).toInt(), ContextCompat.getColor(this@WidgetConfigureActivity, R.color.text_primary))
                    }
                }
                val padH = (12f * d).toInt()
                val padV = (6f * d).toInt()
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8f * d).toInt()
                }
                layoutParams = lp
                setOnClickListener {
                    binding.lineFilterInput.setText(line)
                    renderLineChips(lines, line)
                }
            }
            container.addView(chip)
        }
    }

    private fun loadLivePreview(rbl: Int) {
        previewJob?.cancel()
        binding.previewDeparturesContainer.removeAllViews()
        binding.previewProgress.visibility = View.VISIBLE
        binding.previewStatus.visibility = View.VISIBLE
        binding.previewStatus.text = "Checking live departures & destination direction…"

        previewJob = lifecycleScope.launch {
            try {
                val result = repository.fetchDepartures(listOf(rbl), forceRefresh = true)
                binding.previewProgress.visibility = View.GONE
                val departures = result.getOrNull() ?: emptyList()
                if (departures.isEmpty()) {
                    binding.previewStatus.text = "No upcoming departures scheduled at this platform right now"
                    binding.previewStatus.visibility = View.VISIBLE
                } else {
                    binding.previewStatus.visibility = View.GONE
                    val topDepartures = departures.take(3)
                    for (dep in topDepartures) {
                        val row = DepartureRowView(this@WidgetConfigureActivity)
                        row.bind(dep)
                        binding.previewDeparturesContainer.addView(row)
                    }
                }
            } catch (e: Exception) {
                binding.previewProgress.visibility = View.GONE
                binding.previewStatus.text = "Live preview unavailable offline"
                binding.previewStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun setupAddButton() {
        binding.addStopToWidgetBtn.setOnClickListener {
            val rbl = pendingRbl ?: return@setOnClickListener

            val stopConfig = WidgetPrefs.StopConfig(
                rblNumber = rbl,
                stopName = pendingName,
                lineName = binding.lineFilterInput.text?.toString()?.trim() ?: "",
                customLabel = "", // Aggregated widget uses stop names or "Multiple Stops"
                platformLines = pendingServedLines ?: ""
            )

            previewJob?.cancel()
            selectedStops.add(stopConfig)
            updateSelectedStopsUi()

            // Reset for next add
            binding.selectedCard.visibility = View.GONE
            binding.searchInput.setText("")
            pendingRbl = null
        }
    }

    private fun updateSelectedStopsUi() {
        selectedStopsAdapter.updateItems(selectedStops)
        val hasSelection = selectedStops.isNotEmpty()
        binding.selectedStopsTitle.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.selectedStopsList.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.globalSettings.visibility = if (hasSelection) View.VISIBLE else View.GONE
        binding.saveWidgetBtn.isEnabled = hasSelection
    }

    private fun loadExistingConfig() {
        val existingConfig = WidgetPrefs.getWidgetConfig(this, widgetId) ?: return
        if (existingConfig.stops.isNotEmpty()) {
            selectedStops.clear()
            selectedStops.addAll(existingConfig.stops)
            updateSelectedStopsUi()
        }

        when (existingConfig.maxDepartures) {
            1 -> binding.departureCountGroup.check(R.id.btnCount1)
            3 -> binding.departureCountGroup.check(R.id.btnCount3)
            else -> binding.departureCountGroup.check(R.id.btnCount2)
        }

        if (existingConfig.isGrouped) {
            binding.displayModeToggleGroup.check(R.id.btnModeGrouped)
        } else {
            binding.displayModeToggleGroup.check(R.id.btnModeTimeline)
        }

        when (existingConfig.opacity) {
            20 -> binding.opacityToggleGroup.check(R.id.btnOpacityClear)
            60 -> binding.opacityToggleGroup.check(R.id.btnOpacityGlass)
            else -> binding.opacityToggleGroup.check(R.id.btnOpacitySolid)
        }
    }

    private fun setupSaveButton() {
        binding.saveWidgetBtn.setOnClickListener {
            if (selectedStops.isEmpty()) return@setOnClickListener

            val maxDeps = when (binding.departureCountGroup.checkedButtonId) {
                R.id.btnCount1 -> 1
                R.id.btnCount3 -> 3
                else -> 2
            }

            val isGrouped = binding.displayModeToggleGroup.checkedButtonId != R.id.btnModeTimeline

            val opacity = when (binding.opacityToggleGroup.checkedButtonId) {
                R.id.btnOpacityClear -> 20
                R.id.btnOpacityGlass -> 60
                else -> 100
            }

            val config = WidgetPrefs.WidgetConfig(
                stops = selectedStops,
                maxDepartures = maxDeps,
                groupByStation = isGrouped,
                backgroundOpacityPercent = opacity
            )

            WidgetPrefs.saveWidgetConfig(this, widgetId, config)

            val manager = AppWidgetManager.getInstance(this)
            manager.updateAppWidget(widgetId, DepartureWidgetProvider.buildLoadingView(this, widgetId))
            DepartureWidgetProvider.updateAllWidgets(this)

            val resultIntent = android.content.Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }
}
