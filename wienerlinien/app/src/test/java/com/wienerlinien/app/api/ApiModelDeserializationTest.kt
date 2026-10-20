package com.wienerlinien.app.api

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.wienerlinien.app.model.Line
import com.wienerlinien.app.model.MonitorResponse
import org.junit.Test

class ApiModelDeserializationTest {

    private val gson = Gson()

    @Test
    fun deserialize_validMonitorJson_parsesMonitorsAndLinesCorrectly() {
        val json = """
            {
              "data": {
                "monitors": [
                  {
                    "locationStop": {
                      "type": "Feature",
                      "geometry": {
                        "type": "Point",
                        "coordinates": [16.3717, 48.2086]
                      },
                      "properties": {
                        "name": "60200001",
                        "title": "Stephansplatz",
                        "municipality": "Wien",
                        "type": "stop",
                        "attributes": {
                          "rbl": 4116
                        }
                      }
                    },
                    "lines": [
                      {
                        "name": "U1",
                        "towards": "Oberlaa",
                        "direction": "H",
                        "richtungsId": "1",
                        "barrierFree": true,
                        "realtimeSupported": true,
                        "trafficjam": false,
                        "type": "ptMetro",
                        "departures": {
                          "departure": [
                            {
                              "departureTime": {
                                "timePlanned": "2026-10-14T10:00:00.000+0200",
                                "timeReal": "2026-10-14T10:01:00.000+0200",
                                "countdown": 3
                              },
                              "vehicle": {
                                "name": "U1",
                                "towards": "Oberlaa",
                                "direction": "H",
                                "barrierFree": true,
                                "realtimeSupported": true,
                                "type": "ptMetro"
                              }
                            }
                          ]
                        }
                      }
                    ]
                  }
                ]
              },
              "message": {
                "value": "OK",
                "messageCode": 1,
                "serverTime": "2026-10-14T09:58:00.000+0200"
              }
            }
        """.trimIndent()

        val response = gson.fromJson(json, MonitorResponse::class.java)

        assertThat(response).isNotNull()
        assertThat(response.message?.value).isEqualTo("OK")
        assertThat(response.message?.messageCode).isEqualTo(1)

        val monitors = response.data?.monitors
        assertThat(monitors).isNotNull()
        assertThat(monitors).hasSize(1)

        val monitor = monitors!![0]
        assertThat(monitor.locationStop?.properties?.title).isEqualTo("Stephansplatz")
        assertThat(monitor.locationStop?.properties?.attributes?.rbl).isEqualTo(4116)
        assertThat(monitor.locationStop?.geometry?.coordinates).containsExactly(16.3717, 48.2086).inOrder()

        val lines = monitor.lines
        assertThat(lines).isNotNull()
        assertThat(lines).hasSize(1)

        val line = lines!![0]
        assertThat(line.name).isEqualTo("U1")
        assertThat(line.towards).isEqualTo("Oberlaa")
        assertThat(line.type).isEqualTo("ptMetro")
        assertThat(line.barrierFree).isTrue()
        assertThat(line.realtimeSupported).isTrue()

        val departures = line.departures?.departure
        assertThat(departures).isNotNull()
        assertThat(departures).hasSize(1)

        val dep = departures!![0]
        assertThat(dep.departureTime?.countdown).isEqualTo(3)
        assertThat(dep.departureTime?.timePlanned).isEqualTo("2026-10-14T10:00:00.000+0200")
        assertThat(dep.departureTime?.timeReal).isEqualTo("2026-10-14T10:01:00.000+0200")
        assertThat(dep.vehicle?.towards).isEqualTo("Oberlaa")
    }

    @Test
    fun deserialize_departureWithRealtime_extractsCountdownAndBothTimestamps() {
        val json = """
            {
              "data": {
                "monitors": [
                  {
                    "lines": [
                      {
                        "name": "13A",
                        "type": "ptBusCity",
                        "departures": {
                          "departure": [
                            {
                              "departureTime": {
                                "timePlanned": "2026-10-14T12:00:00.000+0200",
                                "timeReal": "2026-10-14T12:02:00.000+0200",
                                "countdown": 2
                              }
                            }
                          ]
                        }
                      }
                    ]
                  }
                ]
              },
              "message": { "value": "OK" }
            }
        """.trimIndent()

        val response = gson.fromJson(json, MonitorResponse::class.java)
        val depTime = response.data?.monitors?.get(0)?.lines?.get(0)?.departures?.departure?.get(0)?.departureTime

        assertThat(depTime).isNotNull()
        assertThat(depTime?.countdown).isEqualTo(2)
        assertThat(depTime?.timePlanned).isEqualTo("2026-10-14T12:00:00.000+0200")
        assertThat(depTime?.timeReal).isEqualTo("2026-10-14T12:02:00.000+0200")
    }

