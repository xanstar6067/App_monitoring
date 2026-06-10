package com.adam.app_monitoring.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adam.app_monitoring.MainActivity
import com.adam.app_monitoring.R
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.util.ByteFormatter
import com.adam.app_monitoring.data.ServiceLocator

class TrafficWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedulePeriodicRefresh(context)
        enqueueRefresh(context)
    }

    override fun onDisabled(context: Context) {
        cancelPeriodicRefresh(context)
        WorkManager.getInstance(context).cancelUniqueWork(WIDGET_REFRESH_WORK_NAME)
        super.onDisabled(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        renderCached(context, appWidgetManager, appWidgetIds)
        schedulePeriodicRefresh(context)
        enqueueRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                renderLoading(context)
                enqueueRefresh(context)
            }
            ACTION_PERIODIC_REFRESH -> enqueueRefresh(context)
        }
    }

    companion object {
        private const val ACTION_REFRESH =
            "com.adam.app_monitoring.widget.action.REFRESH"
        private const val ACTION_PERIODIC_REFRESH =
            "com.adam.app_monitoring.widget.action.PERIODIC_REFRESH"
        private const val WIDGET_REFRESH_WORK_NAME = "traffic_widget_refresh"
        private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TrafficWidgetProvider::class.java)
            )
            renderCached(context, manager, ids)
        }

        fun ensurePeriodicRefreshIfActive(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TrafficWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) schedulePeriodicRefresh(context)
        }

        private fun enqueueRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrafficWidgetRefreshWorker>()
                .addTag(WIDGET_REFRESH_WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WIDGET_REFRESH_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun renderLoading(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TrafficWidgetProvider::class.java)
            )
            ids.forEach { id ->
                manager.updateAppWidget(id, buildViews(context, loading = true))
            }
        }

        private fun renderCached(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray
        ) {
            if (ids.isEmpty()) return
            val snapshot = ServiceLocator.from(context).repository.loadCached(TrafficPeriod.TODAY)
            val topApps = snapshot?.apps
                .orEmpty()
                .filter { it.todayUsage.totalBytes > 0 }
                .sortedByDescending { it.todayUsage.totalBytes }
                .take(3)
            val totalBytes = snapshot?.todayTotalUsage?.totalBytes ?: 0
            ids.forEach { id ->
                manager.updateAppWidget(
                    id,
                    buildViews(
                        context = context,
                        totalBytes = totalBytes,
                        topApps = topApps,
                        hasData = snapshot != null
                    )
                )
            }
        }

        private fun buildViews(
            context: Context,
            totalBytes: Long = 0,
            topApps: List<AppTraffic> = emptyList(),
            hasData: Boolean = false,
            loading: Boolean = false
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_traffic)
            views.setTextViewText(
                R.id.widget_total,
                when {
                    loading -> context.getString(R.string.widget_loading)
                    hasData -> ByteFormatter.compact(totalBytes)
                    else -> context.getString(R.string.widget_no_data)
                }
            )

            val slots = listOf(
                WidgetSlot(R.id.widget_app_1, R.id.widget_app_1_icon, R.id.widget_app_1_usage),
                WidgetSlot(R.id.widget_app_2, R.id.widget_app_2_icon, R.id.widget_app_2_usage),
                WidgetSlot(R.id.widget_app_3, R.id.widget_app_3_icon, R.id.widget_app_3_usage)
            )
            slots.forEachIndexed { index, slot ->
                bindApp(context, views, slot, topApps.getOrNull(index))
            }

            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(context))
            views.setContentDescription(
                R.id.widget_refresh,
                context.getString(R.string.widget_refresh)
            )
            return views
        }

        private fun bindApp(
            context: Context,
            views: RemoteViews,
            slot: WidgetSlot,
            appTraffic: AppTraffic?
        ) {
            if (appTraffic == null) {
                views.setViewVisibility(slot.containerId, View.INVISIBLE)
                return
            }
            views.setViewVisibility(slot.containerId, View.VISIBLE)
            val app = appTraffic.app
            val icon: Bitmap? = ServiceLocator.from(context).icons.load(
                packageName = app.packageName,
                cachePath = app.iconCachePath
            )
            if (icon != null) {
                views.setImageViewBitmap(slot.iconId, icon)
            } else {
                views.setImageViewResource(slot.iconId, R.drawable.ic_widget_app)
            }
            val usage = ByteFormatter.compact(appTraffic.todayUsage.totalBytes)
            views.setTextViewText(slot.usageId, usage)
            views.setContentDescription(
                slot.containerId,
                context.getString(R.string.widget_app_description, app.appName, usage)
            )
        }

        private fun openAppPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun refreshPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, TrafficWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun periodicPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, TrafficWidgetProvider::class.java)
                    .setAction(ACTION_PERIODIC_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun schedulePeriodicRefresh(context: Context) {
            context.getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + REFRESH_INTERVAL_MS,
                REFRESH_INTERVAL_MS,
                periodicPendingIntent(context)
            )
        }

        private fun cancelPeriodicRefresh(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                .cancel(periodicPendingIntent(context))
        }
    }
}

private data class WidgetSlot(
    val containerId: Int,
    val iconId: Int,
    val usageId: Int
)
