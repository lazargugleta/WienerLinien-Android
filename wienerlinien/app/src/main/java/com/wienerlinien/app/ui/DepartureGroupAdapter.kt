package com.wienerlinien.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.wienerlinien.app.R
import com.wienerlinien.app.model.DepartureItem
import com.wienerlinien.app.model.SavedStop
import com.google.android.material.button.MaterialButton

/**
 * Shows one card per saved stop, with up to 3 departure rows inside.
 */
class DepartureGroupAdapter(
    private val onDeleteStop: (SavedStop) -> Unit,
    private val onEditStop: (SavedStop) -> Unit,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null,
    private val onDepartureClick: ((DepartureItem, SavedStop) -> Unit)? = null,
    private val onToggleCommuteTag: ((SavedStop) -> Unit)? = null
) : RecyclerView.Adapter<DepartureGroupAdapter.StopViewHolder>() {

    private val items = mutableListOf<StopWithDepartures>()
    private var currentDisruptions: List<com.wienerlinien.app.model.TrafficInfoItem> = emptyList()

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in items.indices || toPosition !in items.indices) return
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                java.util.Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                java.util.Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    fun getOrderedStops(): List<SavedStop> = items.map { it.stop }

    enum class DisplayState { FETCHING, NO_DEPARTURES, LINE_NOT_RUNNING, HAS_DATA }

    data class StopWithDepartures(
        val stop: SavedStop,
        val departures: List<DepartureItem>,
        val state: DisplayState
    )

    fun submitData(
        stops: List<SavedStop>,
        deps: Map<Int, List<DepartureItem>>?,
        disruptions: List<com.wienerlinien.app.model.TrafficInfoItem> = emptyList()
    ) {
        currentDisruptions = disruptions
        items.clear()
        stops.forEach { stop ->
            if (deps == null) {
                items.add(StopWithDepartures(stop, emptyList(), DisplayState.FETCHING))
            } else {
                val stopDeps = deps[stop.rblNumber]
                if (stopDeps.isNullOrEmpty()) {
                    items.add(StopWithDepartures(stop, emptyList(), DisplayState.NO_DEPARTURES))
                } else {
                    var filtered = stopDeps
                    if (!stop.lineName.isNullOrBlank()) {
                        filtered = filtered.filter { it.lineName.equals(stop.lineName, ignoreCase = true) }
                    }
                    if (!stop.direction.isNullOrBlank()) {
                        val dir = stop.direction.trim()
                        if (dir.isNotEmpty() && !(dir.length <= 2 && (dir.all { it.isDigit() } || dir.length == 1))) {
                            filtered = filtered.filter { it.towards.contains(dir, ignoreCase = true) }
                        }
                    }

                    if (filtered.isEmpty()) {
                        items.add(StopWithDepartures(stop, emptyList(), DisplayState.LINE_NOT_RUNNING))
                    } else {
                        items.add(StopWithDepartures(stop, filtered.take(stop.maxDepartures), DisplayState.HAS_DATA))
                    }
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stop_card, parent, false)
        return StopViewHolder(view)
    }

    override fun onBindViewHolder(holder: StopViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class StopViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: CardView = view.findViewById(R.id.card)
        private val stopNameText: TextView = view.findViewById(R.id.stopName)
        private val offlineIndicator: TextView = view.findViewById(R.id.offlineIndicator)
        private val homeBadge: TextView = view.findViewById(R.id.homeBadge)
        private val workBadge: TextView = view.findViewById(R.id.workBadge)
        private val rblText: TextView = view.findViewById(R.id.rblNumber)
        private val disruptionBanner: View = view.findViewById(R.id.disruptionBanner)
        private val disruptionText: TextView = view.findViewById(R.id.disruptionText)
        private val dep1: DepartureRowView = view.findViewById(R.id.dep1)
        private val dep2: DepartureRowView = view.findViewById(R.id.dep2)
        private val dep3: DepartureRowView = view.findViewById(R.id.dep3)
        private val dep4: DepartureRowView = view.findViewById(R.id.dep4)
        private val noDataText: TextView = view.findViewById(R.id.noDataText)
        private val deleteBtn: MaterialButton = view.findViewById(R.id.deleteBtn)
        private val dragHandle: View = view.findViewById(R.id.dragHandle)

        private val depViews get() = listOf(dep1, dep2, dep3, dep4)

        fun bind(data: StopWithDepartures) {
            val stop = data.stop
            val departures = data.departures

            itemView.setOnClickListener { onEditStop(stop) }
            itemView.setOnLongClickListener {
                onToggleCommuteTag?.invoke(stop)
                true
            }

            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this@StopViewHolder)
                }
                false
            }

            // Stop label: custom label > stop name
            stopNameText.text = stop.customLabel?.takeIf { it.isNotBlank() } ?: stop.stopName

            // Commute Badges (Home & Work - independent flags, both or multiple can be active)
            homeBadge.visibility = if (stop.isHome) View.VISIBLE else View.GONE
            workBadge.visibility = if (stop.isWork) View.VISIBLE else View.GONE
            homeBadge.setOnClickListener { onToggleCommuteTag?.invoke(stop) }
            workBadge.setOnClickListener { onToggleCommuteTag?.invoke(stop) }

            // Offline cache indicator
            val isOffline = departures.any { it.isCached }
            if (isOffline && departures.isNotEmpty()) {
                offlineIndicator.visibility = View.VISIBLE
                val updatedTime = departures.map { it.cachedAtMillis }.maxOrNull() ?: 0L
                if (updatedTime > 0) {
                    offlineIndicator.text = "Offline · ${CountdownFormatter.formatUpdatedTime(updatedTime)}"
                } else {
                    offlineIndicator.text = "Offline"
                }
            } else {
                offlineIndicator.visibility = View.GONE
            }
            
            val details = mutableListOf<String>()
            if (!stop.platformLines.isNullOrBlank()) {
                details.add("Lines: ${stop.platformLines}")
            }
            details.add("Steig ${stop.direction ?: "?"} (RBL ${stop.rblNumber})")
            rblText.text = details.joinToString(" · ")

            // Service Disruptions / Alerts matching this stop or its lines
            val stopLines = (stop.platformLines?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()) +
                    listOfNotNull(stop.lineName) +
                    departures.map { it.lineName }
            val matchingDisruption = currentDisruptions.firstOrNull { disruption ->
                val matchesLine = disruption.relatedLines?.any { relLine ->
                    stopLines.any { it.equals(relLine, ignoreCase = true) }
                } == true
                val matchesStop = disruption.relatedStops?.contains(stop.rblNumber) == true
                matchesLine || matchesStop
            }
            if (matchingDisruption != null) {
                disruptionBanner.visibility = View.VISIBLE
                disruptionText.text = matchingDisruption.title ?: matchingDisruption.description ?: "Service alert on this line"
                disruptionBanner.setOnClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(itemView.context)
                        .setTitle(matchingDisruption.title ?: "Service Alert")
                        .setMessage(matchingDisruption.description ?: "Traffic notice from Wiener Linien")
                        .setPositiveButton("OK", null)
                        .show()
                }
            } else {
                disruptionBanner.visibility = View.GONE
            }

            // Handle display states
            when (data.state) {
                DisplayState.FETCHING -> {
                    depViews.forEach {
                        it.visibility = View.GONE
                        it.setOnClickListener(null)
                    }
                    noDataText.visibility = View.VISIBLE
                    noDataText.text = "Fetching departures…"
                }
                DisplayState.NO_DEPARTURES -> {
                    depViews.forEach {
                        it.visibility = View.GONE
                        it.setOnClickListener(null)
                    }
                    noDataText.visibility = View.VISIBLE
                    noDataText.text = "No departures for this stop"
                }
                DisplayState.LINE_NOT_RUNNING -> {
                    depViews.forEach {
                        it.visibility = View.GONE
                        it.setOnClickListener(null)
                    }
                    noDataText.visibility = View.VISIBLE
                    noDataText.text = "Line ${stop.lineName} not currently running"
                }
                DisplayState.HAS_DATA -> {
                    noDataText.visibility = View.GONE
                    depViews.forEachIndexed { index, view ->
                        if (index < departures.size) {
                            val dep = departures[index]
                            view.visibility = View.VISIBLE
                            view.bind(dep)
                            view.setOnClickListener {
                                onDepartureClick?.invoke(dep, stop)
                            }
                        } else {
                            view.visibility = View.GONE
                            view.setOnClickListener(null)
                        }
                    }
                }
            }

            deleteBtn.setOnClickListener { onDeleteStop(stop) }
        }
    }
}