    @Test
    fun deserialize_departureWithoutRealtime_setsTimeRealToNull() {
        val json = """
            {
              "data": {
                "monitors": [
                  {
                    "lines": [
                      {
                        "name": "49",
                        "type": "ptTram",
                        "departures": {
                          "departure": [
                            {
                              "departureTime": {
                                "timePlanned": "2026-10-14T14:30:00.000+0200",
                                "countdown": 12
                              }
                            }
                          ]
                        }
                      }
                    ]
                  }
                ]
              },
              "message": { "value": "OK" }
            }
        """.trimIndent()

        val response = gson.fromJson(json, MonitorResponse::class.java)
        val depTime = response.data?.monitors?.get(0)?.lines?.get(0)?.departures?.departure?.get(0)?.departureTime

        assertThat(depTime).isNotNull()
        assertThat(depTime?.countdown).isEqualTo(12)
        assertThat(depTime?.timePlanned).isEqualTo("2026-10-14T14:30:00.000+0200")
        assertThat(depTime?.timeReal).isNull()
    }

    @Test
    fun deserialize_emptyMonitors_parsesCleanlyWithoutNpe() {
        val json = """
            {
              "data": {
                "monitors": []
              },
              "message": {
                "value": "OK",
                "messageCode": 1
              }
            }
        """.trimIndent()

        val response = gson.fromJson(json, MonitorResponse::class.java)
        assertThat(response.data?.monitors).isEmpty()
        assertThat(response.message?.value).isEqualTo("OK")
    }

    @Test
    fun deserialize_error321MissingParameter_extractsMessageAndCode() {
        val json = """
            {
              "message": {
                "value": "Missing query parameter",
                "messageCode": 321,
                "serverTime": "2026-10-14T11:00:00.000+0200"
              }
            }
        """.trimIndent()

        val response = gson.fromJson(json, MonitorResponse::class.java)
        assertThat(response.data).isNull()
        assertThat(response.message?.value).isEqualTo("Missing query parameter")
        assertThat(response.message?.messageCode).isEqualTo(321)
    }

    @Test
    fun deserialize_error311DatabaseUnavailable_extractsMessageAndCode() {
        val json = """
            {
              "message": {
                "value": "Database unavailable",
                "messageCode": 311,
                "serverTime": "2026-10-14T11:00:00.000+0200"
              }
            }
        """.trimIndent()

        val response = gson.fromJson(json, MonitorResponse::class.java)
        assertThat(response.message?.value).isEqualTo("Database unavailable")
        assertThat(response.message?.messageCode).isEqualTo(311)
    }

    @Test
    fun deserialize_malformedNullValues_handledGracefully() {
        val json = """
            {
              "data": {
                "monitors": [
                  {
                    "locationStop": null,
                    "lines": null
                  }
                ]
              },
              "message": null
            }
        """.trimIndent()

        val response = gson.fromJson(json, MonitorResponse::class.java)
        assertThat(response).isNotNull()
        assertThat(response.data?.monitors).hasSize(1)
        assertThat(response.data?.monitors?.get(0)?.lines).isNull()
        assertThat(response.message).isNull()
    }

    @Test
    fun modelHelpers_lineTypeIcon_returnsExpectedLettersForTypes() {
        fun makeLine(type: String) = Line(
            name = "Test",
            towards = null,
            direction = null,
            richtungsId = null,
            barrierFree = null,
            realtimeSupported = null,
            trafficjam = null,
            type = type,
            departures = null
        )

        assertThat(makeLine("ptMetro").lineTypeIcon()).isEqualTo("U")
        assertThat(makeLine("ptTram").lineTypeIcon()).isEqualTo("T")
        assertThat(makeLine("ptBusCity").lineTypeIcon()).isEqualTo("B")
        assertThat(makeLine("ptBusNight").lineTypeIcon()).isEqualTo("N")
        assertThat(makeLine("ptUnknown").lineTypeIcon()).isEqualTo("?")
    }

    @Test
    fun modelHelpers_lineColor_returnsOfficialWienerLinienColors() {
        fun makeNamedLine(name: String, type: String = "ptMetro") = Line(
            name = name,
            towards = null,
            direction = null,
            richtungsId = null,
            barrierFree = null,
            realtimeSupported = null,
            trafficjam = null,
            type = type,
            departures = null
        )

        // U-Bahn colors
        assertThat(makeNamedLine("U1").lineColor()).isEqualTo(0xFFE2001A)
        assertThat(makeNamedLine("U2").lineColor()).isEqualTo(0xFF9B27AF)
        assertThat(makeNamedLine("U3").lineColor()).isEqualTo(0xFFFF6600)
        assertThat(makeNamedLine("U4").lineColor()).isEqualTo(0xFF009A44)
        assertThat(makeNamedLine("U6").lineColor()).isEqualTo(0xFF8B6914)

        // Surface transit colors
        assertThat(makeNamedLine("D", "ptTram").lineColor()).isEqualTo(0xFFD41727)
        assertThat(makeNamedLine("13A", "ptBusCity").lineColor()).isEqualTo(0xFF1565C0)
        assertThat(makeNamedLine("N60", "ptBusNight").lineColor()).isEqualTo(0xFF2E3B4E)
        assertThat(makeNamedLine("Other", "unknown").lineColor()).isEqualTo(0xFF666666)
    }
}
