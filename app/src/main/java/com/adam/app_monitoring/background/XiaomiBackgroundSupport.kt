package com.adam.app_monitoring.background

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object XiaomiBackgroundSupport {
    private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"
    private const val AUTOSTART_ACTIVITY =
        "com.miui.permcenter.autostart.AutoStartManagementActivity"

    fun isXiaomiDevice(
        manufacturer: String = android.os.Build.MANUFACTURER,
        brand: String = android.os.Build.BRAND
    ): Boolean = listOf(manufacturer, brand).any {
        it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true)
    }

    fun autostartIntent(context: Context): Intent {
        val intent = Intent().setComponent(
            ComponentName(SECURITY_CENTER_PACKAGE, AUTOSTART_ACTIVITY)
        )
        return if (intent.resolveActivity(context.packageManager) != null) {
            intent
        } else {
            applicationDetailsIntent(context)
        }
    }

    fun applicationDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
}
