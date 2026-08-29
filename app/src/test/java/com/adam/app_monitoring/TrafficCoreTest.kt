package com.adam.app_monitoring

import com.adam.app_monitoring.core.model.NetworkMode
import com.adam.app_monitoring.core.model.AppRecord
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.ChartPoint
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficSnapshot
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.ByteFormatter
import com.adam.app_monitoring.core.util.ByteUnitPreference
import com.adam.app_monitoring.core.util.ChartBuckets
import com.adam.app_monitoring.core.util.TimeRanges
import com.adam.app_monitoring.core.util.TrafficLimitBalance
import com.adam.app_monitoring.core.util.NetworkSpeedFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TrafficCoreTest {
    @Test
    fun completeDayKeepsAllHoursAndFillsMissingOnesWithZero() {
        val zone = ZoneId.of("Europe/Minsk")
        val date = LocalDate.of(2026, 6, 13)
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val hour8 = date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val hour10 = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

        val result = ChartBuckets.completeDay(
            points = listOf(
                ChartPoint(hour8, "08", wifiBytes = 10, mobileBytes = 0),
                ChartPoint(hour10, "10", wifiBytes = 30, mobileBytes = 0)
            ),
            dayTimestamp = dayStart,
            zoneId = zone
        )

        assertEquals(24, result.size)
        assertEquals((0..23).map { "%02d".format(it) }, result.map { it.label })
        assertEquals(0L, result.single { it.label == "09" }.wifiBytes)
        assertEquals(30L, result.single { it.label == "10" }.wifiBytes)
    }

    @Test
    fun dayBucketsFollowCivilTimeOnDstTransition() {
        val zone = ZoneId.of("Europe/Berlin")
        val dayStart = LocalDate.of(2026, 3, 29)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        val starts = ChartBuckets.dayBucketStarts(dayStart, zone)
        val labels = starts.map {
            Instant.ofEpochMilli(it).atZone(zone).hour
        }

        assertEquals(23, starts.size)
        assertTrue(2 !in labels)
    }

    @Test
    fun currentChartBucketIsAddedWhenAndroidDetailsAreDelayed() {
        val zone = ZoneId.of("Europe/Minsk")
        val currentHour = ZonedDateTime.of(
            2026, 6, 12, 8, 35, 0, 0, zone
        ).toInstant().toEpochMilli()
        val previousHour = ZonedDateTime.of(
            2026, 6, 12, 7, 0, 0, 0, zone
        ).toInstant().toEpochMilli()

        val points = ChartBuckets.ensurePoint(
            points = listOf(
                ChartPoint(previousHour, "07", wifiBytes = 100, mobileBytes = 0)
            ),
            timestamp = currentHour,
            period = TrafficPeriod.TODAY,
            label = "08",
            zoneId = zone
        )
        val result = ChartBuckets.reconcileTotals(
            points = points,
            wifiTotalBytes = 175,
            mobileTotalBytes = 0
        )

        assertEquals(listOf("07", "08"), result.map { it.label })
        assertEquals(75, result.last().wifiBytes)
    }

    @Test
    fun currentChartBucketMakesFreshSummaryVisibleWithoutDetails() {
        val zone = ZoneId.of("Europe/Minsk")
        val now = ZonedDateTime.of(
            2026, 6, 12, 8, 35, 0, 0, zone
        ).toInstant().toEpochMilli()

        val points = ChartBuckets.ensurePoint(
            points = emptyList(),
            timestamp = now,
            period = TrafficPeriod.TODAY,
            label = "08",
            zoneId = zone
        )
        val result = ChartBuckets.reconcileTotals(
            points = points,
            wifiTotalBytes = 250,
            mobileTotalBytes = 40
        )

        assertEquals(1, result.size)
        assertEquals(250, result.single().wifiBytes)
        assertEquals(40, result.single().mobileBytes)
    }

    @Test
    fun todayRangeStartsAtLocalMidnight() {
        val zone = ZoneId.of("Europe/Minsk")
        val now = LocalDateTime.of(2026, 6, 10, 14, 35)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val range = TimeRanges.forPeriod(TrafficPeriod.TODAY, now, zone)

        val expectedStart = LocalDateTime.of(2026, 6, 10, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedStart, range.startMillis)
        assertEquals(now, range.endMillis)
    }

    @Test
    fun monthRangeStartsOnFirstDay() {
        val zone = ZoneId.of("UTC")
        val now = LocalDateTime.of(2026, 6, 10, 14, 35)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val range = TimeRanges.forPeriod(TrafficPeriod.MONTH, now, zone)

        val expectedStart = LocalDateTime.of(2026, 6, 1, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedStart, range.startMillis)
    }

    @Test
    fun previousMonthRangeCoversCompleteCalendarMonth() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = LocalDateTime.of(2026, 1, 10, 14, 35)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val range = TimeRanges.forPeriod(TrafficPeriod.PREVIOUS_MONTH, now, zone)

        val expectedStart = LocalDateTime.of(2025, 12, 1, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val expectedEnd = LocalDateTime.of(2026, 1, 1, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedStart, range.startMillis)
        assertEquals(expectedEnd, range.endMillis)
    }

    @Test
    fun chartBucketCrossingMidnightIsSplitBetweenDays() {
        val zone = ZoneId.of("Europe/Minsk")
        val bucketStart = LocalDateTime.of(2026, 6, 9, 23, 30)
            .atZone(zone).toInstant().toEpochMilli()
        val bucketEnd = LocalDateTime.of(2026, 6, 10, 0, 30)
            .atZone(zone).toInstant().toEpochMilli()
        val rangeStart = LocalDateTime.of(2026, 6, 1, 0, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val rangeEnd = LocalDateTime.of(2026, 6, 10, 14, 0)
            .atZone(zone).toInstant().toEpochMilli()

        val result = ChartBuckets.distribute(
            bucketStartMillis = bucketStart,
            bucketEndMillis = bucketEnd,
            bytes = 1_000,
            rangeStartMillis = rangeStart,
            rangeEndMillis = rangeEnd,
            period = TrafficPeriod.MONTH,
            zoneId = zone
        )

        val june9 = LocalDateTime.of(2026, 6, 9, 0, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val june10 = LocalDateTime.of(2026, 6, 10, 0, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(500L, result[june9])
        assertEquals(500L, result[june10])
    }

    @Test
    fun chartBucketIsClippedToRequestedRange() {
        val zone = ZoneId.of("UTC")
        val bucketStart = LocalDateTime.of(2026, 6, 9, 23, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val bucketEnd = LocalDateTime.of(2026, 6, 10, 1, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val rangeStart = LocalDateTime.of(2026, 6, 10, 0, 0)
            .atZone(zone).toInstant().toEpochMilli()

        val result = ChartBuckets.distribute(
            bucketStartMillis = bucketStart,
            bucketEndMillis = bucketEnd,
            bytes = 600,
            rangeStartMillis = rangeStart,
            rangeEndMillis = bucketEnd,
            period = TrafficPeriod.TODAY,
            zoneId = zone
        )

        assertEquals(mapOf(rangeStart to 600L), result)
    }

    @Test
    fun chartTotalsIncludeDelayedTrafficInLatestBucket() {
        val points = listOf(
            ChartPoint(1, "9", wifiBytes = 500, mobileBytes = 20),
            ChartPoint(2, "10", wifiBytes = 725, mobileBytes = 48)
        )

        val result = ChartBuckets.reconcileTotals(
            points = points,
            wifiTotalBytes = 1_461,
            mobileTotalBytes = 48
        )

        assertEquals(500L, result[0].wifiBytes)
        assertEquals(961L, result[1].wifiBytes)
        assertEquals(1_509L, result.sumOf { it.wifiBytes + it.mobileBytes })
    }

    @Test
    fun chartTotalsAreScaledDownWhenDetailsExceedSummary() {
        val points = listOf(
            ChartPoint(1, "1", wifiBytes = 600, mobileBytes = 0),
            ChartPoint(2, "2", wifiBytes = 400, mobileBytes = 0)
        )

        val result = ChartBuckets.reconcileTotals(
            points = points,
            wifiTotalBytes = 500,
            mobileTotalBytes = 0
        )

        assertEquals(500L, result.sumOf { it.wifiBytes })
        assertEquals(300L, result[0].wifiBytes)
        assertEquals(200L, result[1].wifiBytes)
    }

    @Test
    fun monthlyChartUsesExactTodayTotalForLatestPoint() {
        val points = listOf(
            ChartPoint(1, "9", wifiBytes = 2_000, mobileBytes = 200),
            ChartPoint(2, "10", wifiBytes = 725, mobileBytes = 48)
        )

        val result = ChartBuckets.reconcileLatestPoint(
            points = points,
            wifiTotalBytes = 3_200,
            mobileTotalBytes = 300,
            latestWifiBytes = 962,
            latestMobileBytes = 49
        )

        assertEquals(962L, result.last().wifiBytes)
        assertEquals(49L, result.last().mobileBytes)
        assertEquals(3_200L, result.sumOf { it.wifiBytes })
        assertEquals(300L, result.sumOf { it.mobileBytes })
    }

    @Test
    fun billingCycleUsesConfiguredDay() {
        val zone = ZoneId.of("UTC")
        val now = LocalDateTime.of(2026, 6, 10, 14, 35)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val range = TimeRanges.forBillingCycle(7, now, zone)

        val expectedStart = LocalDateTime.of(2026, 6, 7, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedStart, range.startMillis)
    }

    @Test
    fun billingCycleClampsDayToEndOfShortMonth() {
        val zone = ZoneId.of("UTC")
        val now = LocalDateTime.of(2026, 3, 15, 14, 35)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val range = TimeRanges.forBillingCycle(31, now, zone)

        val expectedStart = LocalDateTime.of(2026, 2, 28, 0, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expectedStart, range.startMillis)
    }

    @Test
    fun usageKeepsWifiAndMobileSeparate() {
        val usage = TrafficUsage(
            wifiRxBytes = 10,
            wifiTxBytes = 20,
            mobileRxBytes = 30,
            mobileTxBytes = 40
        )

        assertEquals(30, usage.bytesFor(NetworkMode.WIFI))
        assertEquals(70, usage.bytesFor(NetworkMode.MOBILE))
        assertEquals(100, usage.bytesFor(NetworkMode.ALL))
        assertEquals(40, usage.rxBytes)
        assertEquals(60, usage.txBytes)
    }

    @Test
    fun configuredTrafficBalanceSubtractsOnlyNewMobileUsage() {
        val mb = TrafficLimitBalance.BYTES_PER_MB

        val remaining = TrafficLimitBalance.remainingBytes(
            limitMb = 10_000,
            usedBytes = 4_500 * mb,
            periodStartMillis = 100,
            configuredRemainingMb = 6_000,
            configuredBaselineBytes = 4_000 * mb,
            configuredPeriodStartMillis = 100,
            hasConfiguredRemaining = true
        )

        assertEquals(5_500 * mb, remaining)
    }

    @Test
    fun configuredTrafficBalanceResetsForNewBillingCycle() {
        val mb = TrafficLimitBalance.BYTES_PER_MB

        val remaining = TrafficLimitBalance.remainingBytes(
            limitMb = 10_000,
            usedBytes = 700 * mb,
            periodStartMillis = 200,
            configuredRemainingMb = 2_000,
            configuredBaselineBytes = 8_000 * mb,
            configuredPeriodStartMillis = 100,
            hasConfiguredRemaining = true
        )

        assertEquals(9_300 * mb, remaining)
    }

    @Test
    fun snapshotKeepsPeriodAndTodayTotalsSeparate() {
        val app = AppRecord(
            packageName = "example",
            uid = 1,
            appName = "Example",
            iconCachePath = null,
            isSystemApp = false,
            lastSeenAt = 0
        )
        val snapshot = TrafficSnapshot(
            period = TrafficPeriod.MONTH,
            periodStart = 0,
            periodEnd = 1,
            apps = listOf(
                AppTraffic(
                    app = app,
                    periodUsage = TrafficUsage(wifiRxBytes = 100),
                    todayUsage = TrafficUsage(wifiRxBytes = 25)
                )
            ),
            chart = emptyList(),
            calculatedAt = 1
        )

        assertEquals(100, snapshot.totalUsage.totalBytes)
        assertEquals(25, snapshot.todayTotalUsage.totalBytes)
    }

    @Test
    fun byteFormatterUsesRequestedUnit() {
        val formatted = ByteFormatter.format(
            2L * 1024 * 1024 * 1024,
            ByteUnitPreference.GB
        )

        assertTrue(formatted.endsWith("ГБ"))
        assertTrue(formatted.startsWith("2"))
    }

    @Test
    fun networkSpeedFormatterChoosesReadableUnits() {
        assertTrue(NetworkSpeedFormatter.format(0).startsWith("0"))
        assertTrue(NetworkSpeedFormatter.format(0).endsWith("Б/с"))
        assertTrue(NetworkSpeedFormatter.format(1536).endsWith("КБ/с"))
        assertEquals("1.5", NetworkSpeedFormatter.iconText(1536).value)
        assertEquals("КБ/с", NetworkSpeedFormatter.iconText(1536).unit)
        assertEquals("МБ/с", NetworkSpeedFormatter.iconText(2L * 1024 * 1024).unit)
        assertEquals("ГБ/с", NetworkSpeedFormatter.iconText(2L * 1024 * 1024 * 1024).unit)
        assertEquals("9.9", NetworkSpeedFormatter.iconText(10_137).value)
        assertEquals("10", NetworkSpeedFormatter.iconText(10_199).value)
        assertEquals("10", NetworkSpeedFormatter.iconText(10_240).value)
    }
}
