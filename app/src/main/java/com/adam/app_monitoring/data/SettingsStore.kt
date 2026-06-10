package com.adam.app_monitoring.data

import android.content.Context
import com.adam.app_monitoring.core.util.ByteUnitPreference

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

enum class UpdateInterval(val minutes: Long) {
    MINUTES_15(15),
    MINUTES_30(30),
    HOUR_1(60),
    HOURS_3(180),
    HOURS_6(360)
}

enum class WidgetUpdateInterval(val minutes: Long) {
    MINUTES_5(5),
    MINUTES_15(15),
    MINUTES_30(30),
    HOUR_1(60),
    HOURS_3(180)
}

data class UserSettings(
    val backgroundEnabled: Boolean = true,
    val updateInterval: UpdateInterval = UpdateInterval.HOUR_1,
    val widgetUpdateInterval: WidgetUpdateInterval = WidgetUpdateInterval.MINUTES_5,
    val requireBatteryNotLow: Boolean = true,
    val refreshAfterBoot: Boolean = true,
    val trafficLimitNotificationsEnabled: Boolean = false,
    val monthlyTrafficLimitMb: Int = 10_240,
    val trafficWarningPercent: Int = 80,
    val billingCycleStartDay: Int = 1,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val units: ByteUnitPreference = ByteUnitPreference.AUTO,
    val showPackageName: Boolean = true,
    val showSystemApps: Boolean = true,
    val showAppsWithoutTraffic: Boolean = false
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): UserSettings = UserSettings(
        backgroundEnabled = prefs.getBoolean(KEY_BACKGROUND_ENABLED, true),
        updateInterval = enumValueOrDefault(
            prefs.getString(KEY_INTERVAL, null),
            UpdateInterval.HOUR_1
        ),
        widgetUpdateInterval = enumValueOrDefault(
            prefs.getString(KEY_WIDGET_INTERVAL, null),
            WidgetUpdateInterval.MINUTES_5
        ),
        requireBatteryNotLow = prefs.getBoolean(KEY_BATTERY_NOT_LOW, true),
        refreshAfterBoot = prefs.getBoolean(KEY_REFRESH_AFTER_BOOT, true),
        trafficLimitNotificationsEnabled = prefs.getBoolean(KEY_LIMIT_NOTIFICATIONS, false),
        monthlyTrafficLimitMb = prefs.getInt(KEY_MONTHLY_LIMIT_MB, 10_240)
            .coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB),
        trafficWarningPercent = prefs.getInt(KEY_WARNING_PERCENT, 80).coerceIn(1, 100),
        billingCycleStartDay = prefs.getInt(KEY_BILLING_DAY, 1).coerceIn(1, 31),
        theme = enumValueOrDefault(prefs.getString(KEY_THEME, null), ThemePreference.SYSTEM),
        units = enumValueOrDefault(prefs.getString(KEY_UNITS, null), ByteUnitPreference.AUTO),
        showPackageName = prefs.getBoolean(KEY_SHOW_PACKAGE, true),
        showSystemApps = prefs.getBoolean(KEY_SHOW_SYSTEM, true),
        showAppsWithoutTraffic = prefs.getBoolean(KEY_SHOW_ZERO, false)
    )

    fun write(settings: UserSettings) {
        prefs.edit()
            .putBoolean(KEY_BACKGROUND_ENABLED, settings.backgroundEnabled)
            .putString(KEY_INTERVAL, settings.updateInterval.name)
            .putString(KEY_WIDGET_INTERVAL, settings.widgetUpdateInterval.name)
            .putBoolean(KEY_BATTERY_NOT_LOW, settings.requireBatteryNotLow)
            .putBoolean(KEY_REFRESH_AFTER_BOOT, settings.refreshAfterBoot)
            .putBoolean(KEY_LIMIT_NOTIFICATIONS, settings.trafficLimitNotificationsEnabled)
            .putInt(
                KEY_MONTHLY_LIMIT_MB,
                settings.monthlyTrafficLimitMb.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
            )
            .putInt(KEY_WARNING_PERCENT, settings.trafficWarningPercent.coerceIn(1, 100))
            .putInt(KEY_BILLING_DAY, settings.billingCycleStartDay.coerceIn(1, 31))
            .putString(KEY_THEME, settings.theme.name)
            .putString(KEY_UNITS, settings.units.name)
            .putBoolean(KEY_SHOW_PACKAGE, settings.showPackageName)
            .putBoolean(KEY_SHOW_SYSTEM, settings.showSystemApps)
            .putBoolean(KEY_SHOW_ZERO, settings.showAppsWithoutTraffic)
            .apply()
    }

    fun lastAppScanAt(): Long = prefs.getLong(KEY_LAST_APP_SCAN, 0)

    fun setLastAppScanAt(value: Long) {
        prefs.edit().putLong(KEY_LAST_APP_SCAN, value).apply()
    }

    fun lastSuccessfulRefreshAt(): Long = prefs.getLong(KEY_LAST_REFRESH, 0)

    fun lastSuccessfulDate(): String? = prefs.getString(KEY_LAST_SUCCESS_DATE, null)

    fun markRefreshSuccess(at: Long, date: String) {
        prefs.edit()
            .putLong(KEY_LAST_REFRESH, at)
            .putString(KEY_LAST_SUCCESS_DATE, date)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun markRefreshError(message: String) {
        prefs.edit().putString(KEY_LAST_ERROR, message.take(300)).apply()
    }

    fun lastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    fun lastLimitNotificationPeriod(): String? =
        prefs.getString(KEY_LAST_LIMIT_NOTIFICATION_PERIOD, null)

    fun markLimitNotificationPeriod(periodKey: String) {
        prefs.edit().putString(KEY_LAST_LIMIT_NOTIFICATION_PERIOD, periodKey).apply()
    }

    fun lastBootRefreshSuccessAt(): Long = prefs.getLong(KEY_LAST_BOOT_REFRESH, 0)

    fun markBootRefreshSuccess(at: Long) {
        prefs.edit().putLong(KEY_LAST_BOOT_REFRESH, at).apply()
    }

    fun markBootCompletedReceived(context: Context, at: Long) {
        recordDirectBootSignal(context, at)
    }

    fun lastBootCompletedReceivedAt(context: Context): Long {
        val directContext = context.createDeviceProtectedStorageContext()
        return directContext.getSharedPreferences(DIRECT_PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_BOOT_RECEIVED, 0)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        raw: String?,
        default: T
    ): T = runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(default)

    companion object {
        fun recordDirectBootSignal(context: Context, at: Long) {
            val directContext = context.createDeviceProtectedStorageContext()
            directContext.getSharedPreferences(DIRECT_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_BOOT_RECEIVED, at)
                .apply()
        }

        const val PREFS_NAME = "traffic_settings"
        private const val DIRECT_PREFS_NAME = "traffic_direct_boot"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
        const val KEY_INTERVAL = "update_interval"
        const val KEY_WIDGET_INTERVAL = "widget_update_interval"
        const val KEY_BATTERY_NOT_LOW = "battery_not_low"
        const val KEY_REFRESH_AFTER_BOOT = "refresh_after_boot"
        const val KEY_LIMIT_NOTIFICATIONS = "limit_notifications"
        const val KEY_MONTHLY_LIMIT_MB = "monthly_limit_mb"
        const val KEY_WARNING_PERCENT = "warning_percent"
        const val KEY_BILLING_DAY = "billing_day"
        const val KEY_THEME = "theme"
        const val KEY_UNITS = "units"
        const val KEY_SHOW_PACKAGE = "show_package"
        const val KEY_SHOW_SYSTEM = "show_system"
        const val KEY_SHOW_ZERO = "show_zero"
        const val KEY_LAST_APP_SCAN = "last_app_scan"
        const val KEY_LAST_REFRESH = "last_refresh"
        const val KEY_LAST_SUCCESS_DATE = "last_success_date"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_LAST_LIMIT_NOTIFICATION_PERIOD = "last_limit_notification_period"
        private const val KEY_LAST_BOOT_RECEIVED = "last_boot_received"
        const val KEY_LAST_BOOT_REFRESH = "last_boot_refresh"
        const val MIN_LIMIT_MB = 1
        const val MAX_LIMIT_MB = 100_000_000
    }
}
