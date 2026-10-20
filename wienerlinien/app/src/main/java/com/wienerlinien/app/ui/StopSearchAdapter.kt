package com.wienerlinien.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wienerlinien.app.R
import com.wienerlinien.app.util.StopSearchUtil

class StopSearchAdapter(
    private val onSelect: (StopSearchUtil.StopSearchResult) -> Unit
) : ListAdapter<StopSearchUtil.StopSearchResult, StopSearchAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stop_search, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val stopName: TextView = view.findViewById(R.id.stopName)
        private val platformBadge: TextView = view.findViewById(R.id.platformBadge)
        private val linesContainer: LinearLayout = view.findViewById(R.id.linesContainer)
        private val stopDetail: TextView = view.findViewById(R.id.stopDetail)

        fun bind(result: StopSearchUtil.StopSearchResult) {
            val context = itemView.context
            stopName.text = result.stopName

            // Steig and RBL platform indicator
            platformBadge.text = if (!result.platform.isNullOrBlank()) {
                "Steig ${result.platform} · RBL ${result.rblNumber}"
            } else {
                "RBL ${result.rblNumber}"
            }

            // Colored line badges
            val lineList = result.lines.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            linesContainer.removeAllViews()
            if (lineList.isNotEmpty()) {
                linesContainer.visibility = View.VISIBLE
                val displayCount = 6
                val displayLines = lineList.take(displayCount)
                for (line in displayLines) {
                    linesContainer.addView(createLineBadge(context, line))
                }
                if (lineList.size > displayCount) {
                    linesContainer.addView(createMoreBadge(context, "+${lineList.size - displayCount}"))
                }
            } else {
                linesContainer.visibility = View.GONE
            }

            // Municipality context when outside Vienna or when extra context is useful
            if (result.municipality.isNotBlank() && !result.municipality.equals("Wien", ignoreCase = true)) {
                stopDetail.text = result.municipality
                stopDetail.visibility = View.VISIBLE
            } else {
                stopDetail.visibility = View.GONE
            }

            itemView.setOnClickListener { onSelect(result) }
        }

        private fun createLineBadge(context: Context, lineName: String): TextView {
            val d = context.resources.displayMetrics.density
            return TextView(context).apply {
                text = lineName
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.white))
                gravity = Gravity.CENTER
                includeFontPadding = false
                val bg = GradientDrawable().apply {
                    cornerRadius = 4f * d
                    setColor(VehicleBadgeHelper.getLineColor(lineName))
                }
                background = bg
                val padH = (6f * d).toInt()
                val padV = (2f * d).toInt()
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (4f * d).toInt()
                }
                layoutParams = lp
            }
        }

        private fun createMoreBadge(context: Context, text: String): TextView {
            val d = context.resources.displayMetrics.density
            return TextView(context).apply {
                this.text = text
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                gravity = Gravity.CENTER
                includeFontPadding = false
                val bg = GradientDrawable().apply {
                    cornerRadius = 4f * d
                    setColor(ContextCompat.getColor(context, R.color.separator))
                }
                background = bg
                val padH = (5f * d).toInt()
                val padV = (2f * d).toInt()
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<StopSearchUtil.StopSearchResult>() {
        override fun areItemsTheSame(
            old: StopSearchUtil.StopSearchResult,
            new: StopSearchUtil.StopSearchResult
        ) = old.rblNumber == new.rblNumber

        override fun areContentsTheSame(
            old: StopSearchUtil.StopSearchResult,
            new: StopSearchUtil.StopSearchResult
        ) = old == new
    }
}
