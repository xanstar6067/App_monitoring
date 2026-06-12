package com.adam.app_monitoring.widget

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.adam.app_monitoring.R
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.data.ServiceLocator
import java.util.Locale

internal object TrafficWidgetRenderer {
    fun render(
        context: Context,
        variant: WidgetVariant,
        data: WidgetTrafficData
    ): RemoteViews = when (variant) {
        WidgetVariant.WIDE_CURRENT -> renderWideCurrent(context, data)
        WidgetVariant.COMPACT_DAY -> renderCompactDay(context, data)
        WidgetVariant.COMPACT_SUMMARY -> renderCompactSummary(context, data)
        WidgetVariant.COMPACT_PROGRESS -> renderCompactProgress(context, data)
        WidgetVariant.COMPACT_SPEED -> renderCompactSpeed(context, data)
        WidgetVariant.LARGE_WEEK -> renderLargeWeek(context, data)
        WidgetVariant.LARGE_HOURLY -> renderLargeHourly(context, data)
        WidgetVariant.LARGE_MONTH -> renderLargeMonth(context, data)
    }

    fun renderRefreshing(
        context: Context,
        variant: WidgetVariant,
        data: WidgetTrafficData
    ): RemoteViews = render(context, variant, data).apply {
        setImageViewResource(R.id.widget_refresh, R.drawable.ic_widget_refreshing)
        setInt(
            R.id.widget_refresh,
            "setColorFilter",
            context.getColor(R.color.widget_gold)
        )
        setContentDescription(
            R.id.widget_refresh,
            context.getString(R.string.widget_refreshing)
        )
    }

    private fun renderWideCurrent(
        context: Context,
        data: WidgetTrafficData
    ): RemoteViews = baseViews(context, R.layout.widget_traffic, WidgetVariant.WIDE_CURRENT).apply {
        setTextViewText(
            R.id.widget_period_label,
            context.getString(R.string.widget_today_total_label)
        )
        setTextViewText(
            R.id.widget_today_total,
            bytesOrNoData(context, data, data.today.totalBytes, data.hasTodayData)
        )
        setTextViewText(
            R.id.widget_mobile_used,
            context.getString(
                R.string.widget_mobile_spent_value,
                data.periodLabel,
                data.formatBytes(data.month.mobileBytes)
            )
        )
        setTextViewText(
            R.id.widget_remaining,
            context.getString(
                R.string.widget_mobile_remaining_value,
                data.formatRemaining()
            )
        )
        bindIconApps(
            context = context,
            views = this,
            apps = data.todayApps.take(4),
            usage = AppUsage.TODAY
        )
    }

    private fun renderCompactDay(
        context: Context,
        data: WidgetTrafficData
    ): RemoteViews = baseViews(context, R.layout.widget_compact_day, WidgetVariant.COMPACT_DAY).apply {
        setTextViewText(
            R.id.compact_day_total,
            bytesOrNoData(context, data, data.today.totalBytes, data.hasTodayData)
        )
        setTextViewText(
            R.id.compact_day_split,
            context.getString(
                R.string.widget_mobile_wifi_split,
                data.formatBytes(data.today.mobileBytes),
                data.formatBytes(data.today.wifiBytes)
            )
        )
        setTextViewText(
            R.id.compact_day_mobile_balance,
            listOf(
                context.getString(
                    R.string.widget_mobile_spent_value,
                    data.periodLabel,
                    data.formatBytes(data.month.mobileBytes)
                ),
                context.getString(
                    R.string.widget_mobile_remaining_value,
                    data.formatRemaining()
                )
            ).joinToString(separator = "\n")
        )
    }

    private fun renderCompactSummary(
        context: Context,
        data: WidgetTrafficData
    ): RemoteViews = baseViews(
        context,
        R.layout.widget_compact_summary,
        WidgetVariant.COMPACT_SUMMARY
    ).apply {
        setTextViewText(
            R.id.compact_summary_today,
            bytesOrNoData(context, data, data.today.totalBytes, data.hasTodayData)
        )
        setTextViewText(
            R.id.compact_summary_period,
            bytesOrNoData(context, data, data.month.totalBytes, data.hasMonthData)
        )
        setTextViewText(
            R.id.compact_summary_period_label,
            data.periodLabel.uppercase(Locale.getDefault())
        )
        setTextViewText(
            R.id.compact_summary_mobile,
            context.getString(
                R.string.widget_mobile_used_remaining,
                data.formatBytes(data.month.mobileBytes),
                data.formatRemaining()
            )
        )
    }

