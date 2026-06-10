package com.adam.app_monitoring.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.data.ServiceLocator
import kotlinx.coroutines.CancellationException

class TrafficWidgetRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            ServiceLocator.from(applicationContext).repository.refreshSelected(
                period = TrafficPeriod.TODAY
            )
            TrafficWidgetProvider.updateAll(applicationContext)
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SecurityException) {
            TrafficWidgetProvider.updateAll(applicationContext)
            Result.success()
        } catch (_: Exception) {
            TrafficWidgetProvider.updateAll(applicationContext)
            if (runAttemptCount == 0) Result.retry() else Result.success()
        }
    }
}
