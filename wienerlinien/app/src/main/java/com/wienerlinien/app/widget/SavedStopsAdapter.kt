package com.wienerlinien.app.widget

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wienerlinien.app.R
import com.wienerlinien.app.model.SavedStop

class SavedStopsAdapter(
    private val onStopSelected: (SavedStop) -> Unit
) : ListAdapter<SavedStop, SavedStopsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_stop_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val stop = getItem(position)
        holder.bind(stop)
        holder.itemView.setOnClickListener { onStopSelected(stop) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.stopChipName)
        fun bind(stop: SavedStop) {
            name.text = stop.customLabel?.takeIf { it.isNotBlank() } ?: stop.stopName
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SavedStop>() {
        override fun areItemsTheSame(oldItem: SavedStop, newItem: SavedStop) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SavedStop, newItem: SavedStop) = oldItem == newItem
    }
}