    private fun renderCompactProgress(
        context: Context,
        data: WidgetTrafficData
    ): RemoteViews = baseViews(
        context,
        R.layout.widget_compact_progress,
        WidgetVariant.COMPACT_PROGRESS
    ).apply {
        setTextViewText(
            R.id.compact_progress_period,
            context.getString(
                R.string.widget_mobile_period,
                data.periodLabel.uppercase(Locale.getDefault())
            )
        )
        setTextViewText(
            R.id.compact_progress_mobile_used,
            bytesOrNoData(context, data, data.month.mobileBytes, data.hasMonthData)
        )
        setProgressBar(
            R.id.compact_progress_bar,
            WidgetTrafficData.PROGRESS_MAX,
            data.balanceProgress,
            false
        )
        setTextViewText(
            R.id.compact_progress_balance,
            context.getString(R.string.widget_remaining_mobile_value, data.formatRemaining())
        )
        setTextViewText(
            R.id.compact_progress_total,
            context.getString(
                R.string.widget_period_total_value,
                data.formatBytes(data.month.totalBytes)
            )
        )
    }

    private fun renderCompactSpeed(
        context: Context,
        data: WidgetTrafficData
    ): RemoteViews = baseViews(
        context,
        R.layout.widget_compact_speed,
        WidgetVariant.COMPACT_SPEED
    ).apply {
        val speed = data.speed
        setTextViewText(
            R.id.compact_speed_download,
            speed?.let { data.formatSpeed(it.downloadBytesPerSecond) } ?: "—"
        )
        setTextViewText(
            R.id.compact_speed_upload,
            speed?.let { data.formatSpeed(it.uploadBytesPerSecond) } ?: "—"
        )
        setTextViewText(
            R.id.compact_speed_status,
            when {
                speed != null -> context.getString(R.string.widget_speed_live)
                data.speedEnabled -> context.getString(R.string.widget_speed_waiting)
                else -> context.getString(R.string.widget_speed_disabled)
            }
        )
        setTextViewText(
            R.id.compact_speed_total,
            context.getString(
                R.string.widget_speed_traffic_summary,
                data.formatBytes(data.today.totalBytes),
                data.formatBytes(data.month.mobileBytes),
                data.formatRemaining()
            )
        )
    }

    private fun renderLargeWeek(
        context: Context,
        data: WidgetTrafficData
    ): RemoteViews = baseViews(context, R.layout.widget_large_week, WidgetVariant.LARGE_WEEK).apply {
        setTextViewText(
            R.id.large_week_today_total,
            bytesOrNoData(context, data, data.today.totalBytes, data.hasTodayData)
        )
        setTextViewText(
            R.id.large_week_today_split,
            context.getString(
                R.string.widget_mobile_wifi_split,
                data.formatBytes(data.today.mobileBytes),
                data.formatBytes(data.today.wifiBytes)
            )
        )
        setTextViewText(
            R.id.large_week_period_label,
            data.periodLabel.uppercase(Locale.getDefault())
        )
        setTextViewText(
            R.id.large_week_period_total,
            bytesOrNoData(context, data, data.month.totalBytes, data.hasMonthData)
        )
        setTextViewText(
            R.id.large_week_mobile_balance,
            context.getString(
                R.string.widget_mobile_used_remaining,
                data.formatBytes(data.month.mobileBytes),
                data.formatRemaining()
            )
        )
        setImageViewBitmap(
            R.id.large_week_chart,
            TrafficWidgetChartRenderer.weekly(data.monthChart)
        )
        bindLargeApps(
            context = context,
            views = this,
            apps = data.todayApps.take(3),
            usage = AppUsage.TODAY,
            slots = largeAppSlots.take(3)
        )
    }

    private fun renderLargeHourly(
        context: Context,
        data: WidgetTrafficData
    ): RemoteViews = baseViews(
        context,
        R.layout.widget_large_hourly,
        WidgetVariant.LARGE_HOURLY
    ).apply {
        setTextViewText(
            R.id.large_hourly_today_total,
            bytesOrNoData(context, data, data.today.totalBytes, data.hasTodayData)
        )
        setImageViewBitmap(
            R.id.large_hourly_chart,
            TrafficWidgetChartRenderer.hourly(data.todayChart)
        )
        setProgressBar(
            R.id.large_hourly_progress,
            WidgetTrafficData.PROGRESS_MAX,
            data.balanceProgress,
            false
        )
        setTextViewText(
            R.id.large_hourly_mobile,
            context.getString(
                R.string.widget_mobile_used_remaining,
                data.formatBytes(data.month.mobileBytes),
                data.formatRemaining()
            )
        )
        setTextViewText(
            R.id.large_hourly_total,
            context.getString(
                R.string.widget_period_total_wifi,
                data.formatBytes(data.month.totalBytes),
                data.formatBytes(data.month.wifiBytes)
            )
        )
        bindLargeApps(
            context = context,
            views = this,
            apps = data.todayApps.take(2),
            usage = AppUsage.TODAY,
            slots = largeAppSlots.take(2)
        )
    }

