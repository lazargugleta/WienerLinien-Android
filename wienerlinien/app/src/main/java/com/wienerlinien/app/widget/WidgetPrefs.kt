package com.wienerlinien.app.widget

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Stores per-widget configuration in SharedPreferences.
 * Each widget can be configured independently for multiple stops.
 */
object WidgetPrefs {

    private const val PREFS_NAME = "wienerlinien_widget_prefs"
    private const val KEY_CONFIG_JSON = "config_json_"
    private val gson = Gson()

    data class WidgetConfig(
        val stops: List<StopConfig>,
        val maxDepartures: Int = 2,
        val groupByStation: Boolean? = true,
        val backgroundOpacityPercent: Int? = 100
    ) {
        val isGrouped: Boolean
            get() = groupByStation ?: true

        val opacity: Int
            get() = backgroundOpacityPercent ?: 100
    }

    data class StopConfig(
        val rblNumber: Int,
        val stopName: String,
        val lineName: String,       // empty = show all lines
        val customLabel: String,    // empty = use stop name
        val platformLines: String = ""
    )

    fun saveWidgetConfig(context: Context, widgetId: Int, config: WidgetConfig) {
        val json = gson.toJson(config)
        prefs(context).edit()
            .putString(KEY_CONFIG_JSON + widgetId, json)
            .apply()
    }

    fun getWidgetConfig(context: Context, widgetId: Int): WidgetConfig? {
        val json = prefs(context).getString(KEY_CONFIG_JSON + widgetId, null) ?: return null
        return try {
            gson.fromJson(json, WidgetConfig::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clearWidgetConfig(context: Context, widgetId: Int) {
        prefs(context).edit()
            .remove(KEY_CONFIG_JSON + widgetId)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
