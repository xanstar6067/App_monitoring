package com.adam.app_monitoring.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.data.ServiceLocator
import com.adam.app_monitoring.data.TrafficBalanceStore
import kotlinx.coroutines.CancellationException

class TrafficWidgetRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(LOG_TAG, "Widget refresh worker started")
        return try {
            val services = ServiceLocator.from(applicationContext)
            services.repository.refreshSelected(
                period = TrafficPeriod.TODAY,
                includeCharts = false
            )
            Log.i(LOG_TAG, "Today traffic refreshed in ${elapsed(startedAt)} ms")
            TrafficBalanceStore.refresh(services)
            Log.i(LOG_TAG, "Mobile balance refreshed in ${elapsed(startedAt)} ms")
            TrafficWidgetProvider.updateAll(applicationContext)
            Log.i(LOG_TAG, "Widget refresh finished in ${elapsed(startedAt)} ms")
            Result.success()
        } catch (cancellation: CancellationException) {
            Log.w(LOG_TAG, "Widget refresh cancelled after ${elapsed(startedAt)} ms")
            throw cancellation
        } catch (error: SecurityException) {
            Log.e(LOG_TAG, "Widget refresh has no usage access", error)
            TrafficWidgetProvider.updateAll(applicationContext)
            Result.success()
        } catch (error: Exception) {
            Log.e(
                LOG_TAG,
                "Widget refresh failed after ${elapsed(startedAt)} ms",
                error
            )
            TrafficWidgetProvider.updateAll(applicationContext)
            Result.success()
        }
    }

    private fun elapsed(startedAt: Long): Long =
        SystemClock.elapsedRealtime() - startedAt

    private companion object {
        const val LOG_TAG = "TrafficWidget"
    }
}
