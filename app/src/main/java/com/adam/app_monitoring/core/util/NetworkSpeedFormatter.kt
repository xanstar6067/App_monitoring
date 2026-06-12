package com.adam.app_monitoring.core.util

import java.util.Locale

data class SpeedIconText(
    val value: String,
    val unit: String
)

object NetworkSpeedFormatter {
    private const val KIB = 1024.0
    private const val MIB = KIB * 1024.0
    private const val GIB = MIB * 1024.0

    fun format(bytesPerSecond: Long): String {
        val safeValue = bytesPerSecond.coerceAtLeast(0).toDouble()
        val (value, unit) = when {
            safeValue >= GIB -> safeValue / GIB to "ГБ/с"
            safeValue >= MIB -> safeValue / MIB to "МБ/с"
            safeValue >= KIB -> safeValue / KIB to "КБ/с"
            else -> safeValue to "Байт/с"
        }
        val decimals = when {
            safeValue < KIB -> 0
            value >= 100 -> 0
            value >= 10 -> 1
            else -> 2
        }
        return String.format(Locale.getDefault(), "%.${decimals}f %s", value, unit)
    }

    fun iconText(bytesPerSecond: Long): SpeedIconText {
        val safeValue = bytesPerSecond.coerceAtLeast(0).toDouble()
        val (value, unit) = when {
            safeValue >= GIB -> safeValue / GIB to "G/s"
            safeValue >= MIB -> safeValue / MIB to "M/s"
            safeValue >= KIB -> safeValue / KIB to "K/s"
            else -> safeValue to "B/s"
        }
        val text = when {
            value >= 100 -> value.toLong().coerceAtMost(999).toString()
            value >= 10 -> String.format(Locale.US, "%.0f", value)
            value >= 1 -> {
                val decimalText = String.format(Locale.US, "%.1f", value)
                if (decimalText.length <= 3) {
                    decimalText
                } else {
                    String.format(Locale.US, "%.0f", value)
                }
            }
            else -> "0"
        }
        return SpeedIconText(text, unit)
    }
}
