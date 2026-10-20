package com.wienerlinien.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.*
import com.wienerlinien.app.model.AppDatabase
import com.wienerlinien.app.model.DepartureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that triggers widget updates directly without background services.
 *
 * Runs periodically (every 15 min, WorkManager minimum interval) to ensure home screen
 * widgets stay updated even when the app is closed.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            val manager = AppWidgetManager.getInstance(applicationContext)
            val specificId = inputData.getInt(
                DepartureWidgetProvider.EXTRA_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )

            if (specificId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val db = AppDatabase.getDatabase(applicationContext)
                val repo = DepartureRepository(db)
                DepartureWidgetProvider.updateSingleWidget(applicationContext, manager, specificId, repo, forceRefresh = true)
            } else {
                val ids = manager.getAppWidgetIds(
                    ComponentName(applicationContext, DepartureWidgetProvider::class.java)
                )
                if (ids.isNotEmpty()) {
                    DepartureWidgetProvider.updateWidgets(applicationContext, manager, ids, forceRefresh = true)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "widget_refresh_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun triggerImmediate(context: Context, widgetId: Int? = null) {
            val data = if (widgetId != null && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                workDataOf(DepartureWidgetProvider.EXTRA_WIDGET_ID to widgetId)
            } else {
                workDataOf()
            }

            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(data)
                .build()

            val uniqueName = if (widgetId != null) "widget_refresh_immediate_$widgetId" else "widget_refresh_immediate_all"
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
