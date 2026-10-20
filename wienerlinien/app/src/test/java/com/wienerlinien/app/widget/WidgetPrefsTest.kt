package com.wienerlinien.app.widget

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class WidgetPrefsTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val prefStorage = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()

        prefStorage.clear()

        whenever(mockContext.getSharedPreferences(eq("wienerlinien_widget_prefs"), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)

        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockEditor.putString(any(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<String>(1)
            prefStorage[key] = value
            mockEditor
        }
        whenever(mockEditor.remove(any())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            prefStorage.remove(key)
            mockEditor
        }

        whenever(mockPrefs.getString(any(), anyOrNull())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val default = invocation.getArgument<String?>(1)
            prefStorage[key] ?: default
        }
    }

    @Test
    fun widgetConfig_serializationRoundTrip_matchesExactly() {
        val gson = Gson()
        val original = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(
                    rblNumber = 4116,
                    stopName = "Stephansplatz",
                    lineName = "U1",
                    customLabel = "Home",
                    platformLines = "U1, U3"
                ),
                WidgetPrefs.StopConfig(
                    rblNumber = 4200,
                    stopName = "Karlsplatz",
                    lineName = "",
                    customLabel = "Work",
                    platformLines = "U1, U2, U4"
                )
            ),
            maxDepartures = 3
        )

        val json = gson.toJson(original)
        val deserialized = gson.fromJson(json, WidgetPrefs.WidgetConfig::class.java)

        assertThat(deserialized).isEqualTo(original)
        assertThat(deserialized.stops).hasSize(2)
        assertThat(deserialized.stops[0].rblNumber).isEqualTo(4116)
        assertThat(deserialized.stops[0].stopName).isEqualTo("Stephansplatz")
        assertThat(deserialized.stops[0].lineName).isEqualTo("U1")
        assertThat(deserialized.stops[0].customLabel).isEqualTo("Home")
        assertThat(deserialized.stops[1].rblNumber).isEqualTo(4200)
        assertThat(deserialized.maxDepartures).isEqualTo(3)
    }

    @Test
    fun saveAndGetWidgetConfig_storesAndRetrievesCorrectly() {
        val widgetId = 101
        val config = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(
                    rblNumber = 4920,
                    stopName = "Schottentor",
                    lineName = "D",
                    customLabel = "University"
                )
            ),
            maxDepartures = 2
        )

        WidgetPrefs.saveWidgetConfig(mockContext, widgetId, config)

        val retrieved = WidgetPrefs.getWidgetConfig(mockContext, widgetId)
        assertThat(retrieved).isNotNull()
        assertThat(retrieved).isEqualTo(config)
    }

    @Test
    fun getWidgetConfig_nonExistentWidget_returnsNull() {
        val retrieved = WidgetPrefs.getWidgetConfig(mockContext, 999)
        assertThat(retrieved).isNull()
    }

    @Test
    fun getWidgetConfig_malformedJson_returnsNull() {
        prefStorage["config_json_555"] = "{not valid json}"

        val retrieved = WidgetPrefs.getWidgetConfig(mockContext, 555)
        assertThat(retrieved).isNull()
    }

    @Test
    fun clearWidgetConfig_removesWidgetConfig() {
        val widgetId = 202
        val config = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(4116, "Stephansplatz", "U1", "")
            )
        )

        WidgetPrefs.saveWidgetConfig(mockContext, widgetId, config)
        assertThat(WidgetPrefs.getWidgetConfig(mockContext, widgetId)).isNotNull()

        WidgetPrefs.clearWidgetConfig(mockContext, widgetId)
        assertThat(WidgetPrefs.getWidgetConfig(mockContext, widgetId)).isNull()
    }

    @Test
    fun widgetConfig_legacyJsonWithoutGroupByStation_defaultsToGrouped() {
        val legacyJson = """{"stops":[{"rblNumber":4116,"stopName":"Stephansplatz","lineName":"","customLabel":"","platformLines":""}],"maxDepartures":2}"""
        val deserialized = Gson().fromJson(legacyJson, WidgetPrefs.WidgetConfig::class.java)
        assertThat(deserialized.isGrouped).isTrue()
    }

    @Test
    fun widgetConfig_groupByStationFalse_persistsAndRetrieves() {
        val config = WidgetPrefs.WidgetConfig(
            stops = listOf(WidgetPrefs.StopConfig(4116, "Stephansplatz", "", "")),
            maxDepartures = 2,
            groupByStation = false
        )
        val json = Gson().toJson(config)
        val deserialized = Gson().fromJson(json, WidgetPrefs.WidgetConfig::class.java)
        assertThat(deserialized.isGrouped).isFalse()
    }
}
