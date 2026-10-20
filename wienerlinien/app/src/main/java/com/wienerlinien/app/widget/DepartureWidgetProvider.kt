package com.wienerlinien.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.wienerlinien.app.R
import com.wienerlinien.app.model.AppDatabase
import com.wienerlinien.app.model.DepartureItem
import com.wienerlinien.app.model.DepartureRepository
import com.wienerlinien.app.ui.CountdownFormatter
import com.wienerlinien.app.ui.MainActivity
import com.wienerlinien.app.ui.VehicleBadgeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Modern, resilient App Widget provider for home screen next departures.
 *
 * Key Architectural Highlights:
 * - Eliminates background service crashes: executes async operations via goAsync() & CoroutineWorker.
 * - Responsive sizing: supports 2x2, 3x2, and 4x2 dimensions without vertical clipping.
 * - Offline cache resilience: displays cached departures with an "Offline · Updated HH:mm" banner.
 * - Real-time transit indicators & official Wiener Linien line colors.
 * - Manual refresh button with immediate visual feedback.
 */
class DepartureWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.wienerlinien.app.WIDGET_REFRESH"
        const val EXTRA_WIDGET_ID = "widget_id"
        private val lastRefreshTimes = ConcurrentHashMap<Int, Long>()

        /**
         * Broadcast or direct trigger to refresh all active widgets.
         * Safe to call from foreground activity or background workers.
         */
        fun updateAllWidgets(context: Context, forceRefresh: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, DepartureWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    updateWidgets(context, manager, ids, forceRefresh)
                }
            }
        }

        /**
         * Updates a list of widget IDs.
         */
        suspend fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetIds: IntArray,
            forceRefresh: Boolean = false
        ) {
            val db = AppDatabase.getDatabase(context)
            val repo = DepartureRepository(db)

            for (widgetId in widgetIds) {
                updateSingleWidget(context, appWidgetManager, widgetId, repo, forceRefresh)
            }
        }

        /**
         * Updates an individual widget instance with size awareness and offline fallback.
         */
        suspend fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            repo: DepartureRepository,
            forceRefresh: Boolean = false
        ) = withContext(Dispatchers.IO) {
            try {
                val config = WidgetPrefs.getWidgetConfig(context, widgetId)
                if (config == null || config.stops.isEmpty()) {
                    val views = buildNoConfigView(context, widgetId)
                    appWidgetManager.updateAppWidget(widgetId, views)
                    return@withContext
                }

                // Batch fetch departures for all configured stops in this widget
                val rbls = config.stops.map { it.rblNumber }.distinct()
                val result = repo.fetchDepartures(rbls, forceRefresh = forceRefresh)

                val options = appWidgetManager.getAppWidgetOptions(widgetId)
                val maxVisibleRows = getMaxVisibleRows(options)

                if (result.isSuccess) {
                    val allItems = result.getOrThrow()
                    val isOffline = allItems.any { it.isCached }

                    val finalItems = processWidgetDepartures(config, allItems, maxVisibleRows)
                    val statusMessage = getStatusMessage(isOffline, allItems, finalItems)

                    val views = buildDepartureView(
                        context = context,
                        widgetId = widgetId,
                        config = config,
                        departures = finalItems,
                        statusMessage = statusMessage,
                        isOffline = isOffline
                    )
                    appWidgetManager.updateAppWidget(widgetId, views)
                } else {
                    // Fatal failure without cache
                    val errorViews = buildErrorView(context, widgetId, "Offline · Tap to retry")
                    appWidgetManager.updateAppWidget(widgetId, errorViews)
                }
            } catch (e: Exception) {
                Log.e("DepartureWidgetProvider", "updateSingleWidget error: ${e.message}", e)
                val errorViews = buildErrorView(context, widgetId, "Update failed · Tap to retry")
                appWidgetManager.updateAppWidget(widgetId, errorViews)
            }
        }

        /**
         * Filters and orders departures according to widget configuration:
         * - If config.isGrouped is true: preserved in station order, each station sorted by countdown.
         * - If config.isGrouped is false: merged across all stations and sorted purely by countdown time.
         */
        fun processWidgetDepartures(
            config: WidgetPrefs.WidgetConfig,
            allItems: List<DepartureItem>,
            maxVisibleRows: Int
        ): List<DepartureItem> {
            val targetCount = minOf(config.maxDepartures, maxVisibleRows)
            val grouped = allItems.groupBy { it.rbl }
            val finalItems = mutableListOf<DepartureItem>()

            if (config.isGrouped) {
                config.stops.forEach { stop ->
                    val stopDeps = grouped[stop.rblNumber] ?: emptyList()
                    val filtered = if (stop.lineName.isNotBlank()) {
                        stopDeps.filter { it.lineName.equals(stop.lineName, ignoreCase = true) }
                    } else {
                        stopDeps
                    }
                    finalItems.addAll(filtered.sortedBy { it.countdown }.take(targetCount))
                }
            } else {
                config.stops.forEach { stop ->
                    val stopDeps = grouped[stop.rblNumber] ?: emptyList()
                    val filtered = if (stop.lineName.isNotBlank()) {
                        stopDeps.filter { it.lineName.equals(stop.lineName, ignoreCase = true) }
                    } else {
                        stopDeps
                    }
                    finalItems.addAll(filtered.take(targetCount))
                }
                finalItems.sortBy { it.countdown }
            }

            return finalItems.take(maxVisibleRows)
        }

        /**
         * Computes status message or offline banner for widget header.
         * Suppresses the "Offline" banner if cached departures are recent (<3 min).
         */
        fun getStatusMessage(
            isOffline: Boolean,
            allItems: List<DepartureItem>,
            finalItems: List<DepartureItem>,
            currentTimeMillis: Long = System.currentTimeMillis()
        ): String? {
            return when {
                isOffline -> {
                    val cachedTime = allItems.map { it.cachedAtMillis }.maxOrNull() ?: currentTimeMillis
                    val ageMinutes = (currentTimeMillis - cachedTime) / 60_000L
                    when {
                        finalItems.isEmpty() -> "Departures expired · Tap to refresh"
                        ageMinutes < 3 -> null // Hide offline banner if data is fresh (< 3 min)
                        else -> "Offline · Updated ${CountdownFormatter.formatUpdatedTime(cachedTime)}"
                    }
                }
                allItems.isEmpty() -> "No departures found"
                finalItems.isEmpty() -> "Filtered lines not running"
                else -> null
            }
        }

        /**
         * Formats destination direction display, prefixing stop title when multiple distinct stops are configured.
         * Falls back gracefully to configured stop names when offline.
         */
        fun formatDisplayDirection(config: WidgetPrefs.WidgetConfig, dep: DepartureItem): String {
            val distinctStops = config.stops.map {
                it.customLabel.takeIf { c -> c.isNotBlank() } ?: it.stopName
            }.distinct()

            if (distinctStops.size <= 1) return dep.towards

            val stopConfig = config.stops.find { it.rblNumber == dep.rbl }
            val title = dep.stopTitle.takeIf { it.isNotBlank() && it != "Unknown Stop" }
                ?: stopConfig?.customLabel?.takeIf { it.isNotBlank() }
                ?: stopConfig?.stopName
                ?: ""

            return if (title.isNotBlank()) "[$title] ${dep.towards}" else dep.towards
        }

        /**
         * Calculates maximum row count to avoid vertical clipping in 2x2, 3x2, and 4x2 widgets.
         */
        fun getMaxVisibleRows(options: Bundle?): Int {
            if (options == null) return 4
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            return when {
                minHeight <= 0 -> 4
                minHeight < 110 -> 2  // Compact / 2x2
                minHeight < 165 -> 3  // Medium-short / 3x2 or 4x2
                minHeight < 225 -> 4  // Standard
                minHeight < 290 -> 6  // Large
                else -> 8             // Extra large
            }
        }

        fun buildLoadingView(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_departure)
            views.setInt(R.id.widgetRefreshBtn, "setColorFilter", 0x88FFFFFF.toInt())
            views.setTextViewText(R.id.widgetStatus, "Updating…")
            views.setTextColor(R.id.widgetStatus, Color.parseColor("#99FFFFFF"))
            views.setViewVisibility(R.id.widgetStatus, View.VISIBLE)
            setRefreshAction(context, views, widgetId)
            setOpenAppAction(context, views)
            return views
        }

        fun buildErrorView(context: Context, widgetId: Int, message: String): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_departure)
            views.setInt(R.id.widgetRefreshBtn, "setColorFilter", 0xAAFFFFFF.toInt())
            views.setTextViewText(R.id.widgetStatus, message)
            views.setTextColor(R.id.widgetStatus, Color.parseColor("#FF9F0A"))
            views.setViewVisibility(R.id.widgetStatus, View.VISIBLE)
            hideAllRows(views)
            setRefreshAction(context, views, widgetId)
            setOpenAppAction(context, views)
            return views
        }

        fun buildNoConfigView(context: Context, widgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_departure)
            views.setInt(R.id.widgetRefreshBtn, "setColorFilter", 0xAAFFFFFF.toInt())
            views.setTextViewText(R.id.widgetStatus, "Tap to configure stops")
            views.setTextColor(R.id.widgetStatus, Color.parseColor("#99FFFFFF"))
            views.setViewVisibility(R.id.widgetStatus, View.VISIBLE)
            hideAllRows(views)

            val intent = Intent(context, WidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            val pi = PendingIntent.getActivity(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetContainer, pi)
            return views
        }

        fun buildDepartureView(
            context: Context,
            widgetId: Int,
            config: WidgetPrefs.WidgetConfig,
            departures: List<DepartureItem>,
            statusMessage: String? = null,
            isOffline: Boolean = false
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_departure)
            views.setInt(R.id.widgetRefreshBtn, "setColorFilter", 0xFFFFFFFF.toInt())

            if (config.opacity < 100) {
                val alpha = when (config.opacity) {
                    20 -> 0x33
                    60 -> 0x99
                    else -> 0xE6
                }
                views.setInt(R.id.widgetContainer, "setBackgroundColor", Color.argb(alpha, 28, 28, 30))
            }

            // Header title: if all stops share the same name/label, show it, otherwise "Wiener Linien"
            val distinctLabels = config.stops.map {
                it.customLabel.takeIf { c -> c.isNotBlank() } ?: it.stopName
            }.distinct()
            val label = if (distinctLabels.size == 1) {
                distinctLabels[0]
            } else {
                "Wiener Linien"
            }
            views.setTextViewText(R.id.widgetTitle, label)

            // Current time / updated time
            val currentTime = CountdownFormatter.formatUpdatedTime(System.currentTimeMillis())
            views.setTextViewText(R.id.widgetUpdatedTime, currentTime)

            // Status message / offline warning banner
            if (statusMessage != null) {
                views.setTextViewText(R.id.widgetStatus, statusMessage)
                views.setViewVisibility(R.id.widgetStatus, View.VISIBLE)
                if (isOffline) {
                    views.setTextColor(R.id.widgetStatus, Color.parseColor("#FF9F0A")) // Orange
                } else {
                    views.setTextColor(R.id.widgetStatus, Color.parseColor("#99FFFFFF"))
                }
            } else {
                views.setViewVisibility(R.id.widgetStatus, View.GONE)
            }

            // Row definitions (rowId, lineId, dirId, timeId, rtId)
            val rows = listOf(
                WidgetRow(R.id.widgetDep1Row, R.id.widgetDep1Line, R.id.widgetDep1Direction, R.id.widgetDep1Time, R.id.widgetDep1Realtime),
                WidgetRow(R.id.widgetDep2Row, R.id.widgetDep2Line, R.id.widgetDep2Direction, R.id.widgetDep2Time, R.id.widgetDep2Realtime),
                WidgetRow(R.id.widgetDep3Row, R.id.widgetDep3Line, R.id.widgetDep3Direction, R.id.widgetDep3Time, R.id.widgetDep3Realtime),
                WidgetRow(R.id.widgetDep4Row, R.id.widgetDep4Line, R.id.widgetDep4Direction, R.id.widgetDep4Time, R.id.widgetDep4Realtime),
                WidgetRow(R.id.widgetDep5Row, R.id.widgetDep5Line, R.id.widgetDep5Direction, R.id.widgetDep5Time, R.id.widgetDep5Realtime),
                WidgetRow(R.id.widgetDep6Row, R.id.widgetDep6Line, R.id.widgetDep6Direction, R.id.widgetDep6Time, R.id.widgetDep6Realtime),
                WidgetRow(R.id.widgetDep7Row, R.id.widgetDep7Line, R.id.widgetDep7Direction, R.id.widgetDep7Time, R.id.widgetDep7Realtime),
                WidgetRow(R.id.widgetDep8Row, R.id.widgetDep8Line, R.id.widgetDep8Direction, R.id.widgetDep8Time, R.id.widgetDep8Realtime)
            )

            rows.forEachIndexed { index, row ->
                if (index < departures.size) {
                    val dep = departures[index]
                    views.setViewVisibility(row.rowId, View.VISIBLE)

                    // Line chip text & official Wiener Linien line color
                    views.setTextViewText(row.lineId, dep.lineName)
                    val lineColor = VehicleBadgeHelper.getLineColor(dep.lineName, dep.lineType)
                    views.setInt(row.lineId, "setBackgroundColor", lineColor)
                    views.setTextColor(row.lineId, Color.WHITE)

                    // Multi-stop stop title prefix (or destination only when single stop)
                    val displayDirection = formatDisplayDirection(config, dep)
                    views.setTextViewText(row.dirId, displayDirection)

                    // Countdown formatting
                    views.setTextViewText(row.timeId, CountdownFormatter.formatCountdown(dep.countdown))

                    // Real-time dot
                    views.setViewVisibility(
                        row.rtId,
                        if (dep.isRealtime) View.VISIBLE else View.INVISIBLE
                    )
                } else {
                    views.setViewVisibility(row.rowId, View.GONE)
                }
            }

            setRefreshAction(context, views, widgetId)
            setOpenAppAction(context, views)

            return views
        }

        private fun hideAllRows(views: RemoteViews) {
            val rows = listOf(
                R.id.widgetDep1Row, R.id.widgetDep2Row, R.id.widgetDep3Row, R.id.widgetDep4Row,
                R.id.widgetDep5Row, R.id.widgetDep6Row, R.id.widgetDep7Row, R.id.widgetDep8Row
            )
            rows.forEach { views.setViewVisibility(it, View.GONE) }
        }

        private fun setRefreshAction(context: Context, views: RemoteViews, widgetId: Int) {
            val intent = Intent(context, DepartureWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(EXTRA_WIDGET_ID, widgetId)
                data = android.net.Uri.parse("wienerlinien://widget/refresh/$widgetId")
            }
            val pi = PendingIntent.getBroadcast(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRefreshBtn, pi)
            views.setOnClickPendingIntent(R.id.widgetRefreshArea, pi)
        }

        private fun setOpenAppAction(context: Context, views: RemoteViews) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTitle, pi)
            val rows = listOf(
                R.id.widgetDep1Row, R.id.widgetDep2Row, R.id.widgetDep3Row, R.id.widgetDep4Row,
                R.id.widgetDep5Row, R.id.widgetDep6Row, R.id.widgetDep7Row, R.id.widgetDep8Row
            )
            rows.forEach { views.setOnClickPendingIntent(it, pi) }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgets(context.applicationContext, appWidgetManager, appWidgetIds, forceRefresh = true)
            } catch (e: Throwable) {
                Log.e("DepartureWidgetProvider", "onUpdate error: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val now = System.currentTimeMillis()
                val lastTime = lastRefreshTimes[widgetId] ?: 0L
                if (now - lastTime < 1500L) {
                    // Debounce rapid repeated taps (cooldown 1.5s)
                    return
                }
                lastRefreshTimes[widgetId] = now

                val manager = AppWidgetManager.getInstance(context)
                // Smooth partial update: keep existing title & departures on screen, show "Updating…"
                val partialViews = RemoteViews(context.packageName, R.layout.widget_departure).apply {
                    setTextViewText(R.id.widgetStatus, "Updating…")
                    setTextColor(R.id.widgetStatus, Color.parseColor("#99FFFFFF"))
                    setViewVisibility(R.id.widgetStatus, View.VISIBLE)
                    setInt(R.id.widgetRefreshBtn, "setColorFilter", 0x88FFFFFF.toInt())
                }
                manager.partiallyUpdateAppWidget(widgetId, partialViews)

                // Execute the update directly via goAsync() with high receiver priority
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AppDatabase.getDatabase(context.applicationContext)
                        val repo = DepartureRepository(db)
                        updateSingleWidget(context.applicationContext, manager, widgetId, repo, forceRefresh = true)
                    } catch (e: Throwable) {
                        Log.e("DepartureWidgetProvider", "Widget refresh error: ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context.applicationContext)
                val repo = DepartureRepository(db)
                updateSingleWidget(context.applicationContext, appWidgetManager, appWidgetId, repo, forceRefresh = false)
            } catch (e: Throwable) {
                Log.e("DepartureWidgetProvider", "onOptionsChanged error: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            WidgetPrefs.clearWidgetConfig(context, widgetId)
        }
    }

    private data class WidgetRow(
        val rowId: Int,
        val lineId: Int,
        val dirId: Int,
        val timeId: Int,
        val rtId: Int
    )
}
