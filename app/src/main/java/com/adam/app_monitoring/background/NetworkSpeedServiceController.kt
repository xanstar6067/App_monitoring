package com.adam.app_monitoring.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.adam.app_monitoring.data.ServiceLocator

enum class RestartAlarmMode {
    EXACT,
    INEXACT
}

object NetworkSpeedServiceController {
    private const val RESTART_REQUEST_CODE = 1003
    private const val RESTART_DELAY_MS = 2_000L

    fun sync(context: Context, source: String = "sync") {
        val appContext = context.applicationContext
        val settings = ServiceLocator.from(appContext).settings.read()
        if (settings.speedNotificationEnabled && hasNotificationPermission(appContext)) {
            start(appContext, source)
        } else {
            stop(appContext, if (settings.speedNotificationEnabled) "notifications_denied" else source)
        }
    }

    fun start(context: Context, source: String): Boolean {
        val appContext = context.applicationContext
        if (!ServiceLocator.from(appContext).settings.read().speedNotificationEnabled) {
            cancelRestart(appContext)
            NetworkSpeedDiagnostics.record(appContext, "start_skipped", "indicator_disabled")
            return false
        }
        return runCatching {
            cancelRestart(appContext)
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, NetworkSpeedService::class.java)
                    .putExtra(NetworkSpeedService.EXTRA_START_SOURCE, source)
            )
        }.onSuccess {
            NetworkSpeedDiagnostics.record(appContext, "start_requested", source)
        }.onFailure {
            NetworkSpeedDiagnostics.record(
                appContext,
                "start_failed",
                "$source: ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
            )
        }.isSuccess
    }

    fun stop(context: Context, source: String) {
        val appContext = context.applicationContext
        cancelRestart(appContext)
        appContext.stopService(Intent(appContext, NetworkSpeedService::class.java))
        NetworkSpeedDiagnostics.record(appContext, "stop_requested", source)
    }

    fun scheduleRestart(context: Context, source: String): RestartAlarmMode? {
        val appContext = context.applicationContext
        if (!ServiceLocator.from(appContext).settings.read().speedNotificationEnabled) {
            cancelRestart(appContext)
            NetworkSpeedDiagnostics.record(appContext, "restart_skipped", "indicator_disabled")
            return null
        }
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        var mode = chooseAlarmMode(
            sdkInt = Build.VERSION.SDK_INT,
            canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
        )
        val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        val scheduled = runCatching {
            if (mode == RestartAlarmMode.EXACT) {
                runCatching {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        restartPendingIntent(appContext)
                    )
                }.onFailure {
                    mode = RestartAlarmMode.INEXACT
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        restartPendingIntent(appContext)
                    )
                }
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    restartPendingIntent(appContext)
                )
            }
        }
        if (scheduled.isFailure) {
            NetworkSpeedDiagnostics.record(
                appContext,
                "restart_schedule_failed",
                "$source: ${scheduled.exceptionOrNull()?.message.orEmpty()}"
            )
            return null
        }
        NetworkSpeedDiagnostics.record(appContext, "restart_scheduled", "$source, $mode")
        return mode
    }

    fun cancelRestart(context: Context) {
        context.applicationContext.getSystemService(AlarmManager::class.java)
            .cancel(restartPendingIntent(context.applicationContext))
    }

    internal fun chooseAlarmMode(sdkInt: Int, canScheduleExact: Boolean): RestartAlarmMode =
        if (sdkInt < Build.VERSION_CODES.S || canScheduleExact) {
            RestartAlarmMode.EXACT
        } else {
            RestartAlarmMode.INEXACT
        }

    private fun restartPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RESTART_REQUEST_CODE,
            Intent(context, NetworkSpeedRestartReceiver::class.java)
                .setAction(NetworkSpeedRestartReceiver.ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
