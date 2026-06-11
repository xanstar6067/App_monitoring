package com.adam.app_monitoring.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.adam.app_monitoring.MainActivity
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.ByteFormatter
import com.adam.app_monitoring.core.util.NetworkSpeedFormatter
import com.adam.app_monitoring.core.util.SpeedIconText
import com.adam.app_monitoring.core.util.TimeRanges
import com.adam.app_monitoring.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkSpeedService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val services by lazy { ServiceLocator.from(applicationContext) }
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private val statusBarIconRenderer by lazy {
        StatusBarSpeedIconRenderer(applicationContext)
    }
    private var samplerJob: Job? = null
    private var totalsJob: Job? = null
    @Volatile
    private var todayUsage = TrafficUsage()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        todayUsage = services.repository.loadCached(TrafficPeriod.TODAY)
            ?.todayTotalUsage
            ?: TrafficUsage()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!services.settings.read().speedNotificationEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (samplerJob == null) {
            startInForeground(buildNotification(0, 0, todayUsage))
            startSampling()
            startTotalsRefresh()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        samplerJob?.cancel()
        totalsJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (services.settings.read().speedNotificationEnabled) {
            scheduleRestart(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSampling() {
        samplerJob = scope.launch {
            var previousRx = TrafficStats.getTotalRxBytes().validCounter()
            var previousTx = TrafficStats.getTotalTxBytes().validCounter()
            var previousAt = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val currentRx = TrafficStats.getTotalRxBytes().validCounter()
                val currentTx = TrafficStats.getTotalTxBytes().validCounter()
                val currentAt = SystemClock.elapsedRealtime()
                val elapsedMs = (currentAt - previousAt).coerceAtLeast(1)
                val rxPerSecond = bytesPerSecond(currentRx, previousRx, elapsedMs)
                val txPerSecond = bytesPerSecond(currentTx, previousTx, elapsedMs)

                previousRx = currentRx
                previousTx = currentTx
                previousAt = currentAt
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification(rxPerSecond, txPerSecond, todayUsage)
                )
            }
        }
    }

    private fun startTotalsRefresh() {
        totalsJob = scope.launch {
            while (isActive) {
                refreshTodayUsage()
                delay(TOTALS_REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshTodayUsage() {
        if (!services.permissions.hasUsageAccess()) return
        runCatching {
            val range = TimeRanges.forPeriod(TrafficPeriod.TODAY)
            withContext(Dispatchers.IO) {
                services.repository.loadUsageForRange(range.startMillis, range.endMillis)
            }
        }.onSuccess { usage ->
            todayUsage = usage
        }
    }

    private fun buildNotification(
        rxPerSecond: Long,
        txPerSecond: Long,
        usage: TrafficUsage
    ): Notification {
        val totalSpeed = rxPerSecond + txPerSecond
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "Загр.: ${NetworkSpeedFormatter.format(rxPerSecond)}   " +
            "Пер.: ${NetworkSpeedFormatter.format(txPerSecond)}"
        val totals = "Моб.: ${ByteFormatter.format(usage.mobileBytes)}   " +
            "Wi-Fi: ${ByteFormatter.format(usage.wifiBytes)}"
        val smallIcon = statusBarIconRenderer.create(
            NetworkSpeedFormatter.iconText(totalSpeed).withFullUnit()
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setLargeIcon(createLargeIcon(totalSpeed))
            .setContentTitle(title)
            .setContentText(totals)
            .setStyle(Notification.BigTextStyle().bigText(totals))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun createLargeIcon(bytesPerSecond: Long): Bitmap {
        val size = 144
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 124, 210)
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, background)
        val icon = NetworkSpeedFormatter.iconText(bytesPerSecond)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (icon.value.length >= 3) 48f else 56f
        }
        canvas.drawText(icon.value, size / 2f, 76f, paint)
        paint.textSize = 30f
        canvas.drawText(icon.unit, size / 2f, 112f, paint)
        return bitmap
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Текущая скорость интернета",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Постоянная скорость загрузки и передачи данных"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun Long.validCounter(): Long = takeIf { it >= 0 } ?: 0

    private fun bytesPerSecond(current: Long, previous: Long, elapsedMs: Long): Long {
        if (current < previous) return 0
        return ((current - previous) * 1000L / elapsedMs).coerceAtLeast(0)
    }

    private fun SpeedIconText.withFullUnit() = copy(
        unit = when (unit) {
            "K/s" -> "KB/s"
            "M/s" -> "MB/s"
            "G/s" -> "GB/s"
            else -> unit
        }
    )

    companion object {
        private const val CHANNEL_ID = "network_speed"
        private const val NOTIFICATION_ID = 1002
        private const val RESTART_REQUEST_CODE = 1003
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val TOTALS_REFRESH_INTERVAL_MS = 60_000L
        private const val RESTART_DELAY_MS = 1_500L

        fun sync(context: Context) {
            val appContext = context.applicationContext
            val enabled = ServiceLocator.from(appContext)
                .settings
                .read()
                .speedNotificationEnabled
            if (enabled && hasNotificationPermission(appContext)) {
                cancelScheduledRestart(appContext)
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, NetworkSpeedService::class.java)
                )
            } else {
                cancelScheduledRestart(appContext)
                appContext.stopService(Intent(appContext, NetworkSpeedService::class.java))
            }
        }

        private fun scheduleRestart(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                restartPendingIntent(context)
            )
        }

        private fun cancelScheduledRestart(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                .cancel(restartPendingIntent(context))
        }

        private fun restartPendingIntent(context: Context): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                RESTART_REQUEST_CODE,
                Intent(context, NetworkSpeedService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
