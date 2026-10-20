package com.wienerlinien.app.update

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.wienerlinien.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object UpdateDialogHelper {

    fun showUpdateDialog(
        activity: Activity,
        scope: CoroutineScope,
        updateInfo: UpdateInfo,
        updateManager: AppUpdateManager
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)

        val titleView: TextView = view.findViewById(R.id.updateTitle)
        val versionText: TextView = view.findViewById(R.id.updateVersionText)
        val changelogView: TextView = view.findViewById(R.id.updateChangelog)
        val progressBar: LinearProgressIndicator = view.findViewById(R.id.updateProgressBar)
        val statusText: TextView = view.findViewById(R.id.updateStatusText)
        val laterBtn: MaterialButton = view.findViewById(R.id.updateLaterBtn)
        val updateNowBtn: MaterialButton = view.findViewById(R.id.updateNowBtn)

        titleView.text = "Update Available"
        versionText.text = "Version ${updateInfo.versionName} is ready to install"
        changelogView.text = updateInfo.changelog?.takeIf { it.isNotBlank() }
            ?: "General improvements and bug fixes."

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(!updateInfo.isMandatory)
            .create()

        if (updateInfo.isMandatory) {
            laterBtn.visibility = View.GONE
        } else {
            laterBtn.setOnClickListener {
                dialog.dismiss()
            }
        }

        updateNowBtn.setOnClickListener {
            updateNowBtn.isEnabled = false
            updateNowBtn.text = "Downloading…"
            laterBtn.isEnabled = false
            progressBar.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.text = "Connecting…"

            scope.launch {
                val result = updateManager.downloadApk(updateInfo) { progress ->
                    progressBar.progress = progress
                    statusText.text = "Downloading update… $progress%"
                }

                result.onSuccess { apkFile ->
                    statusText.text = "Download complete. Starting install…"
                    dialog.dismiss()
                    updateManager.installApk(apkFile)
                }

                result.onFailure { error ->
                    progressBar.visibility = View.GONE
                    statusText.text = "Download failed: ${error.message}"
                    updateNowBtn.isEnabled = true
                    updateNowBtn.text = "Retry"
                    laterBtn.isEnabled = true
                    Toast.makeText(activity, "Update download failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }
}