    private fun renderLargeMonth(
        context: Context,
        data: WidgetTrafficData
    ): RemoteViews = baseViews(
        context,
        R.layout.widget_large_month,
        WidgetVariant.LARGE_MONTH
    ).apply {
        setTextViewText(
            R.id.large_month_period_label,
            data.monthYearLabel.uppercase(Locale.getDefault())
        )
        setTextViewText(
            R.id.large_month_total,
            bytesOrNoData(context, data, data.month.totalBytes, data.hasMonthData)
        )
        setTextViewText(
            R.id.large_month_today,
            context.getString(
                R.string.widget_today_plus,
                data.formatBytes(data.today.totalBytes)
            )
        )
        setTextViewText(
            R.id.large_month_mobile,
            data.formatBytes(data.month.mobileBytes)
        )
        setTextViewText(
            R.id.large_month_remaining,
            context.getString(R.string.widget_remaining_mobile_value, data.formatRemaining())
        )
        setProgressBar(
            R.id.large_month_progress,
            WidgetTrafficData.PROGRESS_MAX,
            data.balanceProgress,
            false
        )
        setTextViewText(
            R.id.large_month_progress_caption,
            context.getString(
                R.string.widget_plan_used,
                data.balanceProgress / 10
            )
        )
        setTextViewText(
            R.id.large_month_wifi,
            context.getString(
                R.string.widget_period_wifi_value,
                data.formatBytes(data.month.wifiBytes)
            )
        )
        bindLargeApps(
            context = context,
            views = this,
            apps = data.monthApps.take(4),
            usage = AppUsage.PERIOD,
            slots = largeAppSlots
        )
    }

    private fun baseViews(
        context: Context,
        layoutId: Int,
        variant: WidgetVariant
    ): RemoteViews =
        RemoteViews(context.packageName, layoutId).apply {
            setBoolean(R.id.widget_refresh, "setEnabled", true)
            setImageViewResource(R.id.widget_refresh, R.drawable.ic_widget_refresh)
            setInt(
                R.id.widget_refresh,
                "setColorFilter",
                context.getColor(R.color.widget_accent)
            )
            setOnClickPendingIntent(
                R.id.widget_root,
                TrafficWidgetProvider.openAppPendingIntent(context)
            )
            setOnClickPendingIntent(
                R.id.widget_refresh,
                TrafficWidgetProvider.refreshPendingIntent(
                    context,
                    providerClassFor(variant)
                )
            )
            setContentDescription(
                R.id.widget_refresh,
                context.getString(R.string.widget_refresh)
            )
        }

    private fun providerClassFor(
        variant: WidgetVariant
    ): Class<out android.appwidget.AppWidgetProvider> = when (variant) {
        WidgetVariant.WIDE_CURRENT -> TrafficWidgetProvider::class.java
        WidgetVariant.COMPACT_DAY -> CompactDayWidgetProvider::class.java
        WidgetVariant.COMPACT_SUMMARY -> CompactSummaryWidgetProvider::class.java
        WidgetVariant.COMPACT_PROGRESS -> CompactProgressWidgetProvider::class.java
        WidgetVariant.COMPACT_SPEED -> CompactSpeedWidgetProvider::class.java
        WidgetVariant.LARGE_WEEK -> LargeWeekWidgetProvider::class.java
        WidgetVariant.LARGE_HOURLY -> LargeHourlyWidgetProvider::class.java
        WidgetVariant.LARGE_MONTH -> LargeMonthWidgetProvider::class.java
    }

    private fun bytesOrNoData(
        context: Context,
        data: WidgetTrafficData,
        bytes: Long,
        hasData: Boolean
    ): String = if (hasData) {
        data.formatBytes(bytes)
    } else {
        context.getString(R.string.widget_no_data)
    }

    private fun bindIconApps(
        context: Context,
        views: RemoteViews,
        apps: List<AppTraffic>,
        usage: AppUsage
    ) {
        iconAppSlots.forEachIndexed { index, slot ->
            val appTraffic = apps.getOrNull(index)
            if (appTraffic == null) {
                views.setViewVisibility(slot.containerId, View.INVISIBLE)
                return@forEachIndexed
            }
            views.setViewVisibility(slot.containerId, View.VISIBLE)
            bindIcon(context, views, slot.iconId, appTraffic)
            val bytes = usage.bytes(appTraffic)
            views.setTextViewText(slot.usageId, ByteFormatterForWidget.format(context, bytes))
            views.setContentDescription(
                slot.containerId,
                context.getString(
                    R.string.widget_app_description,
                    appTraffic.app.appName,
                    ByteFormatterForWidget.format(context, bytes)
                )
            )
        }
    }

