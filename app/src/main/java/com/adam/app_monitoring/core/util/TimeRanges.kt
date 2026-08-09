package com.adam.app_monitoring.core.util

import com.adam.app_monitoring.core.model.TrafficPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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
        val currentMonthStart = today.withDayOfMonth(1)
        val startDate = when (period) {
            TrafficPeriod.TODAY -> today
            TrafficPeriod.MONTH -> currentMonthStart
            TrafficPeriod.PREVIOUS_MONTH -> currentMonthStart.minusMonths(1)
        }
        val endMillis = when (period) {
            TrafficPeriod.PREVIOUS_MONTH -> currentMonthStart
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            else -> nowMillis
        }
        return TimeRange(
            startMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endMillis = endMillis
        )
    }

    fun forDate(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TimeRange = TimeRange(
        startMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endMillis = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    )

    fun forBillingCycle(
        startDay: Int,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TimeRange {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val day = startDay.coerceIn(1, 31)
        val thisMonthStart = billingDate(YearMonth.from(now), day)
        val startDate = if (now.toLocalDate() >= thisMonthStart) {
            thisMonthStart
        } else {
            billingDate(YearMonth.from(now).minusMonths(1), day)
        }
        return TimeRange(
            startMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endMillis = nowMillis
        )
    }

    fun todayKey(
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().toString()

    private fun billingDate(month: YearMonth, day: Int): LocalDate =
        month.atDay(day.coerceAtMost(month.lengthOfMonth()))
}
