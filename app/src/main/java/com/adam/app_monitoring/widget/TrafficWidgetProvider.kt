package com.adam.app_monitoring.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adam.app_monitoring.MainActivity
import com.adam.app_monitoring.data.ServiceLocator

private const val LOG_TAG = "TrafficWidget"

class TrafficWidgetProvider : BaseTrafficWidgetProvider(WidgetVariant.WIDE_CURRENT) {
    companion object {
        internal const val ACTION_REFRESH =
            "com.adam.app_monitoring.widget.action.REFRESH"
        internal const val ACTION_PERIODIC_REFRESH =
            "com.adam.app_monitoring.widget.action.PERIODIC_REFRESH"
        internal const val ACTION_REFRESH_VISUAL_TIMEOUT =
            "com.adam.app_monitoring.widget.action.REFRESH_VISUAL_TIMEOUT"

        fun updateAll(context: Context) = TrafficWidgetCoordinator.updateAll(context)

        fun updateSpeedWidgets(context: Context) =
            TrafficWidgetCoordinator.updateSpeedWidgets(context)

        fun hasSpeedWidgets(context: Context): Boolean =
            TrafficWidgetCoordinator.hasSpeedWidgets(context)

        fun ensurePeriodicRefreshIfActive(context: Context) =
            TrafficWidgetCoordinator.ensurePeriodicRefreshIfActive(context)

        fun reschedulePeriodicRefreshIfActive(context: Context) =
            TrafficWidgetCoordinator.reschedulePeriodicRefreshIfActive(context)

        internal fun openAppPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        internal fun refreshPendingIntent(
            context: Context,
            providerClass: Class<out AppWidgetProvider>
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, providerClass).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}

class CompactDayWidgetProvider :
    BaseTrafficWidgetProvider(WidgetVariant.COMPACT_DAY)

class CompactSummaryWidgetProvider :
    BaseTrafficWidgetProvider(WidgetVariant.COMPACT_SUMMARY)

class CompactProgressWidgetProvider :
    BaseTrafficWidgetProvider(WidgetVariant.COMPACT_PROGRESS)

class CompactSpeedWidgetProvider :
    BaseTrafficWidgetProvider(WidgetVariant.COMPACT_SPEED)

class LargeWeekWidgetProvider :
    BaseTrafficWidgetProvider(WidgetVariant.LARGE_WEEK)

class LargeHourlyWidgetProvider :
    BaseTrafficWidgetProvider(WidgetVariant.LARGE_HOURLY)

class LargeMonthWidgetProvider :
    BaseTrafficWidgetProvider(WidgetVariant.LARGE_MONTH)

abstract class BaseTrafficWidgetProvider(
    private val variant: WidgetVariant
) : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TrafficWidgetCoordinator.onWidgetEnabled(context)
    }

    override fun onDisabled(context: Context) {
        TrafficWidgetCoordinator.onWidgetDisabled(context)
        super.onDisabled(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        TrafficWidgetCoordinator.render(
            context = context,
            manager = appWidgetManager,
            variant = variant,
            ids = appWidgetIds
        )
        TrafficWidgetCoordinator.ensurePeriodicRefreshIfActive(context)
        TrafficWidgetCoordinator.enqueueRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            TrafficWidgetProvider.ACTION_REFRESH -> {
                Log.i(LOG_TAG, "Refresh click received by ${javaClass.simpleName}")
                TrafficWidgetCoordinator.refreshNow(context)
            }
            TrafficWidgetProvider.ACTION_PERIODIC_REFRESH ->
                TrafficWidgetCoordinator.enqueueRefresh(context)
            TrafficWidgetProvider.ACTION_REFRESH_VISUAL_TIMEOUT ->
                TrafficWidgetCoordinator.updateAll(context)
        }
    }
}

enum class WidgetVariant {
    WIDE_CURRENT,
    COMPACT_DAY,
    COMPACT_SUMMARY,
    COMPACT_PROGRESS,
    COMPACT_SPEED,
    LARGE_WEEK,
    LARGE_HOURLY,
    LARGE_MONTH
}

private data class WidgetProviderSpec(
    val providerClass: Class<out AppWidgetProvider>,
    val variant: WidgetVariant
)

internal object TrafficWidgetCoordinator {
    private const val WIDGET_REFRESH_WORK_NAME = "traffic_widget_refresh_v2"
    private const val LEGACY_WIDGET_REFRESH_WORK_NAME = "traffic_widget_refresh"

