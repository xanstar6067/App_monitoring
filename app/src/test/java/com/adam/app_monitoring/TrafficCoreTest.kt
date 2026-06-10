package com.adam.app_monitoring

import com.adam.app_monitoring.core.model.NetworkMode
import com.adam.app_monitoring.core.model.AppRecord
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficSnapshot
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.ByteFormatter
import com.adam.app_monitoring.core.util.ByteUnitPreference
import com.adam.app_monitoring.core.util.TimeRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TrafficCoreTest {
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
}
