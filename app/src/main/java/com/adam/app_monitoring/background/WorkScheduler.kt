package com.adam.app_monitoring.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.adam.app_monitoring.data.SettingsStore
import java.util.concurrent.TimeUnit

object WorkScheduler {
    const val PERIODIC_WORK_NAME = "traffic_periodic_refresh"
    const val BOOT_WORK_NAME = "traffic_boot_refresh"

    fun ensurePeriodic(context: Context) {
        val settings = SettingsStore(context).read()
        val workManager = WorkManager.getInstance(context)
        if (!settings.backgroundEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<TrafficRefreshWorker>(
            settings.updateInterval.minutes,
            TimeUnit.MINUTES
        )
            .setConstraints(buildConstraints(settings.requireBatteryNotLow))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.MINUTES
            )
            .addTag(PERIODIC_WORK_NAME)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueBootRefresh(context: Context) {
        val settings = SettingsStore(context).read()
        if (!settings.backgroundEnabled || !settings.refreshAfterBoot) return
        val request = OneTimeWorkRequestBuilder<TrafficRefreshWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(TrafficRefreshWorker.KEY_FROM_BOOT, true)
                    .build()
            )
            .setConstraints(buildConstraints(settings.requireBatteryNotLow))
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.MINUTES
            )
            .addTag(BOOT_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BOOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun buildConstraints(requireBatteryNotLow: Boolean): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(requireBatteryNotLow)
            .setRequiresCharging(false)
            .build()
}
