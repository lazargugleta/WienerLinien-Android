package com.wienerlinien.app.widget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wienerlinien.app.R

class SelectedStopsAdapter(
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<SelectedStopsAdapter.ViewHolder>() {

    private val items = mutableListOf<WidgetPrefs.StopConfig>()

    fun updateItems(newItems: List<WidgetPrefs.StopConfig>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_stop, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.customLabel.takeIf { it.isNotBlank() } ?: item.stopName
        holder.detail.text = "RBL ${item.rblNumber}"
        holder.removeBtn.setOnClickListener { onRemove(position) }
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.selectedStopName)
        val detail: TextView = view.findViewById(R.id.selectedStopDetail)
        val removeBtn: View = view.findViewById(R.id.removeStopBtn)
    }
}
