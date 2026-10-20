package com.wienerlinien.app.update

import com.google.gson.annotations.SerializedName

/**
 * Model representing remote update metadata fetched over the internet.
 */
data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("apkUrl") val apkUrl: String,
    @SerializedName("changelog") val changelog: String? = null,
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("isMandatory") val isMandatory: Boolean = false
)
