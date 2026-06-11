package com.adam.app_monitoring.background

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

data class ServiceDiagnosticEvent(
    val timestamp: Long,
    val type: String,
    val details: String
)

data class ProcessExitSummary(
    val timestamp: Long,
    val reason: String,
    val description: String
)

object NetworkSpeedDiagnostics {
    private const val PREFS_NAME = "network_speed_diagnostics"
    private const val KEY_EVENTS = "events"
    private const val KEY_HEARTBEAT = "heartbeat"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val MAX_EVENTS = 20
    private const val FIELD_SEPARATOR = "\u001f"

    fun record(context: Context, type: String, details: String = "") {
        val prefs = prefs(context)
        val event = listOf(
            System.currentTimeMillis().toString(),
            sanitize(type),
            sanitize(details)
        ).joinToString(FIELD_SEPARATOR)
        val updated = (listOf(event) + prefs.getStringSet(KEY_EVENTS, emptySet()).orEmpty())
            .sortedByDescending { it.substringBefore(FIELD_SEPARATOR).toLongOrNull() ?: 0L }
            .take(MAX_EVENTS)
            .toSet()
        prefs.edit().putStringSet(KEY_EVENTS, updated).apply()
    }

    fun recordHeartbeat(context: Context, timestamp: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_HEARTBEAT, timestamp).apply()
    }

    fun lastHeartbeat(context: Context): Long = prefs(context).getLong(KEY_HEARTBEAT, 0L)

    fun recentEvents(context: Context): List<ServiceDiagnosticEvent> =
        prefs(context).getStringSet(KEY_EVENTS, emptySet()).orEmpty()
            .mapNotNull { encoded ->
                val fields = encoded.split(FIELD_SEPARATOR, limit = 3)
                val timestamp = fields.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                ServiceDiagnosticEvent(
                    timestamp = timestamp,
                    type = fields.getOrElse(1) { "" },
                    details = fields.getOrElse(2) { "" }
                )
            }
            .sortedByDescending(ServiceDiagnosticEvent::timestamp)

    fun refreshLastExit(context: Context): ProcessExitSummary? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val exit = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .maxByOrNull { it.timestamp }
            ?: return null
        val prefs = prefs(context)
        if (exit.timestamp > prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)) {
            prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, exit.timestamp).apply()
            record(
                context,
                type = "process_exit",
                details = "${exitReasonLabel(exit.reason)}: ${exit.description.orEmpty()}"
            )
        }
        return ProcessExitSummary(
            timestamp = exit.timestamp,
            reason = exitReasonLabel(exit.reason),
            description = exit.description.orEmpty()
        )
    }

    private fun exitReasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Остановлено пользователем или системой"
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Сбой приложения"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Недостаточно памяти"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "Ограничено из-за расхода ресурсов"
        ApplicationExitInfo.REASON_ANR -> "Приложение не отвечало"
        ApplicationExitInfo.REASON_SIGNALED -> "Процесс завершён сигналом"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Штатное завершение"
        else -> "Системное завершение ($reason)"
    }

    private fun prefs(context: Context) =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun sanitize(value: String): String = value.replace(FIELD_SEPARATOR, " ").take(240)
}
