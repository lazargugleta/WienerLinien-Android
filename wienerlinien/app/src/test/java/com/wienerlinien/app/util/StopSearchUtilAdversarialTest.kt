package com.wienerlinien.app.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader

class StopSearchUtilAdversarialTest {

    private val richCsv = """
        rblNumber;stopName;municipality;platform;lines;longitude;latitude
        4116;Stephansplatz;Wien;1;U1, U3;16.3717;48.2086
        4900;Hütteldorf;Wien;1;U4, 49, 52;16.2619;48.1972
        4850;Landstraße;Wien;1;U3, U4, O, 74A;16.3853;48.2064
        4250;Schönbrunn;Wien;1;U4, 10A;16.3194;48.1856
        4310;Längenfeldgasse;Wien;1;U4, U6;16.3347;48.1847
        4720;Jägerstraße;Wien;1;U6, 31, 33;16.3686;48.2353
        4600;Praterstern;Wien;1;U1, U2, O, 5, 80A;16.3917;48.2186
        4400;Westbahnhof;Wien;1;U3, U6, 5, 6, 9, 18, 52, 60;16.3377;48.1969
    """.trimIndent()

    @Before
    fun setUp() {
        StopSearchUtil.clearCache()
        StopSearchUtil.forceOfflineForTesting = true
        StopSearchUtil.assetStreamProvider = {
            richCsv.byteInputStream(Charsets.UTF_8)
        }
    }

    @After
    fun tearDown() {
        StopSearchUtil.clearCache()
        StopSearchUtil.forceOfflineForTesting = false
        StopSearchUtil.assetStreamProvider = null
    }

    // ── German Umlauts & Special Characters ─────────────────────────────────

    @Test
    fun searchStops_germanUmlauts_caseInsensitiveMatches() = runBlocking {
        // Lower-case search
        val r1 = StopSearchUtil.searchStops("hütteldorf")
        assertThat(r1).hasSize(1)
        assertThat(r1[0].stopName).isEqualTo("Hütteldorf")

        // Upper-case search
        val r2 = StopSearchUtil.searchStops("HÜTTELDORF")
        assertThat(r2).hasSize(1)
        assertThat(r2[0].stopName).isEqualTo("Hütteldorf")

        // Mixed case with 'ö'
        val r3 = StopSearchUtil.searchStops("Schönbrunn")
        assertThat(r3).hasSize(1)
        assertThat(r3[0].stopName).isEqualTo("Schönbrunn")

        val r4 = StopSearchUtil.searchStops("SCHÖNBRUNN")
        assertThat(r4).hasSize(1)
        assertThat(r4[0].stopName).isEqualTo("Schönbrunn")

        // Search with 'ä'
        val r5 = StopSearchUtil.searchStops("längenfeldgasse")
        assertThat(r5).hasSize(1)
        assertThat(r5[0].stopName).isEqualTo("Längenfeldgasse")

        val r6 = StopSearchUtil.searchStops("LÄNGENFELDGASSE")
        assertThat(r6).hasSize(1)
        assertThat(r6[0].stopName).isEqualTo("Längenfeldgasse")

        // Search with 'ß'
        val r7 = StopSearchUtil.searchStops("landstraße")
        assertThat(r7).hasSize(1)
        assertThat(r7[0].stopName).isEqualTo("Landstraße")

        val r8 = StopSearchUtil.searchStops("LANDSTRAßE")
        assertThat(r8).hasSize(1)
        assertThat(r8[0].stopName).isEqualTo("Landstraße")
    }

    @Test
    fun searchStops_regexMetacharacters_doesNotCrash() = runBlocking {
        val dangerousInputs = listOf(
            ".*", "[a-z]", "(test)", ".*+?^$[]{}()|\\",
            "U1.*", "^Stephansplatz$", "[1]", "??", "***",
            "\"Stephansplatz\"", "'Hütteldorf'", "\\d+", "\\s+"
        )

        for (query in dangerousInputs) {
            // Must not throw PatternSyntaxException or crash
            val results = StopSearchUtil.searchStops(query)
            assertThat(results).isNotNull()
        }
    }

    @Test
    fun searchStops_specialPunctuationAndLines_matchesCorrectly() = runBlocking {
        // Query line containing comma and space
        val results = StopSearchUtil.searchStops("U3, U6")
        assertThat(results).isNotEmpty()
        assertThat(results.any { it.stopName == "Westbahnhof" }).isTrue()

        // Query with leading/trailing whitespaces
        val whitespaceResults = StopSearchUtil.searchStops("   Hütteldorf   ")
        assertThat(whitespaceResults).hasSize(1)
        assertThat(whitespaceResults[0].stopName).isEqualTo("Hütteldorf")
    }

    @Test
    fun searchStops_blankAndEmptyQuery_returnsTopResultsCappedAt50() = runBlocking {
        val emptyResults = StopSearchUtil.searchStops("")
        assertThat(emptyResults).isNotEmpty()
        assertThat(emptyResults.size).isAtMost(50)

        val whitespaceOnlyResults = StopSearchUtil.searchStops("    ")
        assertThat(whitespaceOnlyResults).isNotEmpty()
        assertThat(whitespaceOnlyResults.size).isAtMost(50)
    }

    // ── Corrupted & Malformed CSV Stream Adversarial Tests ──────────────────

