package com.adam.app_monitoring.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import android.widget.RemoteViews
import com.adam.app_monitoring.MainActivity
import com.adam.app_monitoring.R
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.ByteFormatter
import com.adam.app_monitoring.core.util.NetworkSpeedFormatter
import com.adam.app_monitoring.core.util.TimeRanges
import com.adam.app_monitoring.data.ServiceLocator
import com.adam.app_monitoring.data.NetworkSpeedSnapshotStore
import com.adam.app_monitoring.widget.TrafficWidgetProvider
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
    private val notificationLayout by lazy {
        NetworkSpeedNotificationDesign.chooseLayout(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT
        )
    }
    private var samplerJob: Job? = null
    private var totalsJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastWidgetUpdateAt = 0L
    private val notificationShownAt = System.currentTimeMillis()
    @Volatile
    private var todayUsage = TrafficUsage()

    override fun onCreate() {
        super.onCreate()
        NetworkSpeedDiagnostics.record(this, "service_created")
        createChannel()
        todayUsage = services.repository.loadCached(TrafficPeriod.TODAY)
            ?.todayTotalUsage
            ?: TrafficUsage()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!services.settings.read().speedNotificationEnabled) {
            NetworkSpeedDiagnostics.record(this, "service_start_skipped", "indicator_disabled")
            stopSelf()
            return START_NOT_STICKY
        }
        if (samplerJob == null) {
            startInForeground(buildNotification(0, 0, todayUsage))
            startSampling()
            startTotalsRefresh()
            startHeartbeat()
            NetworkSpeedDiagnostics.record(
                this,
                "service_started",
                intent?.getStringExtra(EXTRA_START_SOURCE).orEmpty()
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        samplerJob?.cancel()
        totalsJob?.cancel()
        heartbeatJob?.cancel()
        NetworkSpeedDiagnostics.record(this, "service_destroyed")
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (services.settings.read().speedNotificationEnabled) {
            NetworkSpeedDiagnostics.record(this, "task_removed")
            NetworkSpeedServiceController.scheduleRestart(this, "task_removed")
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
                if (
                    currentAt - lastWidgetUpdateAt >= WIDGET_SPEED_UPDATE_INTERVAL_MS &&
                    TrafficWidgetProvider.hasSpeedWidgets(this@NetworkSpeedService)
                ) {
                    lastWidgetUpdateAt = currentAt
                    NetworkSpeedSnapshotStore.write(
                        context = this@NetworkSpeedService,
                        downloadBytesPerSecond = rxPerSecond,
                        uploadBytesPerSecond = txPerSecond
                    )
                    TrafficWidgetProvider.updateSpeedWidgets(this@NetworkSpeedService)
                }
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
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val downloadSpeed = NetworkSpeedFormatter.format(rxPerSecond)
        val uploadSpeed = NetworkSpeedFormatter.format(txPerSecond)
        val legacyTitle = getString(
            R.string.network_speed_notification_legacy_title,
            downloadSpeed,
            uploadSpeed
        )
        val compactTitle = getString(
            R.string.network_speed_notification_compact_title,
            downloadSpeed,
            uploadSpeed
        )
        val totals = getString(
            R.string.network_speed_notification_totals,
            ByteFormatter.format(usage.mobileBytes),
            ByteFormatter.format(usage.wifiBytes)
        )
        val statusBarSpeed = maxOf(rxPerSecond, txPerSecond)
        val smallIcon = statusBarIconRenderer.create(
            NetworkSpeedFormatter.iconText(statusBarSpeed)
        )
        val title = when (notificationLayout) {
            NetworkSpeedNotificationLayout.LEGACY_ONE_UI_6 -> legacyTitle
            NetworkSpeedNotificationLayout.COMPACT_DUAL_BADGE -> compactTitle
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(totals)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(notificationShownAt)
            .setShowWhen(false)

        when (notificationLayout) {
            NetworkSpeedNotificationLayout.LEGACY_ONE_UI_6 -> builder
                .setLargeIcon(createLargeIcon(txPerSecond))
                .setStyle(Notification.BigTextStyle().bigText(totals))

            NetworkSpeedNotificationLayout.COMPACT_DUAL_BADGE -> {
                val views = createCompactNotificationViews(
                    title = compactTitle,
                    totals = totals,
                    downloadBytesPerSecond = rxPerSecond,
                    uploadBytesPerSecond = txPerSecond
                )
                builder
                    .setStyle(Notification.DecoratedCustomViewStyle())
                    .setCustomContentView(views)
                    .setCustomBigContentView(views)
                    .setCustomHeadsUpContentView(views)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun createCompactNotificationViews(
        title: String,
        totals: String,
        downloadBytesPerSecond: Long,
        uploadBytesPerSecond: Long
    ): RemoteViews = RemoteViews(packageName, R.layout.notification_network_speed_compact).apply {
        setTextViewText(R.id.network_speed_notification_title, title)
        setTextViewText(R.id.network_speed_notification_totals, totals)
        setImageViewBitmap(
            R.id.network_speed_notification_badges,
            createDualSpeedIcon(downloadBytesPerSecond, uploadBytesPerSecond)
        )
        setContentDescription(
            R.id.network_speed_notification_badges,
            getString(
                R.string.network_speed_notification_badges_values_description,
                NetworkSpeedFormatter.format(downloadBytesPerSecond),
                NetworkSpeedFormatter.format(uploadBytesPerSecond)
            )
        )
    }

    private fun createLargeIcon(bytesPerSecond: Long): Bitmap {
        val size = 144
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        drawSpeedBadge(
            canvas = canvas,
            centerX = size / 2f,
            bytesPerSecond = bytesPerSecond,
            color = UPLOAD_BADGE_COLOR
        )
        return bitmap
    }

    private fun createDualSpeedIcon(
        downloadBytesPerSecond: Long,
        uploadBytesPerSecond: Long
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(
            BADGE_SIZE * 2 + BADGE_GAP,
            BADGE_SIZE,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        drawSpeedBadge(
            canvas = canvas,
            centerX = BADGE_SIZE / 2f,
            bytesPerSecond = downloadBytesPerSecond,
            color = DOWNLOAD_BADGE_COLOR
        )
        drawSpeedBadge(
            canvas = canvas,
            centerX = BADGE_SIZE + BADGE_GAP + BADGE_SIZE / 2f,
            bytesPerSecond = uploadBytesPerSecond,
            color = UPLOAD_BADGE_COLOR
        )
        return bitmap
    }

    private fun drawSpeedBadge(
        canvas: Canvas,
        centerX: Float,
        bytesPerSecond: Long,
        color: Int
    ) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(centerX, BADGE_SIZE / 2f, BADGE_SIZE / 2f, background)
        val icon = NetworkSpeedFormatter.iconText(bytesPerSecond)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (icon.value.length >= 3) 48f else 56f
        }
        canvas.drawText(icon.value, centerX, 76f, paint)
        paint.textSize = if (icon.unit.length >= 4) 26f else 30f
        canvas.drawText(icon.unit, centerX, 112f, paint)
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
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Постоянная скорость загрузки и передачи данных"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun Long.validCounter(): Long = takeIf { it >= 0 } ?: 0

    private fun bytesPerSecond(current: Long, previous: Long, elapsedMs: Long): Long {
        if (current < previous) return 0
        return ((current - previous) * 1000L / elapsedMs).coerceAtLeast(0)
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                NetworkSpeedDiagnostics.recordHeartbeat(this@NetworkSpeedService)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "network_speed_status"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val TOTALS_REFRESH_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val WIDGET_SPEED_UPDATE_INTERVAL_MS = 5_000L
        private const val BADGE_SIZE = 144
        private const val BADGE_GAP = 12
        private val DOWNLOAD_BADGE_COLOR = Color.rgb(236, 103, 166)
        private val UPLOAD_BADGE_COLOR = Color.rgb(45, 124, 210)
        const val EXTRA_START_SOURCE = "start_source"

    }
}
