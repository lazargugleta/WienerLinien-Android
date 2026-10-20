package com.wienerlinien.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.wienerlinien.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages checking, downloading, and installing app updates over the internet.
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        const val PREFS_NAME = "wienerlinien_update_prefs"
        const val KEY_CUSTOM_UPDATE_URL = "custom_update_url"
        const val KEY_LAST_CHECK_TIME = "last_check_timestamp"

        // Default URL: Can be configured in settings or overridden
        const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/lazargugleta/WienerLinien-Android/main/version.json"

        // Minimum time between automatic background checks (6 hours)
        private const val AUTO_CHECK_COOLDOWN_MS = 6 * 3600 * 1000L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getUpdateUrl(): String {
        return prefs.getString(KEY_CUSTOM_UPDATE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_UPDATE_URL
    }

    fun setUpdateUrl(url: String?) {
        prefs.edit().putString(KEY_CUSTOM_UPDATE_URL, url?.trim()).apply()
    }

    fun shouldCheckAutomatically(): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
        return (System.currentTimeMillis() - lastCheck) > AUTO_CHECK_COOLDOWN_MS
    }

    fun recordCheckTime() {
        prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
    }

    /**
     * Checks the remote update endpoint for an available update.
     * Returns UpdateInfo if remote versionCode > currentVersionCode, or null if up to date.
     */
    suspend fun checkForUpdate(
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        overrideUrl: String? = null
    ): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        val targetUrl = overrideUrl ?: getUpdateUrl()
        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "WienerLinien-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to check for updates: HTTP ${response.code}")
                )
            }

            val bodyString = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty update response")
            )

            val updateInfo = gson.fromJson(bodyString, UpdateInfo::class.java)
                ?: return@withContext Result.failure(Exception("Invalid update format"))

            recordCheckTime()

            if (updateInfo.versionCode > currentVersionCode) {
                Result.success(updateInfo)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file to app-specific external files dir or cache.
     * Emits progress percentage via onProgress (0..100).
     */
    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(updateInfo.apkUrl)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Download failed: HTTP ${response.code}")
                )
            }

            val body = response.body ?: return@withContext Result.failure(
                Exception("Download body is null")
            )

            val totalBytes = body.contentLength()
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.cacheDir

            val apkFile = File(downloadDir, "wienerlinien_v${updateInfo.versionName}.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) {
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Triggers the Android package installation for the downloaded APK.
     */
    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return

        // On Android 8.0+ (Oreo), verify permission to install unknown apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }
}
