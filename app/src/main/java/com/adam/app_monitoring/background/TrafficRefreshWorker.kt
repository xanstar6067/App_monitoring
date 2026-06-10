package com.adam.app_monitoring.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adam.app_monitoring.data.ServiceLocator
import com.adam.app_monitoring.data.TrafficBalanceStore
import com.adam.app_monitoring.widget.TrafficWidgetProvider
import kotlinx.coroutines.CancellationException

class TrafficRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val services = ServiceLocator.from(applicationContext)
        val settings = services.settings.read()
        val fromBoot = inputData.getBoolean(KEY_FROM_BOOT, false)
        if (!settings.backgroundEnabled) return Result.success()
        if (fromBoot && !settings.refreshAfterBoot) return Result.success()

        return try {
            services.repository.refreshBackground(fromBoot = fromBoot)
            val remainingBytes = TrafficBalanceStore.refresh(services)
            TrafficWidgetProvider.updateAll(applicationContext)
            try {
                TrafficLimitNotifier.checkAndNotify(
                    applicationContext,
                    services,
                    remainingBytes
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A notification failure must not turn a successful statistics refresh into a retry.
            }
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SecurityException) {
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < MAX_IMMEDIATE_RETRIES) {
                Result.retry()
            } else {
                // The next periodic window is safer than an unbounded retry loop.
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_FROM_BOOT = "from_boot"
        private const val MAX_IMMEDIATE_RETRIES = 2
    }
}
