package com.adam.app_monitoring.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adam.app_monitoring.data.SettingsStore
import com.adam.app_monitoring.widget.TrafficWidgetProvider

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        SettingsStore.recordDirectBootSignal(context, now)
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // WorkManager and the app database use credential-protected storage.
            return
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WorkScheduler.enqueueBootRefresh(context)
            WorkScheduler.ensurePeriodic(context)
            TrafficWidgetProvider.ensurePeriodicRefreshIfActive(context)
            NetworkSpeedService.sync(context)
        }
    }
}
