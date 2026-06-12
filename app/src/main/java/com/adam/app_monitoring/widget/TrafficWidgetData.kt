package com.adam.app_monitoring.widget

import android.content.Context
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.ChartPoint
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.ByteFormatter
import com.adam.app_monitoring.core.util.ByteUnitPreference
import com.adam.app_monitoring.core.util.NetworkSpeedFormatter
import com.adam.app_monitoring.core.util.TrafficLimitBalance
import com.adam.app_monitoring.data.NetworkSpeedSnapshot
import com.adam.app_monitoring.data.NetworkSpeedSnapshotStore
import com.adam.app_monitoring.data.ServiceLocator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class WidgetTrafficData(
    val today: TrafficUsage,
    val month: TrafficUsage,
    val mobileRemainingBytes: Long?,
    val mobileLimitBytes: Long,
    val todayApps: List<AppTraffic>,
    val monthApps: List<AppTraffic>,
    val todayChart: List<ChartPoint>,
    val monthChart: List<ChartPoint>,
    val periodLabel: String,
    val monthLabel: String,
    val monthYearLabel: String,
    val units: ByteUnitPreference,
    val speed: NetworkSpeedSnapshot?,
    val speedEnabled: Boolean,
    val hasTodayData: Boolean,
    val hasMonthData: Boolean
) {
    val balanceUsedBytes: Long?
        get() = mobileRemainingBytes?.let {
            (mobileLimitBytes - it).coerceAtLeast(0)
        }

    val balanceProgress: Int
        get() {
            if (mobileLimitBytes <= 0) return 0
            val used = balanceUsedBytes ?: month.mobileBytes
            return ((used.toDouble() / mobileLimitBytes.toDouble()) * PROGRESS_MAX)
                .toInt()
                .coerceIn(0, PROGRESS_MAX)
        }

    fun formatBytes(bytes: Long): String = ByteFormatter.format(bytes, units)

    fun formatRemaining(): String =
        mobileRemainingBytes?.let(::formatBytes) ?: "—"

    fun formatSpeed(bytesPerSecond: Long): String =
        NetworkSpeedFormatter.format(bytesPerSecond)

    companion object {
        const val PROGRESS_MAX = 1_000
    }
}

internal object TrafficWidgetDataSource {
    private val russianLocale = Locale.forLanguageTag("ru-RU")
    private val monthFormatter = DateTimeFormatter.ofPattern("LLLL", russianLocale)
    private val monthYearFormatter = DateTimeFormatter.ofPattern("LLLL yyyy", russianLocale)
    private val periodMonthFormatter = DateTimeFormatter.ofPattern("MMMM", russianLocale)

    fun load(context: Context): WidgetTrafficData {
        val services = ServiceLocator.from(context)
        val todaySnapshot = services.repository.loadCached(TrafficPeriod.TODAY)
        val monthSnapshot = services.repository.loadCached(TrafficPeriod.MONTH)
        val settings = services.settings.read()
        val today = LocalDate.now()
        val periodMonth = today.format(periodMonthFormatter)
        val periodLabel = if (today.dayOfMonth == 1) {
            "1 $periodMonth"
        } else {
            "1–${today.dayOfMonth} $periodMonth"
        }
        val remaining = settings.cachedTrafficRemainingBytes.takeIf { it >= 0 }
        val limitBytes = settings.monthlyTrafficLimitMb.toLong() *
            TrafficLimitBalance.BYTES_PER_MB

        return WidgetTrafficData(
            today = todaySnapshot?.todayTotalUsage ?: TrafficUsage(),
            month = monthSnapshot?.totalUsage ?: TrafficUsage(),
            mobileRemainingBytes = remaining,
            mobileLimitBytes = limitBytes,
            todayApps = todaySnapshot?.apps.orEmpty()
                .filter { it.todayUsage.totalBytes > 0 }
                .sortedByDescending { it.todayUsage.totalBytes },
            monthApps = monthSnapshot?.apps.orEmpty()
                .filter { it.periodUsage.totalBytes > 0 }
                .sortedByDescending { it.periodUsage.totalBytes },
            todayChart = todaySnapshot?.chart.orEmpty(),
            monthChart = monthSnapshot?.chart.orEmpty(),
            periodLabel = periodLabel,
            monthLabel = today.format(monthFormatter).uppercase(russianLocale),
            monthYearLabel = today.format(monthYearFormatter)
                .replaceFirstChar { it.uppercase(russianLocale) },
            units = settings.units,
            speed = NetworkSpeedSnapshotStore.readFresh(context),
            speedEnabled = settings.speedNotificationEnabled,
            hasTodayData = todaySnapshot != null,
            hasMonthData = monthSnapshot != null
        )
    }
}