    @Test
    fun parseStopsCsv_emptyReader_returnsEmptyListWithoutThrowing() {
        val reader = BufferedReader(StringReader(""))
        val results = StopSearchUtil.parseStopsCsv(reader)
        assertThat(results).isEmpty()
    }

    @Test
    fun parseStopsCsv_onlyHeaderRow_returnsEmptyList() {
        val reader = BufferedReader(StringReader("rblNumber;stopName;municipality;platform;lines;longitude;latitude\n"))
        val results = StopSearchUtil.parseStopsCsv(reader)
        assertThat(results).isEmpty()
    }

    @Test
    fun parseStopsCsv_corruptedBinaryAndNullBytes_skipsSafely() {
        val corruptedCsv = """
            rblNumber;stopName;municipality;platform;lines;longitude;latitude
            \u0000\u0000\u0000;corrupted;null;bytes;here;0.0;0.0
            4116;Stephansplatz;Wien;1;U1;16.3717;48.2086
            \ufffd\ufffd\ufffd;binary_garbage
        """.trimIndent()

        val reader = BufferedReader(StringReader(corruptedCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        // Only the valid Stephansplatz row should be parsed
        assertThat(results).hasSize(1)
        assertThat(results[0].rblNumber).isEqualTo(4116)
        assertThat(results[0].stopName).isEqualTo("Stephansplatz")
    }

    @Test
    fun parseStopsCsv_negativeAndZeroRbls_areFilteredOut() {
        val badRblCsv = """
            rblNumber;stopName;municipality;platform;lines;longitude;latitude
            -1;Negative RBL;Wien;1;U1;16.0;48.0
            0;Zero RBL;Wien;1;U1;16.0;48.0
            4116;Stephansplatz;Wien;1;U1;16.3717;48.2086
        """.trimIndent()

        val reader = BufferedReader(StringReader(badRblCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        assertThat(results).hasSize(1)
        assertThat(results[0].rblNumber).isEqualTo(4116)
    }

    @Test
    fun parseStopsCsv_handlesEuropeanCommaDecimalsInCoordinates() {
        val commaCsv = """
            rblNumber;stopName;municipality;platform;lines;longitude;latitude
            4116;Stephansplatz;Wien;1;U1;16,371725;48,208611
        """.trimIndent()

        val reader = BufferedReader(StringReader(commaCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        assertThat(results).hasSize(1)
        assertThat(results[0].longitude).isEqualTo(16.371725)
        assertThat(results[0].latitude).isEqualTo(48.208611)
    }

    @Test
    fun parseStopsCsv_handlesQuotesAndExtraWhitespace() {
        val quotedCsv = """
            rblNumber;stopName;municipality;platform;lines;longitude;latitude
            "4116" ; "Stephansplatz" ; "Wien" ; "1" ; "U1, U3" ; "16.3717" ; "48.2086"
        """.trimIndent()

        val reader = BufferedReader(StringReader(quotedCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        assertThat(results).hasSize(1)
        assertThat(results[0].rblNumber).isEqualTo(4116)
        assertThat(results[0].stopName).isEqualTo("Stephansplatz")
        assertThat(results[0].municipality).isEqualTo("Wien")
        assertThat(results[0].platform).isEqualTo("1")
        assertThat(results[0].lines).isEqualTo("U1, U3")
    }

    @Test
    fun parseStopsCsv_handlesExtraColumnsBeyondSeven() {
        val extraColsCsv = """
            rblNumber;stopName;municipality;platform;lines;longitude;latitude;extraCol1;extraCol2
            4116;Stephansplatz;Wien;1;U1;16.3717;48.2086;extra;data
        """.trimIndent()

        val reader = BufferedReader(StringReader(extraColsCsv))
        val results = StopSearchUtil.parseStopsCsv(reader)

        assertThat(results).hasSize(1)
        assertThat(results[0].rblNumber).isEqualTo(4116)
    }

    // ── Asset Stream Provider Error Handling ────────────────────────────────

    @Test
    fun loadBundledStops_providerReturnsNull_returnsEmptyListGracefully() {
        StopSearchUtil.assetStreamProvider = { null }
        val results = StopSearchUtil.loadBundledStops()
        assertThat(results).isEmpty()
    }

    @Test
    fun loadBundledStops_streamThrowsIOExceptionDuringRead_returnsEmptyListGracefully() {
        StopSearchUtil.assetStreamProvider = {
            object : java.io.InputStream() {
                override fun read(): Int = throw IOException("Disk read error while reading asset")
            }
        }
        val results = StopSearchUtil.loadBundledStops()
        assertThat(results).isEmpty()
    }

    @Test
    fun loadBundledStops_zeroByteStream_returnsEmptyListGracefully() {
        StopSearchUtil.assetStreamProvider = {
            ByteArrayInputStream(ByteArray(0))
        }
        val results = StopSearchUtil.loadBundledStops()
        assertThat(results).isEmpty()
    }

    // ── Caching & Invalidation ──────────────────────────────────────────────

    @Test
    fun clearCache_resetsCachedStops() = runBlocking {
        // Query to populate cache
        val initial = StopSearchUtil.searchStops("Stephansplatz")
        assertThat(initial).isNotEmpty()

        // Clear cache and change stream provider to empty
        StopSearchUtil.clearCache()
        StopSearchUtil.assetStreamProvider = { ByteArrayInputStream(ByteArray(0)) }

        val afterClear = StopSearchUtil.searchStops("Stephansplatz")
        assertThat(afterClear).isEmpty()
    }
}
