package com.adam.app_monitoring.core.util

import com.adam.app_monitoring.core.model.TrafficPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class TimeRange(
    val startMillis: Long,
    val endMillis: Long
)

object TimeRanges {
    fun forPeriod(
        period: TrafficPeriod,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TimeRange {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val startDate = when (period) {
            TrafficPeriod.TODAY -> today
            TrafficPeriod.MONTH -> today.withDayOfMonth(1)
        }
        return TimeRange(
            startMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endMillis = nowMillis
        )
    }

    fun forDate(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TimeRange = TimeRange(
        startMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endMillis = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    )

    fun todayKey(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().toString()
}
