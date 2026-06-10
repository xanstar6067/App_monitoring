package com.adam.app_monitoring.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.adam.app_monitoring.core.model.AppRecord
import java.io.File

class AppCatalog(
    private val context: Context,
    private val database: TrafficDatabase,
    private val settingsStore: SettingsStore
) {
    fun load(forceScan: Boolean = false): List<AppRecord> {
        val now = System.currentTimeMillis()
        val cached = database.loadApps()
        val stale = now - settingsStore.lastAppScanAt() >= APP_SCAN_INTERVAL_MS
        if (!forceScan && !stale && cached.isNotEmpty()) return cached

        val packageManager = context.packageManager
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        val iconDirectory = File(context.cacheDir, "app_icons").apply { mkdirs() }
        val records = installed
            .groupBy { it.uid }
            .map { (uid, group) ->
                val sorted = group.sortedBy { it.packageName }
                val primary = sorted.first()
                val labels = sorted.map { info ->
                    runCatching {
                        packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(info.packageName)
                }
                AppRecord(
                    packageName = primary.packageName,
                    uid = uid,
                    appName = if (labels.size == 1) {
                        labels.first()
                    } else {
                        "${labels.first()} + ещё ${labels.size - 1}"
                    },
                    iconCachePath = File(
                        iconDirectory,
                        "${primary.packageName.hashCode().toUInt().toString(16)}.png"
                    ).absolutePath,
                    isSystemApp = sorted.all { info ->
                        info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    },
                    lastSeenAt = now
                )
            }
            .sortedBy { it.appName.lowercase() }
        database.replaceAppCatalog(records)
        settingsStore.setLastAppScanAt(now)
        return records
    }

    private companion object {
        const val APP_SCAN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
