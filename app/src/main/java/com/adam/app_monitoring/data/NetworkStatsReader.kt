@file:Suppress("DEPRECATION")

package com.adam.app_monitoring.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.adam.app_monitoring.core.model.ChartPoint
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.ChartBuckets
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PeriodStats(
    val byUid: Map<Int, TrafficUsage>,
    val chart: List<ChartPoint>
)

class NetworkStatsReader(context: Context) {
    private val manager = context.getSystemService(NetworkStatsManager::class.java)

    suspend fun read(
        startMillis: Long,
        endMillis: Long,
        period: TrafficPeriod,
        includeChart: Boolean
    ): PeriodStats {
        require(endMillis > startMillis)
        val wifi = readSummary(
            ConnectivityManager.TYPE_WIFI,
            startMillis,
            endMillis
        )
        val mobile = readSummary(
            ConnectivityManager.TYPE_MOBILE,
            startMillis,
            endMillis
        )
        val allUids = wifi.keys + mobile.keys
        val totals = allUids.associateWith { uid ->
            val wifiUsage = wifi[uid] ?: TrafficUsage()
            val mobileUsage = mobile[uid] ?: TrafficUsage()
            TrafficUsage(
                wifiRxBytes = wifiUsage.wifiRxBytes,
                wifiTxBytes = wifiUsage.wifiTxBytes,
                mobileRxBytes = mobileUsage.mobileRxBytes,
                mobileTxBytes = mobileUsage.mobileTxBytes
            )
        }
        val chart = if (includeChart) {
            try {
                val totalUsage = totals.values.fold(TrafficUsage()) { total, usage ->
                    total + usage
                }
                when (period) {
                    TrafficPeriod.TODAY -> readTodayChart(
                        startMillis = startMillis,
                        endMillis = endMillis,
                        hasWifiTraffic = totalUsage.wifiBytes > 0,
                        hasMobileTraffic = totalUsage.mobileBytes > 0
                    )
                    TrafficPeriod.MONTH,
                    TrafficPeriod.PREVIOUS_MONTH -> {
                        val latestTimestamp = minOf(
                            System.currentTimeMillis(),
                            endMillis - 1
                        ).coerceAtLeast(startMillis)
                        val detailed = ChartBuckets.ensurePoint(
                            points = readDetailedChart(startMillis, endMillis, period),
                            timestamp = latestTimestamp,
                            period = period,
                            label = chartLabel(latestTimestamp, period)
                        )
                        ChartBuckets.reconcileTotals(
                            points = detailed,
                            wifiTotalBytes = totalUsage.wifiBytes,
                            mobileTotalBytes = totalUsage.mobileBytes
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return PeriodStats(totals, chart)
    }

    @Suppress("DEPRECATION")
    private suspend fun readSummary(
        networkType: Int,
        startMillis: Long,
        endMillis: Long
    ): Map<Int, TrafficUsage> {
        val result = mutableMapOf<Int, TrafficUsage>()
        val stats = manager.querySummary(networkType, null, startMillis, endMillis)
        stats.use {
            val bucket = NetworkStats.Bucket()
            var count = 0
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                if (bucket.uid != NetworkStats.Bucket.UID_ALL) {
                    val old = result[bucket.uid] ?: TrafficUsage()
                    result[bucket.uid] = if (networkType == ConnectivityManager.TYPE_WIFI) {
                        old.copy(
                            wifiRxBytes = old.wifiRxBytes + bucket.rxBytes.safeBytes(),
                            wifiTxBytes = old.wifiTxBytes + bucket.txBytes.safeBytes()
                        )
                    } else {
                        old.copy(
                            mobileRxBytes = old.mobileRxBytes + bucket.rxBytes.safeBytes(),
                            mobileTxBytes = old.mobileTxBytes + bucket.txBytes.safeBytes()
                        )
                    }
                }
                count++
                if (count % CANCELLATION_CHECK_INTERVAL == 0) {
                    currentCoroutineContext().ensureActive()
                }
                check(count <= MAX_SUMMARY_BUCKETS) {
                    "Слишком большой объём сводной статистики"
                }
            }
        }
        return result
    }

    private suspend fun readTodayChart(
        startMillis: Long,
        endMillis: Long,
        hasWifiTraffic: Boolean,
        hasMobileTraffic: Boolean
    ): List<ChartPoint> {
        val zoneId = ZoneId.systemDefault()
        val dayStarts = ChartBuckets.dayBucketStarts(startMillis, zoneId)
        val elapsedStarts = dayStarts.filter { it < endMillis }
        val dayEnd = Instant.ofEpochMilli(startMillis)
            .atZone(zoneId)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val wifi = if (hasWifiTraffic) {
            readHourlyDeviceNetwork(
                networkType = ConnectivityManager.TYPE_WIFI,
                dayStarts = dayStarts,
                elapsedStarts = elapsedStarts,
                dayEndMillis = dayEnd,
                rangeStartMillis = startMillis,
                rangeEndMillis = endMillis
            )
        } else {
            emptyMap()
        }
        val mobile = if (hasMobileTraffic) {
            readHourlyDeviceNetwork(
                networkType = ConnectivityManager.TYPE_MOBILE,
                dayStarts = dayStarts,
                elapsedStarts = elapsedStarts,
                dayEndMillis = dayEnd,
                rangeStartMillis = startMillis,
                rangeEndMillis = endMillis
            )
        } else {
            emptyMap()
        }
        val elapsedPoints = elapsedStarts.map { bucketStart ->
            ChartPoint(
                bucketStart = bucketStart,
                label = chartLabel(bucketStart, TrafficPeriod.TODAY),
                wifiBytes = wifi[bucketStart] ?: 0,
                mobileBytes = mobile[bucketStart] ?: 0
            )
        }
        return ChartBuckets.completeDay(elapsedPoints, startMillis, zoneId)
    }

    private suspend fun readHourlyDeviceNetwork(
        networkType: Int,
        dayStarts: List<Long>,
        elapsedStarts: List<Long>,
        dayEndMillis: Long,
        rangeStartMillis: Long,
        rangeEndMillis: Long
    ): Map<Long, Long> {
        return try {
            elapsedStarts.mapIndexed { index, bucketStart ->
                currentCoroutineContext().ensureActive()
                val start = maxOf(bucketStart, rangeStartMillis)
                val nextStart = dayStarts.getOrNull(index + 1) ?: dayEndMillis
                val end = minOf(nextStart, rangeEndMillis)
                bucketStart to if (end > start) {
                    readDeviceTotal(networkType, start, end)
                } else {
                    0L
                }
            }.toMap()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            readChartNetwork(
                networkType = networkType,
                startMillis = rangeStartMillis,
                endMillis = rangeEndMillis,
                period = TrafficPeriod.TODAY
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readDeviceTotal(
        networkType: Int,
        startMillis: Long,
        endMillis: Long
    ): Long {
        val bucket = manager.querySummaryForDevice(
            networkType,
            null,
            startMillis,
            endMillis
        )
        return bucket.rxBytes.safeBytes() + bucket.txBytes.safeBytes()
    }

    private suspend fun readDetailedChart(
        startMillis: Long,
        endMillis: Long,
        period: TrafficPeriod
    ): List<ChartPoint> {
        val wifi = readChartNetwork(
            ConnectivityManager.TYPE_WIFI,
            startMillis,
            endMillis,
            period
        )
        val mobile = readChartNetwork(
            ConnectivityManager.TYPE_MOBILE,
            startMillis,
            endMillis,
            period
        )
        return (wifi.keys + mobile.keys)
            .distinct()
            .sorted()
            .map { key ->
                val label = chartLabel(key, period)
                ChartPoint(
                    bucketStart = key,
                    label = label,
                    wifiBytes = wifi[key] ?: 0,
                    mobileBytes = mobile[key] ?: 0
                )
            }
    }

    @Suppress("DEPRECATION")
    private suspend fun readChartNetwork(
        networkType: Int,
        startMillis: Long,
        endMillis: Long,
        period: TrafficPeriod
    ): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        val stats = manager.queryDetails(networkType, null, startMillis, endMillis)
        stats.use {
            val bucket = NetworkStats.Bucket()
            var count = 0
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val bytes = bucket.rxBytes.safeBytes() + bucket.txBytes.safeBytes()
                ChartBuckets.distribute(
                    bucketStartMillis = bucket.startTimeStamp,
                    bucketEndMillis = bucket.endTimeStamp,
                    bytes = bytes,
                    rangeStartMillis = startMillis,
                    rangeEndMillis = endMillis,
                    period = period
                ).forEach { (key, allocatedBytes) ->
                    result[key] = (result[key] ?: 0) + allocatedBytes
                }
                count++
                if (count % CANCELLATION_CHECK_INTERVAL == 0) {
                    currentCoroutineContext().ensureActive()
                }
                check(count <= MAX_DETAIL_BUCKETS) {
                    "История слишком велика для безопасного построения графика"
                }
            }
        }
        return result
    }

    private fun chartLabel(timestamp: Long, period: TrafficPeriod): String {
        val zoned = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        return when (period) {
            TrafficPeriod.TODAY -> zoned.format(HOUR_FORMAT)
            TrafficPeriod.MONTH,
            TrafficPeriod.PREVIOUS_MONTH -> zoned.dayOfMonth.toString()
        }
    }

    private fun Long.safeBytes() = coerceAtLeast(0)

    private companion object {
        const val CANCELLATION_CHECK_INTERVAL = 256
        const val MAX_SUMMARY_BUCKETS = 100_000
        const val MAX_DETAIL_BUCKETS = 500_000
        val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")
    }
}
