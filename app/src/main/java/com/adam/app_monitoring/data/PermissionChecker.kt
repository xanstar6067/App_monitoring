@file:Suppress("DEPRECATION")

package com.adam.app_monitoring.data

import android.app.AppOpsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import com.adam.app_monitoring.core.model.PermissionState

class PermissionChecker(private val context: Context) {
    fun state(): PermissionState = PermissionState(
        usageAccessGranted = hasUsageAccess(),
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations()
    )

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
