package com.adam.app_monitoring.data

import android.app.usage.NetworkStats
import android.content.Context
import android.os.Process
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.AppRecord
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficSnapshot
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.TimeRange
import com.adam.app_monitoring.core.util.TimeRanges
import com.adam.app_monitoring.core.util.ChartBuckets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId

class UsageAccessMissingException : IllegalStateException(
    "Нет доступа к статистике использования"
)

class TrafficRepository(
    private val database: TrafficDatabase,
    private val settingsStore: SettingsStore,
    private val permissionChecker: PermissionChecker,
    private val appCatalog: AppCatalog,
    private val networkStatsReader: NetworkStatsReader
) {
    private val refreshMutex = Mutex()

    fun loadCached(period: TrafficPeriod): TrafficSnapshot? {
        val range = TimeRanges.forPeriod(period)
        return database.loadSnapshot(
            period = period,
            periodStart = range.startMillis,
            todayDate = TimeRanges.todayKey()
        )
    }

    suspend fun refreshSelected(
        period: TrafficPeriod,
        forceAppScan: Boolean = false,
        includeCharts: Boolean = true
    ): TrafficSnapshot = withContext(Dispatchers.IO) {
        withTimeout(USER_REFRESH_TIMEOUT_MS) {
            refreshMutex.withLock {
                requireUsageAccess()
                var apps = appCatalog.load(forceScan = forceAppScan)
                val now = System.currentTimeMillis()
                val todayRange = TimeRanges.forPeriod(TrafficPeriod.TODAY, now)
                val todayStats = networkStatsReader.read(
                    startMillis = todayRange.startMillis,
                    endMillis = todayRange.endMillis,
                    period = TrafficPeriod.TODAY,
                    includeChart = includeCharts
                )
                apps = refreshCatalogIfMissingApps(apps, todayStats.byUid.keys)
                val todayRows = buildRows(apps, todayStats.byUid, now)
                database.saveDaily(TimeRanges.todayKey(now), todayRows, now)
                database.savePeriod(
                    periodStart = todayRange.startMillis,
                    periodEnd = todayRange.endMillis,
                    rows = todayRows,
                    chart = todayStats.chart.takeIf { includeCharts },
                    calculatedAt = now
                )

                if (period != TrafficPeriod.TODAY) {
                    val monthRange = TimeRanges.forPeriod(period, now)
                    val monthStats = networkStatsReader.read(
                        startMillis = monthRange.startMillis,
                        endMillis = monthRange.endMillis,
                        period = period,
                        includeChart = includeCharts
                    )
                    apps = refreshCatalogIfMissingApps(apps, monthStats.byUid.keys)
                    val todayUsage = todayStats.byUid.values.fold(TrafficUsage()) {
                            total,
                            usage ->
                        total + usage
                    }
                    val monthUsage = monthStats.byUid.values.fold(TrafficUsage()) {
                            total,
                            usage ->
                        total + usage
                    }
                    database.savePeriod(
                        periodStart = monthRange.startMillis,
                        periodEnd = monthRange.endMillis,
                        rows = buildRows(apps, monthStats.byUid, now),
                        chart = if (includeCharts && period == TrafficPeriod.MONTH) {
                            ChartBuckets.reconcileLatestPoint(
                                points = monthStats.chart,
                                wifiTotalBytes = monthUsage.wifiBytes,
                                mobileTotalBytes = monthUsage.mobileBytes,
                                latestWifiBytes = todayUsage.wifiBytes,
                                latestMobileBytes = todayUsage.mobileBytes
                            )
                        } else if (includeCharts) {
                            monthStats.chart
                        } else {
                            null
                        },
                        calculatedAt = now
                    )
                }
                settingsStore.markRefreshSuccess(now, TimeRanges.todayKey(now))
                loadCached(period) ?: TrafficSnapshot.empty(period)
            }
        }
    }

    suspend fun refreshBackground(fromBoot: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(BACKGROUND_REFRESH_TIMEOUT_MS) {
                    refreshMutex.withLock {
                        if (!permissionChecker.hasUsageAccess()) return@withLock false
                        var apps = appCatalog.load()
                        val now = System.currentTimeMillis()
                        val todayKey = TimeRanges.todayKey(now)
                        val todayRange = TimeRanges.forPeriod(TrafficPeriod.TODAY, now)
                        val todayStats = networkStatsReader.read(
                            startMillis = todayRange.startMillis,
                            endMillis = todayRange.endMillis,
                            period = TrafficPeriod.TODAY,
                            includeChart = false
                        )
                        apps = refreshCatalogIfMissingApps(apps, todayStats.byUid.keys)
                        val todayRows = buildRows(apps, todayStats.byUid, now)
                        database.saveDaily(todayKey, todayRows, now)
                        database.savePeriod(
                            periodStart = todayRange.startMillis,
                            periodEnd = todayRange.endMillis,
                            rows = todayRows,
                            chart = null,
                            calculatedAt = now
                        )

                        if (settingsStore.lastSuccessfulDate() != todayKey) {
                            closePreviousDay(apps, now)
                        }
                        settingsStore.markRefreshSuccess(now, todayKey)
                        if (fromBoot) settingsStore.markBootRefreshSuccess(now)
                        true
                    }
                }
            } catch (error: Exception) {
                settingsStore.markRefreshError(
                    error.message ?: error.javaClass.simpleName
                )
                throw error
            }
        }

    fun clearCache() = database.clearTrafficCache()

    suspend fun loadAppsForInterval(
        startMillis: Long,
        endMillis: Long
    ): List<AppTraffic> = withContext(Dispatchers.IO) {
        withTimeout(INTERVAL_LOAD_TIMEOUT_MS) {
            refreshMutex.withLock {
                requireUsageAccess()
                val stats = networkStatsReader.read(
                    startMillis = startMillis,
                    endMillis = endMillis,
                    period = TrafficPeriod.TODAY,
                    includeChart = false
                )
                val apps = refreshCatalogIfMissingApps(
                    apps = appCatalog.load(),
                    trafficUids = stats.byUid.keys
                )
                buildRows(apps, stats.byUid, System.currentTimeMillis()).map { row ->
                    AppTraffic(
                        app = row.app,
                        periodUsage = row.usage,
                        todayUsage = row.usage
                    )
                }
            }
        }
    }

    suspend fun loadUsageForRange(
        startMillis: Long,
        endMillis: Long
    ): TrafficUsage = withContext(Dispatchers.IO) {
        withTimeout(INTERVAL_LOAD_TIMEOUT_MS) {
            refreshMutex.withLock {
                requireUsageAccess()
                networkStatsReader.read(
                    startMillis = startMillis,
                    endMillis = endMillis,
                    period = TrafficPeriod.MONTH,
                    includeChart = false
                ).byUid.values.fold(TrafficUsage()) { total, usage -> total + usage }
            }
        }
    }

    private suspend fun closePreviousDay(apps: List<AppRecord>, now: Long) {
        val zone = ZoneId.systemDefault()
        val yesterday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1)
        val range = TimeRanges.forDate(yesterday, zone)
        val stats = networkStatsReader.read(
            startMillis = range.startMillis,
            endMillis = range.endMillis,
            period = TrafficPeriod.TODAY,
            includeChart = false
        )
        val dayApps = refreshCatalogIfMissingApps(apps, stats.byUid.keys)
        database.saveDaily(
            date = yesterday.toString(),
            rows = buildRows(dayApps, stats.byUid, now),
            calculatedAt = now
        )
    }

    private fun requireUsageAccess() {
        if (!permissionChecker.hasUsageAccess()) throw UsageAccessMissingException()
    }

    private fun buildRows(
        apps: List<AppRecord>,
        byUid: Map<Int, TrafficUsage>,
        calculatedAt: Long
    ): List<TrafficRow> {
        val appsByUid = appsByUid(apps)
        val allUids = appsByUid.keys + byUid.keys
        return allUids.map { uid ->
            val app = specialUidRecord(uid, calculatedAt)
                ?: appsByUid[uid]
                ?: AppRecord(
                packageName = "unknown.uid.$uid",
                uid = uid,
                appName = "Удалённое или неизвестное приложение",
                iconCachePath = null,
                isSystemApp = false,
                isRemoved = true,
                lastSeenAt = calculatedAt
            )
            TrafficRow(app, byUid[uid] ?: TrafficUsage())
        }
    }

    private fun refreshCatalogIfMissingApps(
        apps: List<AppRecord>,
        trafficUids: Set<Int>
    ): List<AppRecord> {
        val appsByUid = appsByUid(apps)
        val hasMissingApp = trafficUids.any { uid ->
            specialUidRecord(uid, calculatedAt = 0) == null &&
                appsByUid[uid]?.isResolvedApp() != true
        }
        return if (hasMissingApp) appCatalog.load(forceScan = true) else apps
    }

    private fun appsByUid(apps: List<AppRecord>): Map<Int, AppRecord> =
        apps.groupBy { it.uid }.mapValues { (_, records) ->
            records.sortedWith(
                compareBy<AppRecord> { it.isRemoved }
                    .thenBy { it.packageName.startsWith(UNKNOWN_UID_PREFIX) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
            ).first()
        }

    private fun AppRecord.isResolvedApp(): Boolean =
        !isRemoved && !packageName.startsWith(UNKNOWN_UID_PREFIX)

    private fun specialUidRecord(uid: Int, calculatedAt: Long): AppRecord? {
        val info = when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> SpecialUidInfo(
                packageName = "android.uid.removed",
                appName = "Удалённые приложения",
                isSystemApp = false,
                isRemoved = true
            )
            NetworkStats.Bucket.UID_TETHERING -> SpecialUidInfo(
                packageName = "android.uid.tethering",
                appName = "Раздача интернета",
                isSystemApp = true,
                isRemoved = false
            )
            Process.SYSTEM_UID -> SpecialUidInfo(
                packageName = "android.uid.system",
                appName = "Система Android",
                isSystemApp = true,
                isRemoved = false
            )
            else -> return null
        }
        return AppRecord(
            packageName = info.packageName,
            uid = uid,
            appName = info.appName,
            iconCachePath = null,
            isSystemApp = info.isSystemApp,
            isRemoved = info.isRemoved,
            lastSeenAt = calculatedAt
        )
    }

    private data class SpecialUidInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean,
        val isRemoved: Boolean
    )

    private companion object {
        const val UNKNOWN_UID_PREFIX = "unknown.uid."
        const val USER_REFRESH_TIMEOUT_MS = 2 * 60 * 1000L
        const val BACKGROUND_REFRESH_TIMEOUT_MS = 90 * 1000L
        const val INTERVAL_LOAD_TIMEOUT_MS = 60 * 1000L
    }
}

object ServiceLocator {
    @Volatile
    private var instance: Services? = null

    fun from(context: Context): Services {
        val appContext = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: create(appContext).also { instance = it }
        }
    }

    private fun create(context: Context): Services {
        val database = TrafficDatabase(context)
        val settings = SettingsStore(context)
        val permissions = PermissionChecker(context)
        val catalog = AppCatalog(context, database, settings)
        return Services(
            database = database,
            settings = settings,
            permissions = permissions,
            icons = AppIconCache(context),
            repository = TrafficRepository(
                database = database,
                settingsStore = settings,
                permissionChecker = permissions,
                appCatalog = catalog,
                networkStatsReader = NetworkStatsReader(context)
            )
        )
    }
}

data class Services(
    val database: TrafficDatabase,
    val settings: SettingsStore,
    val permissions: PermissionChecker,
    val icons: AppIconCache,
    val repository: TrafficRepository
)