    private fun bindLargeApps(
        context: Context,
        views: RemoteViews,
        apps: List<AppTraffic>,
        usage: AppUsage,
        slots: List<LargeAppSlot>
    ) {
        val maxBytes = apps.maxOfOrNull(usage::bytes)?.coerceAtLeast(1) ?: 1
        slots.forEachIndexed { index, slot ->
            val appTraffic = apps.getOrNull(index)
            if (appTraffic == null) {
                views.setViewVisibility(slot.containerId, View.GONE)
                return@forEachIndexed
            }
            val bytes = usage.bytes(appTraffic)
            views.setViewVisibility(slot.containerId, View.VISIBLE)
            bindIcon(context, views, slot.iconId, appTraffic)
            views.setTextViewText(slot.nameId, appTraffic.app.appName)
            views.setTextViewText(slot.usageId, ByteFormatterForWidget.format(context, bytes))
            views.setProgressBar(
                slot.progressId,
                WidgetTrafficData.PROGRESS_MAX,
                ((bytes.toDouble() / maxBytes.toDouble()) * WidgetTrafficData.PROGRESS_MAX)
                    .toInt()
                    .coerceIn(0, WidgetTrafficData.PROGRESS_MAX),
                false
            )
            views.setContentDescription(
                slot.containerId,
                context.getString(
                    R.string.widget_app_description,
                    appTraffic.app.appName,
                    ByteFormatterForWidget.format(context, bytes)
                )
            )
        }
    }

    private fun bindIcon(
        context: Context,
        views: RemoteViews,
        iconId: Int,
        appTraffic: AppTraffic
    ) {
        val app = appTraffic.app
        val icon: Bitmap? = ServiceLocator.from(context).icons.load(
            packageName = app.packageName,
            cachePath = app.iconCachePath
        )
        if (icon != null) {
            views.setImageViewBitmap(iconId, icon)
        } else {
            views.setImageViewResource(iconId, R.drawable.ic_widget_app)
        }
    }

    private enum class AppUsage {
        TODAY,
        PERIOD;

        fun bytes(app: AppTraffic): Long = when (this) {
            TODAY -> app.todayUsage.totalBytes
            PERIOD -> app.periodUsage.totalBytes
        }
    }

    private data class IconAppSlot(
        val containerId: Int,
        val iconId: Int,
        val usageId: Int
    )

    private data class LargeAppSlot(
        val containerId: Int,
        val iconId: Int,
        val nameId: Int,
        val progressId: Int,
        val usageId: Int
    )

    private val iconAppSlots = listOf(
        IconAppSlot(R.id.widget_app_1, R.id.widget_app_1_icon, R.id.widget_app_1_usage),
        IconAppSlot(R.id.widget_app_2, R.id.widget_app_2_icon, R.id.widget_app_2_usage),
        IconAppSlot(R.id.widget_app_3, R.id.widget_app_3_icon, R.id.widget_app_3_usage),
        IconAppSlot(R.id.widget_app_4, R.id.widget_app_4_icon, R.id.widget_app_4_usage)
    )

    private val largeAppSlots = listOf(
        LargeAppSlot(
            R.id.widget_large_app_1,
            R.id.widget_large_app_1_icon,
            R.id.widget_large_app_1_name,
            R.id.widget_large_app_1_progress,
            R.id.widget_large_app_1_usage
        ),
        LargeAppSlot(
            R.id.widget_large_app_2,
            R.id.widget_large_app_2_icon,
            R.id.widget_large_app_2_name,
            R.id.widget_large_app_2_progress,
            R.id.widget_large_app_2_usage
        ),
        LargeAppSlot(
            R.id.widget_large_app_3,
            R.id.widget_large_app_3_icon,
            R.id.widget_large_app_3_name,
            R.id.widget_large_app_3_progress,
            R.id.widget_large_app_3_usage
        ),
        LargeAppSlot(
            R.id.widget_large_app_4,
            R.id.widget_large_app_4_icon,
            R.id.widget_large_app_4_name,
            R.id.widget_large_app_4_progress,
            R.id.widget_large_app_4_usage
        )
    )
}

private object ByteFormatterForWidget {
    fun format(context: Context, bytes: Long): String {
        val units = ServiceLocator.from(context).settings.read().units
        return com.adam.app_monitoring.core.util.ByteFormatter.format(bytes, units)
    }
}
