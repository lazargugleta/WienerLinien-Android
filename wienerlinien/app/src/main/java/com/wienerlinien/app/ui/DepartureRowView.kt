package com.wienerlinien.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wienerlinien.app.R
import com.wienerlinien.app.model.DepartureItem

class DepartureRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val vehicleIcon: ImageView
    private val lineChip: TextView
    private val towardsText: TextView
    private val timeText: TextView
    private val liveBadge: TextView
    private val scheduledBadge: TextView
    private val realtimeDot: View
    private val countdownText: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_departure_row, this, true)
        vehicleIcon = findViewById(R.id.vehicleIcon)
        lineChip = findViewById(R.id.lineChip)
        towardsText = findViewById(R.id.towardsText)
        timeText = findViewById(R.id.timeText)
        liveBadge = findViewById(R.id.liveBadge)
        scheduledBadge = findViewById(R.id.scheduledBadge)
        realtimeDot = findViewById(R.id.realtimeDot)
        countdownText = findViewById(R.id.countdownText)
        orientation = HORIZONTAL

        val outValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        setBackgroundResource(outValue.resourceId)
    }

    fun bind(item: DepartureItem) {
        // Vehicle type icon
        vehicleIcon.setImageResource(VehicleBadgeHelper.getVehicleIcon(item.lineType, item.lineName))

        // Line chip with official line color
        lineChip.text = item.lineName
        val chipBg = lineChip.background?.mutate() as? GradientDrawable
        chipBg?.setColor(VehicleBadgeHelper.getLineColor(item.lineName, item.lineType))

        // Destination
        towardsText.text = item.towards

        // Formatted clock time (e.g. "14:32")
        timeText.text = CountdownFormatter.formatClockTime(
            timeReal = item.departureTimeReal,
            timePlanned = item.departureTimePlanned,
            countdown = item.countdown
        )

        // Real-time LIVE indicator vs scheduled indicator
        if (item.isRealtime) {
            liveBadge.visibility = View.VISIBLE
            scheduledBadge.visibility = View.GONE
            realtimeDot.visibility = View.VISIBLE
        } else {
            liveBadge.visibility = View.GONE
            scheduledBadge.visibility = View.VISIBLE
            realtimeDot.visibility = View.INVISIBLE
        }

        // Countdown minutes
        countdownText.text = CountdownFormatter.formatCountdown(item.countdown)
    }
}
