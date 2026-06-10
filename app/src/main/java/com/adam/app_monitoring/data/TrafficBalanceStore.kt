package com.adam.app_monitoring.data

import com.adam.app_monitoring.core.util.TimeRanges
import com.adam.app_monitoring.core.util.TrafficLimitBalance

object TrafficBalanceStore {
    suspend fun refresh(services: Services): Long {
        val settings = services.settings.read()
        val range = TimeRanges.forBillingCycle(settings.billingCycleStartDay)
        val usedBytes = services.repository
            .loadUsageForRange(range.startMillis, range.endMillis)
            .mobileBytes
        val remainingBytes = TrafficLimitBalance.remainingBytes(
            limitMb = settings.monthlyTrafficLimitMb,
            usedBytes = usedBytes,
            periodStartMillis = range.startMillis,
            configuredRemainingMb = settings.configuredTrafficRemainingMb,
            configuredBaselineBytes = settings.trafficRemainingBaselineBytes,
            configuredPeriodStartMillis = settings.trafficRemainingPeriodStartMillis,
            hasConfiguredRemaining = settings.hasConfiguredTrafficRemaining
        )
        services.settings.cacheTrafficRemaining(remainingBytes)
        return remainingBytes
    }

    suspend fun configure(services: Services, remainingMb: Int): Long {
        val settings = services.settings.read()
        val range = TimeRanges.forBillingCycle(settings.billingCycleStartDay)
        val usedBytes = services.repository
            .loadUsageForRange(range.startMillis, range.endMillis)
            .mobileBytes
        val safeRemainingMb = remainingMb.coerceIn(0, settings.monthlyTrafficLimitMb)
        val remainingBytes = safeRemainingMb.toLong() * TrafficLimitBalance.BYTES_PER_MB
        services.settings.configureTrafficRemaining(
            remainingMb = safeRemainingMb,
            baselineBytes = usedBytes,
            periodStartMillis = range.startMillis,
            cachedRemainingBytes = remainingBytes
        )
        return remainingBytes
    }
}
