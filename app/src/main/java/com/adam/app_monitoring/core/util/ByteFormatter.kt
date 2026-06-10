package com.adam.app_monitoring.core.util

import java.util.Locale
import kotlin.math.abs

enum class ByteUnitPreference {
    AUTO,
    MB,
    GB
}

object ByteFormatter {
    private const val MB = 1024.0 * 1024.0
    private const val GB = 1024.0 * 1024.0 * 1024.0

    fun format(bytes: Long, preference: ByteUnitPreference = ByteUnitPreference.AUTO): String {
        val safeBytes = bytes.coerceAtLeast(0)
        val useGb = preference == ByteUnitPreference.GB ||
            (preference == ByteUnitPreference.AUTO && safeBytes >= GB)
        val value = if (useGb) safeBytes / GB else safeBytes / MB
        val unit = if (useGb) "ГБ" else "МБ"
        val decimals = when {
            value >= 100 -> 0
            value >= 10 -> 1
            else -> 2
        }
        return String.format(Locale.getDefault(), "%.${decimals}f %s", value, unit)
    }

    fun compact(bytes: Long): String {
        if (abs(bytes) < 1024) return "$bytes Б"
        return format(bytes)
    }
}
