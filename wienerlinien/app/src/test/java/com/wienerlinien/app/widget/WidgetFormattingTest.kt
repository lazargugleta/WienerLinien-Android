package com.wienerlinien.app.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import com.wienerlinien.app.model.DepartureItem
import com.wienerlinien.app.ui.CountdownFormatter
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WidgetFormattingTest {

    @Test
    fun getMaxVisibleRows_nullOptions_returnsDefaultFourRows() {
        val rows = DepartureWidgetProvider.getMaxVisibleRows(null)
        assertThat(rows).isEqualTo(4)
    }

    @Test
    fun getMaxVisibleRows_zeroHeight_returnsDefaultFourRows() {
        val mockBundle = mock<Bundle>()
        whenever(mockBundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)).thenReturn(0)

        val rows = DepartureWidgetProvider.getMaxVisibleRows(mockBundle)
        assertThat(rows).isEqualTo(4)
    }

    @Test
    fun getMaxVisibleRows_compactHeight_returnsTwoRows() {
        val mockBundle = mock<Bundle>()
        whenever(mockBundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)).thenReturn(90)

        val rows = DepartureWidgetProvider.getMaxVisibleRows(mockBundle)
        assertThat(rows).isEqualTo(2)
    }

    @Test
    fun getMaxVisibleRows_mediumShortHeight_returnsThreeRows() {
        val mockBundle = mock<Bundle>()
        whenever(mockBundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)).thenReturn(140)

        val rows = DepartureWidgetProvider.getMaxVisibleRows(mockBundle)
        assertThat(rows).isEqualTo(3)
    }

    @Test
    fun getMaxVisibleRows_standardHeight_returnsFourRows() {
        val mockBundle = mock<Bundle>()
        whenever(mockBundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)).thenReturn(190)

        val rows = DepartureWidgetProvider.getMaxVisibleRows(mockBundle)
        assertThat(rows).isEqualTo(4)
    }

    @Test
    fun getMaxVisibleRows_largeHeight_returnsSixRows() {
        val mockBundle = mock<Bundle>()
        whenever(mockBundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)).thenReturn(260)

        val rows = DepartureWidgetProvider.getMaxVisibleRows(mockBundle)
        assertThat(rows).isEqualTo(6)
    }

    @Test
    fun getMaxVisibleRows_extraLargeHeight_returnsEightRows() {
        val mockBundle = mock<Bundle>()
        whenever(mockBundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)).thenReturn(350)

        val rows = DepartureWidgetProvider.getMaxVisibleRows(mockBundle)
        assertThat(rows).isEqualTo(8)
    }

    @Test
    fun getStatusMessage_cachedDepartures_formatsWithTimestamp() {
        val cachedTime = 1700000000000L
        val formattedTime = CountdownFormatter.formatUpdatedTime(cachedTime)
        val expectedMessage = "Offline · Updated $formattedTime"

        val departures = listOf(
            DepartureItem(
                lineName = "U1",
                towards = "Oberlaa",
                lineType = "ptMetro",
                countdown = 3,
                isRealtime = false,
                isCached = true,
                cachedAtMillis = cachedTime,
                rbl = 4116
            )
        )

        val statusMessage = DepartureWidgetProvider.getStatusMessage(
            isOffline = true,
            allItems = departures,
            finalItems = departures
        )

        assertThat(statusMessage).isEqualTo(expectedMessage)
    }

    @Test
    fun getStatusMessage_veryRecentCacheUnderTwoMinutes_returnsNull() {
        val now = 1700000000000L
        val departures = listOf(
            DepartureItem(
                lineName = "U1",
                towards = "Oberlaa",
                lineType = "ptMetro",
                countdown = 3,
                isRealtime = false,
                isCached = true,
                cachedAtMillis = now - 30_000L, // 30 seconds ago
                rbl = 4116
            )
        )

        val statusMessage = DepartureWidgetProvider.getStatusMessage(
            isOffline = true,
            allItems = departures,
            finalItems = departures,
            currentTimeMillis = now
        )

        assertThat(statusMessage).isNull()
    }

    @Test
    fun getStatusMessage_liveDepartures_returnsNull() {
        val departures = listOf(
            DepartureItem(
                lineName = "U1",
                towards = "Oberlaa",
                lineType = "ptMetro",
                countdown = 3,
                isRealtime = true,
                isCached = false,
                cachedAtMillis = System.currentTimeMillis(),
                rbl = 4116
            )
        )

        val statusMessage = DepartureWidgetProvider.getStatusMessage(
            isOffline = false,
            allItems = departures,
            finalItems = departures
        )

        assertThat(statusMessage).isNull()
    }

    @Test
    fun getStatusMessage_noDeparturesFound_returnsNoDeparturesFound() {
        val statusMessage = DepartureWidgetProvider.getStatusMessage(
            isOffline = false,
            allItems = emptyList(),
            finalItems = emptyList()
        )

        assertThat(statusMessage).isEqualTo("No departures found")
    }

    @Test
    fun getStatusMessage_filteredLinesNotRunning_returnsFilteredLinesNotRunning() {
        val allItems = listOf(
            DepartureItem(
                lineName = "U1",
                towards = "Oberlaa",
                lineType = "ptMetro",
                countdown = 3,
                rbl = 4116
            )
        )

        val statusMessage = DepartureWidgetProvider.getStatusMessage(
            isOffline = false,
            allItems = allItems,
            finalItems = emptyList()
        )

        assertThat(statusMessage).isEqualTo("Filtered lines not running")
    }

    @Test
    fun formatDisplayDirection_multiDistinctStops_prefixesWithStopTitle() {
        val dep = DepartureItem(
            lineName = "U1",
            towards = "Oberlaa",
            lineType = "ptMetro",
            countdown = 2,
            isRealtime = true,
            stopTitle = "Stephansplatz",
            rbl = 4116
        )

        val configMulti = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(4116, "Stephansplatz", "", ""),
                WidgetPrefs.StopConfig(4200, "Karlsplatz", "", "")
            )
        )

        val display = DepartureWidgetProvider.formatDisplayDirection(configMulti, dep)
        assertThat(display).isEqualTo("[Stephansplatz] Oberlaa")
    }

    @Test
    fun formatDisplayDirection_singleStop_doesNotPrefixStopTitle() {
        val dep = DepartureItem(
            lineName = "U1",
            towards = "Oberlaa",
            lineType = "ptMetro",
            countdown = 2,
            isRealtime = true,
            stopTitle = "Stephansplatz",
            rbl = 4116
        )

        val configSingle = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(4116, "Stephansplatz", "", "")
            )
        )

        val display = DepartureWidgetProvider.formatDisplayDirection(configSingle, dep)
        assertThat(display).isEqualTo("Oberlaa")
    }

    @Test
    fun formatDisplayDirection_multiPlatformsSameStop_doesNotPrefixStopTitle() {
        // When user configures both U1 and U3 platforms at Stephansplatz, they share the same stopName
        val dep = DepartureItem(
            lineName = "U1",
            towards = "Oberlaa",
            lineType = "ptMetro",
            countdown = 2,
            stopTitle = "Stephansplatz",
            rbl = 4116
        )

        val configSameStop = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(4116, "Stephansplatz", "U1", ""),
                WidgetPrefs.StopConfig(4920, "Stephansplatz", "U3", "")
            )
        )

        val display = DepartureWidgetProvider.formatDisplayDirection(configSameStop, dep)
        // Redundant prefix omitted because widget header already displays "Stephansplatz"
        assertThat(display).isEqualTo("Oberlaa")
    }

    @Test
    fun formatDisplayDirection_offlineEmptyStopTitle_fallsBackToStopConfigName() {
        // When offline, cached departures have stopTitle = ""
        val dep = DepartureItem(
            lineName = "U1",
            towards = "Oberlaa",
            lineType = "ptMetro",
            countdown = 5,
            isCached = true,
            stopTitle = "",
            rbl = 4116
        )

        val configMulti = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(4116, "Stephansplatz", "", ""),
                WidgetPrefs.StopConfig(4200, "Karlsplatz", "", "")
            )
        )

        val display = DepartureWidgetProvider.formatDisplayDirection(configMulti, dep)
        assertThat(display).isEqualTo("[Stephansplatz] Oberlaa")
    }

    @Test
    fun formatDisplayDirection_customLabelTakesPrecedenceOverStopName() {
        val dep = DepartureItem(
            lineName = "U1",
            towards = "Oberlaa",
            lineType = "ptMetro",
            countdown = 2,
            stopTitle = "",
            rbl = 4116
        )

        val configMulti = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(4116, "Stephansplatz", "", "Home"),
                WidgetPrefs.StopConfig(4200, "Karlsplatz", "", "Work")
            )
        )

        val display = DepartureWidgetProvider.formatDisplayDirection(configMulti, dep)
        assertThat(display).isEqualTo("[Home] Oberlaa")
    }

    @Test
    fun processWidgetDepartures_groupedByStation_keepsStationOrder() {
        val stop1Deps = listOf(
            DepartureItem("1", "Stefan-Fadinger-Platz", "ptTram", 5, rbl = 1876),
            DepartureItem("1", "Stefan-Fadinger-Platz", "ptTram", 15, rbl = 1876)
        )
        val stop2Deps = listOf(
            DepartureItem("65A", "Reumannplatz U", "ptBusCity", 2, rbl = 1398),
            DepartureItem("65A", "Reumannplatz U", "ptBusCity", 12, rbl = 1398)
        )
        val allItems = stop1Deps + stop2Deps

        val configGrouped = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(1876, "Knöllgasse/Troststraße", "", ""),
                WidgetPrefs.StopConfig(1398, "Starhemberg-Kaserne", "", "")
            ),
            maxDepartures = 2,
            groupByStation = true
        )

        val processed = DepartureWidgetProvider.processWidgetDepartures(configGrouped, allItems, maxVisibleRows = 4)

        assertThat(processed).hasSize(4)
        // First 2 should be from Stop 1 (Knöllgasse RBL 1876), even though Stop 2 has an earlier departure (2 min)
        assertThat(processed[0].rbl).isEqualTo(1876)
        assertThat(processed[0].countdown).isEqualTo(5)
        assertThat(processed[1].rbl).isEqualTo(1876)
        assertThat(processed[1].countdown).isEqualTo(15)
        // Next 2 should be from Stop 2 (Starhemberg RBL 1398)
        assertThat(processed[2].rbl).isEqualTo(1398)
        assertThat(processed[2].countdown).isEqualTo(2)
        assertThat(processed[3].rbl).isEqualTo(1398)
        assertThat(processed[3].countdown).isEqualTo(12)
    }

    @Test
    fun processWidgetDepartures_sortedByTime_sortsChronologicallyAcrossStations() {
        val stop1Deps = listOf(
            DepartureItem("1", "Stefan-Fadinger-Platz", "ptTram", 5, rbl = 1876),
            DepartureItem("1", "Stefan-Fadinger-Platz", "ptTram", 15, rbl = 1876)
        )
        val stop2Deps = listOf(
            DepartureItem("65A", "Reumannplatz U", "ptBusCity", 2, rbl = 1398),
            DepartureItem("65A", "Reumannplatz U", "ptBusCity", 12, rbl = 1398)
        )
        val allItems = stop1Deps + stop2Deps

        val configTimeline = WidgetPrefs.WidgetConfig(
            stops = listOf(
                WidgetPrefs.StopConfig(1876, "Knöllgasse/Troststraße", "", ""),
                WidgetPrefs.StopConfig(1398, "Starhemberg-Kaserne", "", "")
            ),
            maxDepartures = 2,
            groupByStation = false
        )

        val processed = DepartureWidgetProvider.processWidgetDepartures(configTimeline, allItems, maxVisibleRows = 4)

        assertThat(processed).hasSize(4)
        // Should be sorted purely by countdown: 2, 5, 12, 15
        assertThat(processed[0].countdown).isEqualTo(2)
        assertThat(processed[0].rbl).isEqualTo(1398)
        assertThat(processed[1].countdown).isEqualTo(5)
        assertThat(processed[1].rbl).isEqualTo(1876)
        assertThat(processed[2].countdown).isEqualTo(12)
        assertThat(processed[2].rbl).isEqualTo(1398)
        assertThat(processed[3].countdown).isEqualTo(15)
        assertThat(processed[3].rbl).isEqualTo(1876)
    }
}
