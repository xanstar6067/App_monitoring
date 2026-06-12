package com.adam.app_monitoring.data

import android.content.Context

data class NetworkSpeedSnapshot(
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
    val measuredAt: Long
)

object NetworkSpeedSnapshotStore {
    private const val PREFS_NAME = "network_speed_snapshot"
    private const val KEY_DOWNLOAD = "download"
    private const val KEY_UPLOAD = "upload"
    private const val KEY_MEASURED_AT = "measured_at"
    private const val MAX_AGE_MS = 15_000L

    fun write(
        context: Context,
        downloadBytesPerSecond: Long,
        uploadBytesPerSecond: Long,
        measuredAt: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putLong(KEY_DOWNLOAD, downloadBytesPerSecond.coerceAtLeast(0))
            .putLong(KEY_UPLOAD, uploadBytesPerSecond.coerceAtLeast(0))
            .putLong(KEY_MEASURED_AT, measuredAt)
            .apply()
    }

    fun readFresh(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): NetworkSpeedSnapshot? {
        val prefs = prefs(context)
        val measuredAt = prefs.getLong(KEY_MEASURED_AT, 0)
        if (measuredAt <= 0 || now - measuredAt !in 0..MAX_AGE_MS) return null
        return NetworkSpeedSnapshot(
            downloadBytesPerSecond = prefs.getLong(KEY_DOWNLOAD, 0).coerceAtLeast(0),
            uploadBytesPerSecond = prefs.getLong(KEY_UPLOAD, 0).coerceAtLeast(0),
            measuredAt = measuredAt
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