    private val providers = listOf(
        WidgetProviderSpec(TrafficWidgetProvider::class.java, WidgetVariant.WIDE_CURRENT),
        WidgetProviderSpec(CompactDayWidgetProvider::class.java, WidgetVariant.COMPACT_DAY),
        WidgetProviderSpec(
            CompactSummaryWidgetProvider::class.java,
            WidgetVariant.COMPACT_SUMMARY
        ),
        WidgetProviderSpec(
            CompactProgressWidgetProvider::class.java,
            WidgetVariant.COMPACT_PROGRESS
        ),
        WidgetProviderSpec(CompactSpeedWidgetProvider::class.java, WidgetVariant.COMPACT_SPEED),
        WidgetProviderSpec(LargeWeekWidgetProvider::class.java, WidgetVariant.LARGE_WEEK),
        WidgetProviderSpec(LargeHourlyWidgetProvider::class.java, WidgetVariant.LARGE_HOURLY),
        WidgetProviderSpec(LargeMonthWidgetProvider::class.java, WidgetVariant.LARGE_MONTH)
    )

    fun onWidgetEnabled(context: Context) {
        cancelLegacyRefresh(context)
        schedulePeriodicRefresh(context)
        enqueueRefresh(context)
    }

    fun onWidgetDisabled(context: Context) {
        if (hasActiveWidgets(context)) return
        cancelPeriodicRefresh(context)
        WorkManager.getInstance(context).cancelUniqueWork(WIDGET_REFRESH_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WIDGET_REFRESH_WORK_NAME)
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val data = TrafficWidgetDataSource.load(context)
        providers.forEach { spec ->
            val ids = idsFor(context, manager, spec)
            if (ids.isNotEmpty()) {
                render(context, manager, spec.variant, ids, data)
            }
        }
    }

    fun refreshNow(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val data = TrafficWidgetDataSource.load(context)
        providers.forEach { spec ->
            val ids = idsFor(context, manager, spec)
            ids.forEach { id ->
                manager.updateAppWidget(
                    id,
                    TrafficWidgetRenderer.renderRefreshing(context, spec.variant, data)
                )
            }
        }
        scheduleRefreshVisualTimeout(context)
        cancelLegacyRefresh(context)
        enqueueRefresh(context)
    }

    fun updateSpeedWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val spec = providers.first { it.variant == WidgetVariant.COMPACT_SPEED }
        val ids = idsFor(context, manager, spec)
        if (ids.isEmpty()) return
        render(
            context = context,
            manager = manager,
            variant = spec.variant,
            ids = ids,
            data = TrafficWidgetDataSource.load(context)
        )
    }

    fun hasSpeedWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val spec = providers.first { it.variant == WidgetVariant.COMPACT_SPEED }
        return idsFor(context, manager, spec).isNotEmpty()
    }

    fun render(
        context: Context,
        manager: AppWidgetManager,
        variant: WidgetVariant,
        ids: IntArray,
        data: WidgetTrafficData = TrafficWidgetDataSource.load(context)
    ) {
        ids.forEach { id ->
            manager.updateAppWidget(
                id,
                TrafficWidgetRenderer.render(context, variant, data)
            )
        }
    }

    fun enqueueRefresh(context: Context) {
        Log.i(LOG_TAG, "Enqueue widget refresh work")
        val request = OneTimeWorkRequestBuilder<TrafficWidgetRefreshWorker>()
            .addTag(WIDGET_REFRESH_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WIDGET_REFRESH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun cancelLegacyRefresh(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(LEGACY_WIDGET_REFRESH_WORK_NAME)
    }

    fun ensurePeriodicRefreshIfActive(context: Context) {
        if (hasActiveWidgets(context)) schedulePeriodicRefresh(context)
    }

    fun reschedulePeriodicRefreshIfActive(context: Context) {
        if (!hasActiveWidgets(context)) return
        cancelPeriodicRefresh(context)
        schedulePeriodicRefresh(context)
    }

    private fun hasActiveWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return providers.any { idsFor(context, manager, it).isNotEmpty() }
    }

    private fun idsFor(
        context: Context,
        manager: AppWidgetManager,
        spec: WidgetProviderSpec
    ): IntArray = manager.getAppWidgetIds(ComponentName(context, spec.providerClass))

    private fun periodicPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, TrafficWidgetProvider::class.java)
                .setAction(TrafficWidgetProvider.ACTION_PERIODIC_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun visualTimeoutPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, TrafficWidgetProvider::class.java)
                .setAction(TrafficWidgetProvider.ACTION_REFRESH_VISUAL_TIMEOUT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun scheduleRefreshVisualTimeout(context: Context) {
        context.getSystemService(AlarmManager::class.java).set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + REFRESH_VISUAL_TIMEOUT_MS,
            visualTimeoutPendingIntent(context)
        )
    }

    private fun schedulePeriodicRefresh(context: Context) {
        val intervalMillis = ServiceLocator.from(context)
            .settings
            .read()
            .widgetUpdateInterval
            .minutes * 60 * 1000L
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + intervalMillis,
            intervalMillis,
            periodicPendingIntent(context)
        )
    }

    private fun cancelPeriodicRefresh(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(periodicPendingIntent(context))
    }

    private const val REFRESH_VISUAL_TIMEOUT_MS = 30_000L
}
