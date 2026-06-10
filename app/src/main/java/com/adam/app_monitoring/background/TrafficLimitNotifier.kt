package com.adam.app_monitoring.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.adam.app_monitoring.MainActivity
import com.adam.app_monitoring.R
import com.adam.app_monitoring.core.util.TimeRanges
import com.adam.app_monitoring.data.Services
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

object TrafficLimitNotifier {
    private const val CHANNEL_ID = "traffic_limit_alerts"
    private const val NOTIFICATION_ID = 1001
    private const val BYTES_PER_MB = 1024L * 1024L

    suspend fun checkAndNotify(context: Context, services: Services) {
        val settings = services.settings.read()
        if (!settings.trafficLimitNotificationsEnabled ||
            !services.permissions.hasUsageAccess() ||
            !hasNotificationPermission(context)
        ) {
            return
        }

        val range = TimeRanges.forBillingCycle(settings.billingCycleStartDay)
        val periodKey = Instant.ofEpochMilli(range.startMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        if (services.settings.lastLimitNotificationPeriod() == periodKey) return

        val usedBytes = services.repository
            .loadUsageForRange(range.startMillis, range.endMillis)
            .mobileBytes
        val limitBytes = settings.monthlyTrafficLimitMb.toLong() * BYTES_PER_MB
        val warningBytes = limitBytes * settings.trafficWarningPercent / 100
        if (usedBytes < warningBytes) return

        createChannel(context)
        val usedMb = usedBytes.toDouble() / BYTES_PER_MB
        val usedPercent = (usedBytes.toDouble() * 100 / limitBytes).roundToInt()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Приближение к лимиту трафика")
            .setContentText(
                "Использовано %.0f из %d МБ мобильного трафика (%d%%)"
                    .format(usedMb, settings.monthlyTrafficLimitMb, usedPercent)
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
        services.settings.markLimitNotificationPeriod(periodKey)
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Лимит мобильного трафика",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Предупреждения о приближении к месячному лимиту трафика"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
