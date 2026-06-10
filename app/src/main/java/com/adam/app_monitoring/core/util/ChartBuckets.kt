package com.adam.app_monitoring.core.util

import com.adam.app_monitoring.core.model.TrafficPeriod
import java.time.Instant
import java.time.ZoneId

object ChartBuckets {
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
}
