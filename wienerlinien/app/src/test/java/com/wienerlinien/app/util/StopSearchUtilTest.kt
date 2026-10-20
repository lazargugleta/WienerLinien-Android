package com.wienerlinien.app.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class StopSearchUtilTest {

    private val sampleCsv = """
        rblNumber;stopName;municipality;platform;lines;longitude;latitude
        4116;Stephansplatz;Wien;1;U1;16.3717;48.2086
        4117;Stephansplatz;Wien;2;U1;16.3717;48.2086
        4920;Stephansplatz;Wien;1;U3;16.3717;48.2086
        4200;Karlsplatz;Wien;1;U1, U2, U4;16.3698;48.2001
        4850;Landstraße;Wien;1;U3, U4, O, 74A;16.3853;48.2064
        4900;Hütteldorf;Wien;1;U4, 49, 52;16.2619;48.1972
        4400;Westbahnhof;Wien;1;U3, U6, 5, 6, 9, 18, 52, 60;16.3377;48.1969
    """.trimIndent()

    @Before
    fun setUp() {
        StopSearchUtil.clearCache()
        StopSearchUtil.forceOfflineForTesting = true
        // Inject sample CSV stream provider for testing
        StopSearchUtil.assetStreamProvider = {
            sampleCsv.byteInputStream(Charsets.UTF_8)
        }
    }

    @After
    fun tearDown() {
        StopSearchUtil.clearCache()
        StopSearchUtil.forceOfflineForTesting = false
        StopSearchUtil.assetStreamProvider = null
    }

    @Test
    fun parseStopsCsv_parsesValidCsvWithUmlauts() {
        val reader = BufferedReader(StringReader(sampleCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        assertThat(results).hasSize(7)

        val stephansplatzU1 = results.firstOrNull { it.rblNumber == 4116 }
        assertThat(stephansplatzU1).isNotNull()
        assertThat(stephansplatzU1?.stopName).isEqualTo("Stephansplatz")
        assertThat(stephansplatzU1?.platform).isEqualTo("1")
        assertThat(stephansplatzU1?.lines).isEqualTo("U1")
        assertThat(stephansplatzU1?.longitude).isEqualTo(16.3717)
        assertThat(stephansplatzU1?.latitude).isEqualTo(48.2086)

        val huetteldorf = results.firstOrNull { it.rblNumber == 4900 }
        assertThat(huetteldorf).isNotNull()
        assertThat(huetteldorf?.stopName).isEqualTo("Hütteldorf")

        val landstrasse = results.firstOrNull { it.rblNumber == 4850 }
        assertThat(landstrasse).isNotNull()
        assertThat(landstrasse?.stopName).isEqualTo("Landstraße")
    }

    @Test
    fun parseStopsCsv_skipsHeaderRow() {
        val reader = BufferedReader(StringReader(sampleCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        assertThat(results.none { it.stopName.contains("stopName", ignoreCase = true) }).isTrue()
        assertThat(results.none { it.rblNumber == 0 }).isTrue()
    }

    @Test
    fun parseStopsCsv_handlesMissingColumnsGracefully() {
        val csvWithBadRows = """
            rblNumber;stopName;municipality;platform;lines;longitude;latitude
            4116;Stephansplatz;Wien;1;U1;16.3717;48.2086
            invalid_row_with_few_columns;foo;bar
            ;missing_rbl;Wien;1;U1;16.0;48.0
            4200;Karlsplatz;Wien;1;U1, U2, U4;16.3698;48.2001
        """.trimIndent()

        val reader = BufferedReader(StringReader(csvWithBadRows))
        val results = StopSearchUtil.parseStopsCsv(reader)

        assertThat(results).hasSize(2)
        assertThat(results.map { it.rblNumber }).containsExactly(4116, 4200)
    }

    @Test
    fun parseStopsCsv_skipsNonNumericRbls() {
        val badCsv = """
            rblNumber;stopName;municipality;platform;lines;longitude;latitude
            NON_NUMERIC;Invalid;Wien;1;U1;16.0;48.0
            4116;Stephansplatz;Wien;1;U1;16.3717;48.2086
        """.trimIndent()

        val reader = BufferedReader(StringReader(badCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        assertThat(results).hasSize(1)
        assertThat(results[0].rblNumber).isEqualTo(4116)
    }

    @Test
    fun loadBundledStops_fallsBackToStreamProviderWhenOffline() {
        val stops = StopSearchUtil.loadBundledStops()

        assertThat(stops).isNotEmpty()
        assertThat(stops.map { it.stopName }).contains("Stephansplatz")
        assertThat(stops.map { it.stopName }).contains("Karlsplatz")
    }

    @Test
    fun searchStops_caseInsensitiveNameQuery_returnsMatchingStop() = runBlocking {
        val results = StopSearchUtil.searchStops("stephansplatz")

        assertThat(results).isNotEmpty()
        assertThat(results.all { it.stopName.equals("Stephansplatz", ignoreCase = true) }).isTrue()
    }

    @Test
    fun searchStops_lineNameQuery_returnsStopsServingLine() = runBlocking {
        val results = StopSearchUtil.searchStops("U6")

        assertThat(results).isNotEmpty()
        assertThat(results.any { it.stopName == "Westbahnhof" }).isTrue()
    }

    @Test
    fun searchStops_emptyQuery_returnsTopResults() = runBlocking {
        val results = StopSearchUtil.searchStops("")

        assertThat(results).isNotEmpty()
        assertThat(results.size).isAtMost(50)
    }

    @Test
    fun parseStopsCsv_distinguishesPlatformsAndLinesForSameStation() {
        val reader = BufferedReader(StringReader(sampleCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        val stephansplatzPlatforms = results.filter { it.stopName == "Stephansplatz" }
        assertThat(stephansplatzPlatforms).hasSize(3)

        // Verifies platform and lines are distinct
        val rbls = stephansplatzPlatforms.map { it.rblNumber }
        assertThat(rbls).containsExactly(4116, 4117, 4920)

        val lines = stephansplatzPlatforms.map { it.lines }
        assertThat(lines).contains("U1")
        assertThat(lines).contains("U3")
    }
}
