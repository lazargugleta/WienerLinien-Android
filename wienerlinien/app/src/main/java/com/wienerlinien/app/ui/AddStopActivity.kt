package com.wienerlinien.app.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wienerlinien.app.R
import com.wienerlinien.app.databinding.ActivityAddStopBinding
import com.wienerlinien.app.model.AppDatabase
import com.wienerlinien.app.model.DepartureRepository
import com.wienerlinien.app.model.SavedStop
import com.wienerlinien.app.util.StopSearchUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AddStopActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddStopBinding
    private lateinit var searchAdapter: StopSearchAdapter
    private val repository by lazy {
        DepartureRepository(AppDatabase.getDatabase(this))
    }

    private var selectedRbl: Int? = null
    private var selectedStopName: String = ""
    private var selectedPlatform: String? = null
    private var selectedServedLines: String? = null
    private var editingStopId: Int? = null
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var isUpdatingFromSelection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddStopBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingStopId = intent.getIntExtra("EXTRA_STOP_ID", -1).takeIf { it != -1 }

        setupToolbar()
        setupSearchResults()
        setupSearchInput()
        setupLineFilterWatcher()
        setupSaveButton()

        if (editingStopId != null) {
            loadStopForEditing(editingStopId!!)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (editingStopId != null) "Edit Stop" else "Add Stop"
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadStopForEditing(id: Int) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AddStopActivity)
            val repo = DepartureRepository(db)
            val stop = repo.getAllStopsOnce().find { it.id == id } ?: return@launch

            onStopPicked(stop.rblNumber, stop.stopName, stop.lineName, stop.customLabel, stop.direction, stop.platformLines)

            // Set max departures
            when (stop.maxDepartures) {
                1 -> binding.departureCountGroup.check(R.id.btnCount1)
                2 -> binding.departureCountGroup.check(R.id.btnCount2)
                4 -> binding.departureCountGroup.check(R.id.btnCount4)
                else -> binding.departureCountGroup.check(R.id.btnCount3)
            }

            binding.chipHomeProfile.isChecked = stop.isHome
            binding.chipWorkProfile.isChecked = stop.isWork

            binding.saveBtn.text = "Save Changes"
        }
    }

    private fun onStopPicked(rbl: Int, name: String, line: String?, label: String?, platform: String?, servedLines: String? = null) {
        isUpdatingFromSelection = true
        selectedRbl = rbl
        selectedStopName = name
        selectedPlatform = platform
        selectedServedLines = servedLines
        binding.searchInput.setText(name)
        binding.selectedStopCard.visibility = View.VISIBLE
        binding.selectedStopName.text = name

        binding.selectedPlatformBadge.text = if (!platform.isNullOrBlank()) {
            "Steig $platform · RBL $rbl"
        } else {
            "RBL $rbl"
        }

        val details = mutableListOf<String>()
        if (!servedLines.isNullOrBlank()) {
            details.add("Lines: $servedLines")
        }
        details.add("Steig ${platform ?: "?"} (RBL $rbl)")
        binding.selectedRbl.text = details.joinToString(" · ")

        binding.searchResultsList.visibility = View.GONE
        binding.saveConfig.visibility = View.VISIBLE

        if (line != null) binding.lineNameInput.setText(line)
        if (label != null) binding.customLabelInput.setText(label)

        val linesList = servedLines?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        renderLineChips(linesList, line)
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
                if (isAllSelected) ContextCompat.getColor(this@AddStopActivity, R.color.white)
                else ContextCompat.getColor(this@AddStopActivity, R.color.text_primary)
            )
            background = GradientDrawable().apply {
                cornerRadius = 8f * d
                if (isAllSelected) {
                    setColor(ContextCompat.getColor(this@AddStopActivity, R.color.accent))
                } else {
                    setColor(ContextCompat.getColor(this@AddStopActivity, R.color.separator))
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
                binding.lineNameInput.setText("")
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
                setTextColor(ContextCompat.getColor(this@AddStopActivity, R.color.white))
                background = GradientDrawable().apply {
                    cornerRadius = 8f * d
                    setColor(lineColor)
                    if (isLineSelected) {
                        setStroke((2.5f * d).toInt(), ContextCompat.getColor(this@AddStopActivity, R.color.text_primary))
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
                    binding.lineNameInput.setText(line)
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
                        val row = DepartureRowView(this@AddStopActivity)
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

    private fun setupLineFilterWatcher() {
        binding.lineNameInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromSelection) return
                val currentFilter = s?.toString()?.trim()
                val linesList = selectedServedLines?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                renderLineChips(linesList, currentFilter)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupSearchResults() {
        searchAdapter = StopSearchAdapter { result ->
            // User selected a stop from search results
            onStopPicked(result.rblNumber, result.stopName, null, null, result.platform, result.lines)
        }

        binding.searchResultsList.apply {
            layoutManager = LinearLayoutManager(this@AddStopActivity)
            adapter = searchAdapter
        }
    }

    private fun setupSearchInput() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromSelection) return

                val query = s?.toString() ?: return
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(250) // Debounce
                    if (query.length >= 2) {
                        binding.searchProgress.visibility = View.VISIBLE
                        val results = StopSearchUtil.searchStops(query)
                        binding.searchProgress.visibility = View.GONE
                        searchAdapter.submitList(results)
                        binding.searchResultsList.visibility =
                            if (results.isEmpty()) View.GONE else View.VISIBLE
                        if (results.isEmpty() && query.isNotBlank()) {
                            binding.noResultsText.visibility = View.VISIBLE
                        } else {
                            binding.noResultsText.visibility = View.GONE
                        }
                    } else {
                        binding.searchResultsList.visibility = View.GONE
                        binding.noResultsText.visibility = View.GONE
                    }
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isUpdatingFromSelection) return

                // Clear selection if user starts typing again
                if (selectedRbl != null) {
                    previewJob?.cancel()
                    selectedRbl = null
                    binding.selectedStopCard.visibility = View.GONE
                    binding.saveConfig.visibility = View.GONE
                }
            }
        })
    }

    private fun setupSaveButton() {
        binding.saveBtn.setOnClickListener {
            val rbl = selectedRbl ?: run {
                Toast.makeText(this, "Please select a stop first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val lineName = binding.lineNameInput.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }

            val customLabel = binding.customLabelInput.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }

            val maxDeps = when (binding.departureCountGroup.checkedButtonId) {
                R.id.btnCount1 -> 1
                R.id.btnCount2 -> 2
                R.id.btnCount4 -> 4
                else -> 3
            }

            val isHome = binding.chipHomeProfile.isChecked
            val isWork = binding.chipWorkProfile.isChecked

            val stop = SavedStop(
                id = editingStopId ?: 0,
                rblNumber = rbl,
                stopName = selectedStopName,
                lineName = lineName,
                lineType = null,  // Detected dynamically from API
                direction = selectedPlatform,
                platformLines = selectedServedLines,
                customLabel = customLabel,
                maxDepartures = maxDeps,
                isHome = isHome,
                isWork = isWork
            )

            lifecycleScope.launch {
                if (editingStopId != null) {
                    repository.updateStop(stop)
                    Toast.makeText(this@AddStopActivity, "Stop updated!", Toast.LENGTH_SHORT).show()
                } else {
                    repository.saveStop(stop)
                    Toast.makeText(this@AddStopActivity, "Stop added!", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
    }
}
