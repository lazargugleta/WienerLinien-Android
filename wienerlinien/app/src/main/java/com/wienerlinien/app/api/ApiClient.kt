package com.wienerlinien.app.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val BASE_URL = "https://www.wienerlinien.at/ogd_realtime/"

    // 15 seconds allows reliable network roundtrips on mobile/underground networks without blocking Android's 30s receiver limits
    private const val TIMEOUT_SECONDS = 15L

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    /**
     * In-memory CookieJar to persist Radware WAF / Bot Manager session cookies (__uzm*, dtCookie).
     * This prevents the server WAF from blocking or challenging consecutive requests.
     */
    private val cookieJar = object : CookieJar {
        private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) {
                val existing = cookieStore[url.host] ?: emptyList()
                val updated = (existing.filter { old -> cookies.none { it.name == old.name } } + cookies)
                cookieStore[url.host] = updated
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val list = cookieStore[url.host] ?: return emptyList()
            // Filter expired cookies
            return list.filter { it.expiresAt > now }
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept", "application/json")
                .header("User-Agent", "WienerLinien-Android/1.0 (Android 14; Mobile)")
                .build()
            chain.proceed(request)
        }
        .retryOnConnectionFailure(true)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: WienerLinienApiService = retrofit.create(WienerLinienApiService::class.java)
}
