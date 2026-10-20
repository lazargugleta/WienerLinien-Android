package com.wienerlinien.app.api

import com.wienerlinien.app.model.MonitorResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface WienerLinienApiService {

    /**
     * Fetch real-time departure monitor for one or more RBL stops.
     *
     * Official endpoint: https://www.wienerlinien.at/ogd_realtime/monitor
     *
     * @param stopId  RBL number(s). Can be repeated for multiple stops.
     * @param activateTrafficInfo Optional disruption types: stoerungkurz | stoerunglang | aufzugsinfo
     *
     * Example:
     *   /monitor?stopId=252&activateTrafficInfo=stoerungkurz
     *
     * Response: JSON with data.monitors[].lines[].departures.departure[].departureTime.countdown
     * countdown = minutes until departure (integer)
     */
    @GET("monitor")
    suspend fun getMonitor(
        @Query("stopId") stopId: List<Int>,
        @Query("activateTrafficInfo") activateTrafficInfo: List<String> = emptyList()
    ): Response<MonitorResponse>

    /**
     * Fetch active citywide disruptions, delays, elevator outages, and planned maintenance.
     * Official endpoint: https://www.wienerlinien.at/ogd_realtime/trafficInfoList
     */
    @GET("trafficInfoList")
    suspend fun getTrafficInfoList(
        @Query("name") name: String? = null
    ): Response<com.wienerlinien.app.model.TrafficInfoResponse>

    /**
     * Search stops by name using the static CSV (via a helper utility, not retrofit).
     * The CSV is at: https://www.wienerlinien.at/ogd_realtime/doku/ogd/wienerlinien-ogd-haltepunkte.csv
     * Format: StopID;DIVA;StopText;Municipality;MunicipalityID;Longitude;Latitude
     * StopID = RBL number
     */
}
