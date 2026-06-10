package com.adam.app_monitoring.widget

import android.content.Context
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
        return try {
            val services = ServiceLocator.from(applicationContext)
            services.repository.refreshSelected(
                period = TrafficPeriod.TODAY
            )
            TrafficBalanceStore.refresh(services)
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
