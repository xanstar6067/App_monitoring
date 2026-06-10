package com.adam.app_monitoring.core.util

import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.ChartPoint
import java.time.Instant
import java.time.ZoneId

object ChartBuckets {
    fun reconcileTotals(
        points: List<ChartPoint>,
        wifiTotalBytes: Long,
        mobileTotalBytes: Long
    ): List<ChartPoint> {
        if (points.isEmpty()) return points

        val wifi = reconcileValues(points.map { it.wifiBytes }, wifiTotalBytes)
        val mobile = reconcileValues(points.map { it.mobileBytes }, mobileTotalBytes)
        return points.mapIndexed { index, point ->
            point.copy(
                wifiBytes = wifi[index],
                mobileBytes = mobile[index]
            )
        }
    }

    fun reconcileLatestPoint(
        points: List<ChartPoint>,
        wifiTotalBytes: Long,
        mobileTotalBytes: Long,
        latestWifiBytes: Long,
        latestMobileBytes: Long
    ): List<ChartPoint> {
        if (points.isEmpty()) return points

        val earlier = points.dropLast(1)
        val reconciledEarlier = reconcileTotals(
            points = earlier,
            wifiTotalBytes = (wifiTotalBytes - latestWifiBytes).coerceAtLeast(0),
            mobileTotalBytes = (mobileTotalBytes - latestMobileBytes).coerceAtLeast(0)
        )
        return reconciledEarlier + points.last().copy(
            wifiBytes = latestWifiBytes.coerceAtLeast(0),
            mobileBytes = latestMobileBytes.coerceAtLeast(0)
        )
    }

    fun distribute(
        bucketStartMillis: Long,
        bucketEndMillis: Long,
        bytes: Long,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        period: TrafficPeriod,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Map<Long, Long> {
        if (bytes <= 0 || rangeEndMillis <= rangeStartMillis) return emptyMap()

        val start = maxOf(bucketStartMillis, rangeStartMillis)
        val end = minOf(bucketEndMillis, rangeEndMillis)
        if (end <= start) return emptyMap()

        val segments = mutableListOf<Pair<Long, Long>>()
        var segmentStart = start
        while (segmentStart < end) {
            val key = normalize(segmentStart, period, zoneId)
            val segmentEnd = minOf(nextBoundary(key, period, zoneId), end)
            segments += key to (segmentEnd - segmentStart)
            segmentStart = segmentEnd
        }

        val coveredDuration = end - start
        var remainingBytes = bytes
        return buildMap {
            segments.forEachIndexed { index, (key, duration) ->
                val allocated = if (index == segments.lastIndex) {
                    remainingBytes
                } else {
                    ((bytes / coveredDuration) * duration) +
                        ((bytes % coveredDuration) * duration / coveredDuration)
                }
                put(key, (get(key) ?: 0L) + allocated)
                remainingBytes -= allocated
            }
        }
    }

    private fun normalize(
        timestamp: Long,
        period: TrafficPeriod,
        zoneId: ZoneId
    ): Long {
        val zoned = Instant.ofEpochMilli(timestamp).atZone(zoneId)
        val normalized = when (period) {
            TrafficPeriod.TODAY -> zoned.withMinute(0).withSecond(0).withNano(0)
            TrafficPeriod.MONTH -> zoned.toLocalDate().atStartOfDay(zoneId)
        }
        return normalized.toInstant().toEpochMilli()
    }

    private fun nextBoundary(
        normalizedStart: Long,
        period: TrafficPeriod,
        zoneId: ZoneId
    ): Long {
        val zoned = Instant.ofEpochMilli(normalizedStart).atZone(zoneId)
        return when (period) {
            TrafficPeriod.TODAY -> zoned.plusHours(1)
            TrafficPeriod.MONTH -> zoned.plusDays(1)
        }.toInstant().toEpochMilli()
    }

    private fun reconcileValues(values: List<Long>, targetBytes: Long): List<Long> {
        val safeValues = values.map { it.coerceAtLeast(0) }
        val target = targetBytes.coerceAtLeast(0)
        val current = safeValues.sum()
        if (current == target) return safeValues
        if (current < target) {
            return safeValues.toMutableList().apply {
                this[lastIndex] += target - current
            }
        }
        if (target == 0L) return List(values.size) { 0L }

        var remaining = target
        return safeValues.mapIndexed { index, value ->
            val adjusted = if (index == safeValues.lastIndex) {
                remaining
            } else {
                (value.toDouble() / current.toDouble() * target.toDouble())
                    .toLong()
                    .coerceIn(0L, remaining)
            }
            remaining -= adjusted
            adjusted
        }
    }
}
