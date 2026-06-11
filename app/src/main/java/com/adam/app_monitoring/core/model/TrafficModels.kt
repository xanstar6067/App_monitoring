package com.adam.app_monitoring.core.model

enum class TrafficPeriod {
    TODAY,
    MONTH
}

enum class NetworkMode {
    ALL,
    WIFI,
    MOBILE
}

enum class SortMode {
    TODAY,
    PERIOD,
    NAME,
    WIFI,
    MOBILE
}

data class TrafficUsage(
    val wifiRxBytes: Long = 0,
    val wifiTxBytes: Long = 0,
    val mobileRxBytes: Long = 0,
    val mobileTxBytes: Long = 0
) {
    val wifiBytes: Long get() = wifiRxBytes + wifiTxBytes
    val mobileBytes: Long get() = mobileRxBytes + mobileTxBytes
    val rxBytes: Long get() = wifiRxBytes + mobileRxBytes
    val txBytes: Long get() = wifiTxBytes + mobileTxBytes
    val totalBytes: Long get() = wifiBytes + mobileBytes

    operator fun plus(other: TrafficUsage) = TrafficUsage(
        wifiRxBytes = wifiRxBytes + other.wifiRxBytes,
        wifiTxBytes = wifiTxBytes + other.wifiTxBytes,
        mobileRxBytes = mobileRxBytes + other.mobileRxBytes,
        mobileTxBytes = mobileTxBytes + other.mobileTxBytes
    )

    fun bytesFor(mode: NetworkMode): Long = when (mode) {
        NetworkMode.ALL -> totalBytes
        NetworkMode.WIFI -> wifiBytes
        NetworkMode.MOBILE -> mobileBytes
    }
}

data class AppRecord(
    val packageName: String,
    val uid: Int,
    val appName: String,
    val iconCachePath: String?,
    val isSystemApp: Boolean,
    val isRemoved: Boolean = false,
    val lastSeenAt: Long
)

data class AppTraffic(
    val app: AppRecord,
    val periodUsage: TrafficUsage,
    val todayUsage: TrafficUsage
)

data class ChartPoint(
    val bucketStart: Long,
    val label: String,
    val wifiBytes: Long,
    val mobileBytes: Long
) {
    fun bytesFor(mode: NetworkMode): Long = when (mode) {
        NetworkMode.ALL -> wifiBytes + mobileBytes
        NetworkMode.WIFI -> wifiBytes
        NetworkMode.MOBILE -> mobileBytes
    }
}

data class TrafficSnapshot(
    val period: TrafficPeriod,
    val periodStart: Long,
    val periodEnd: Long,
    val apps: List<AppTraffic>,
    val chart: List<ChartPoint>,
    val calculatedAt: Long
) {
    val totalUsage: TrafficUsage = apps.fold(TrafficUsage()) { total, item ->
        total + item.periodUsage
    }
    val todayTotalUsage: TrafficUsage = apps.fold(TrafficUsage()) { total, item ->
        total + item.todayUsage
    }

    companion object {
        fun empty(period: TrafficPeriod) = TrafficSnapshot(
            period = period,
            periodStart = 0,
            periodEnd = 0,
            apps = emptyList(),
            chart = emptyList(),
            calculatedAt = 0
        )
    }
}

data class PermissionState(
    val usageAccessGranted: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val notificationsGranted: Boolean = true,
    val exactAlarmsGranted: Boolean = true
)
