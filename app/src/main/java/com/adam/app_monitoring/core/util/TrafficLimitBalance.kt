package com.adam.app_monitoring.core.util

object TrafficLimitBalance {
    const val BYTES_PER_MB = 1024L * 1024L

    fun remainingBytes(
        limitMb: Int,
        usedBytes: Long,
        periodStartMillis: Long,
        configuredRemainingMb: Int,
        configuredBaselineBytes: Long,
        configuredPeriodStartMillis: Long,
        hasConfiguredRemaining: Boolean
    ): Long {
        val limitBytes = limitMb.toLong() * BYTES_PER_MB
        if (!hasConfiguredRemaining || configuredPeriodStartMillis != periodStartMillis) {
            return (limitBytes - usedBytes).coerceAtLeast(0)
        }

        val usageSinceConfigured = (usedBytes - configuredBaselineBytes).coerceAtLeast(0)
        return (configuredRemainingMb.toLong() * BYTES_PER_MB - usageSinceConfigured)
            .coerceAtLeast(0)
    }
}
