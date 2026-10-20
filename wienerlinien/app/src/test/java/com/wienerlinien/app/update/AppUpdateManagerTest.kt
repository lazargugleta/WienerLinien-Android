package com.wienerlinien.app.update

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class AppUpdateManagerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val prefStorage = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()
        prefStorage.clear()

        whenever(mockContext.getSharedPreferences(eq(AppUpdateManager.PREFS_NAME), eq(Context.MODE_PRIVATE)))
            .thenReturn(mockPrefs)
        whenever(mockPrefs.edit()).thenReturn(mockEditor)

        whenever(mockEditor.putString(any(), anyOrNull())).thenAnswer {
            val key = it.getArgument<String>(0)
            val value = it.getArgument<String?>(1)
            prefStorage[key] = value
            mockEditor
        }

        whenever(mockEditor.putLong(any(), any())).thenAnswer {
            val key = it.getArgument<String>(0)
            val value = it.getArgument<Long>(1)
            prefStorage[key] = value
            mockEditor
        }

        whenever(mockPrefs.getString(any(), anyOrNull())).thenAnswer {
            val key = it.getArgument<String>(0)
            val default = it.getArgument<String?>(1)
            (prefStorage[key] as? String) ?: default
        }

        whenever(mockPrefs.getLong(any(), any())).thenAnswer {
            val key = it.getArgument<String>(0)
            val default = it.getArgument<Long>(1)
            (prefStorage[key] as? Long) ?: default
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun updateInfo_deserializesValidJsonProperly() {
        val json = """
            {
                "versionCode": 5,
                "versionName": "1.2.0",
                "apkUrl": "https://example.com/app.apk",
                "changelog": "New station reordering",
                "releaseDate": "2026-10-19",
                "isMandatory": true
            }
        """.trimIndent()

        val info = Gson().fromJson(json, UpdateInfo::class.java)
        assertThat(info.versionCode).isEqualTo(5)
        assertThat(info.versionName).isEqualTo("1.2.0")
        assertThat(info.apkUrl).isEqualTo("https://example.com/app.apk")
        assertThat(info.changelog).isEqualTo("New station reordering")
        assertThat(info.releaseDate).isEqualTo("2026-10-19")
        assertThat(info.isMandatory).isTrue()
    }

    @Test
    fun checkForUpdate_whenRemoteVersionHigher_returnsUpdateInfo() = runTest {
        val json = """
            {
                "versionCode": 2,
                "versionName": "1.1",
                "apkUrl": "https://example.com/app-v1.1.apk",
                "changelog": "Bug fixes"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val manager = AppUpdateManager(mockContext)
        val updateUrl = mockWebServer.url("/version.json").toString()

        val result = manager.checkForUpdate(currentVersionCode = 1, overrideUrl = updateUrl)

        assertThat(result.isSuccess).isTrue()
        val updateInfo = result.getOrThrow()
        assertThat(updateInfo).isNotNull()
        assertThat(updateInfo?.versionCode).isEqualTo(2)
        assertThat(updateInfo?.versionName).isEqualTo("1.1")
        assertThat(updateInfo?.apkUrl).isEqualTo("https://example.com/app-v1.1.apk")
    }

    @Test
    fun checkForUpdate_whenRemoteVersionSameOrLower_returnsNull() = runTest {
        val json = """
            {
                "versionCode": 1,
                "versionName": "1.0",
                "apkUrl": "https://example.com/app-v1.0.apk"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val manager = AppUpdateManager(mockContext)
        val updateUrl = mockWebServer.url("/version.json").toString()

        val result = manager.checkForUpdate(currentVersionCode = 1, overrideUrl = updateUrl)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isNull()
    }

    @Test
    fun checkForUpdate_whenHttpError_returnsFailure() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val manager = AppUpdateManager(mockContext)
        val updateUrl = mockWebServer.url("/missing.json").toString()

        val result = manager.checkForUpdate(currentVersionCode = 1, overrideUrl = updateUrl)

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun updateUrl_customUrlSavedAndRetrieved() {
        val manager = AppUpdateManager(mockContext)

        // Default URL
        assertThat(manager.getUpdateUrl()).isEqualTo(AppUpdateManager.DEFAULT_UPDATE_URL)

        // Save custom URL
        manager.setUpdateUrl("https://my-server.com/app_version.json")
        assertThat(manager.getUpdateUrl()).isEqualTo("https://my-server.com/app_version.json")

        // Reset to default
        manager.setUpdateUrl(null)
        assertThat(manager.getUpdateUrl()).isEqualTo(AppUpdateManager.DEFAULT_UPDATE_URL)
    }

    @Test
    fun shouldCheckAutomatically_respectsCooldown() {
        val manager = AppUpdateManager(mockContext)

        // Never checked before
        assertThat(manager.shouldCheckAutomatically()).isTrue()

        // Just checked
        manager.recordCheckTime()
        assertThat(manager.shouldCheckAutomatically()).isFalse()
    }
}
