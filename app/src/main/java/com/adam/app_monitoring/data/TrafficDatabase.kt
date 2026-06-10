package com.adam.app_monitoring.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.adam.app_monitoring.core.model.AppRecord
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.ChartPoint
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficSnapshot
import com.adam.app_monitoring.core.model.TrafficUsage

data class TrafficRow(
    val app: AppRecord,
    val usage: TrafficUsage
)

class TrafficDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE apps (
                package_name TEXT PRIMARY KEY,
                uid INTEGER NOT NULL,
                app_name TEXT NOT NULL,
                icon_cache_path TEXT,
                is_system INTEGER NOT NULL,
                is_removed INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_apps_uid ON apps(uid)")
        db.execSQL(
            """
            CREATE TABLE traffic_daily (
                date TEXT NOT NULL,
                uid INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                wifi_rx_bytes INTEGER NOT NULL,
                wifi_tx_bytes INTEGER NOT NULL,
                mobile_rx_bytes INTEGER NOT NULL,
                mobile_tx_bytes INTEGER NOT NULL,
                total_rx_bytes INTEGER NOT NULL,
                total_tx_bytes INTEGER NOT NULL,
                calculated_at INTEGER NOT NULL,
                PRIMARY KEY(date, uid, package_name)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE traffic_period_cache (
                period_start INTEGER NOT NULL,
                period_end INTEGER NOT NULL,
                uid INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                wifi_rx_bytes INTEGER NOT NULL,
                wifi_tx_bytes INTEGER NOT NULL,
                mobile_rx_bytes INTEGER NOT NULL,
                mobile_tx_bytes INTEGER NOT NULL,
                total_rx_bytes INTEGER NOT NULL,
                total_tx_bytes INTEGER NOT NULL,
                calculated_at INTEGER NOT NULL,
                PRIMARY KEY(period_start, uid, package_name)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE chart_cache (
                period_start INTEGER NOT NULL,
                bucket_start INTEGER NOT NULL,
                label TEXT NOT NULL,
                wifi_bytes INTEGER NOT NULL,
                mobile_bytes INTEGER NOT NULL,
                calculated_at INTEGER NOT NULL,
                PRIMARY KEY(period_start, bucket_start)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceAppCatalog(apps: List<AppRecord>) {
        val db = writableDatabase
        db.inTransaction {
            db.execSQL("UPDATE apps SET is_removed = 1")
            apps.forEach { upsertApp(db, it) }
        }
    }

    fun loadApps(): List<AppRecord> {
        val result = mutableListOf<AppRecord>()
        readableDatabase.query(
            "apps",
            APP_COLUMNS,
            null,
            null,
            null,
            null,
            "app_name COLLATE NOCASE"
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toAppRecord()
        }
        return result
    }

    fun saveDaily(
        date: String,
        rows: List<TrafficRow>,
        calculatedAt: Long
    ) {
        val db = writableDatabase
        db.inTransaction {
            db.delete("traffic_daily", "date = ?", arrayOf(date))
            rows.forEach { row ->
                upsertApp(db, row.app)
                db.insertWithOnConflict(
                    "traffic_daily",
                    null,
                    trafficValues(row, calculatedAt).apply { put("date", date) },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        }
    }

    fun savePeriod(
        periodStart: Long,
        periodEnd: Long,
        rows: List<TrafficRow>,
        chart: List<ChartPoint>?,
        calculatedAt: Long
    ) {
        val db = writableDatabase
        db.inTransaction {
            db.delete(
                "traffic_period_cache",
                "period_start = ?",
                arrayOf(periodStart.toString())
            )
            rows.forEach { row ->
                upsertApp(db, row.app)
                db.insertWithOnConflict(
                    "traffic_period_cache",
                    null,
                    trafficValues(row, calculatedAt).apply {
                        put("period_start", periodStart)
                        put("period_end", periodEnd)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            if (chart != null) {
                db.delete(
                    "chart_cache",
                    "period_start = ?",
                    arrayOf(periodStart.toString())
                )
                chart.forEach { point ->
                    db.insertWithOnConflict(
                        "chart_cache",
                        null,
                        ContentValues().apply {
                            put("period_start", periodStart)
                            put("bucket_start", point.bucketStart)
                            put("label", point.label)
                            put("wifi_bytes", point.wifiBytes)
                            put("mobile_bytes", point.mobileBytes)
                            put("calculated_at", calculatedAt)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
            }
        }
    }

    fun loadSnapshot(
        period: TrafficPeriod,
        periodStart: Long,
        todayDate: String
    ): TrafficSnapshot? {
        val apps = mutableListOf<AppTraffic>()
        var periodEnd = 0L
        var calculatedAt = 0L
        val sql =
            """
            SELECT
                p.package_name, p.uid,
                COALESCE(a.app_name, 'Удалённое или неизвестное приложение'),
                a.icon_cache_path,
                COALESCE(a.is_system, 0),
                COALESCE(a.is_removed, 1),
                COALESCE(a.last_seen_at, 0),
                p.wifi_rx_bytes, p.wifi_tx_bytes,
                p.mobile_rx_bytes, p.mobile_tx_bytes,
                COALESCE(d.wifi_rx_bytes, 0), COALESCE(d.wifi_tx_bytes, 0),
                COALESCE(d.mobile_rx_bytes, 0), COALESCE(d.mobile_tx_bytes, 0),
                p.period_end, p.calculated_at
            FROM traffic_period_cache p
            LEFT JOIN apps a ON a.package_name = p.package_name
            LEFT JOIN traffic_daily d
                ON d.date = ? AND d.uid = p.uid AND d.package_name = p.package_name
            WHERE p.period_start = ?
            """.trimIndent()
        readableDatabase.rawQuery(
            sql,
            arrayOf(todayDate, periodStart.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val app = AppRecord(
                    packageName = cursor.getString(0),
                    uid = cursor.getInt(1),
                    appName = cursor.getString(2),
                    iconCachePath = cursor.getStringOrNull(3),
                    isSystemApp = cursor.getInt(4) != 0,
                    isRemoved = cursor.getInt(5) != 0,
                    lastSeenAt = cursor.getLong(6)
                )
                val periodUsage = TrafficUsage(
                    wifiRxBytes = cursor.getLong(7),
                    wifiTxBytes = cursor.getLong(8),
                    mobileRxBytes = cursor.getLong(9),
                    mobileTxBytes = cursor.getLong(10)
                )
                val todayUsage = if (period == TrafficPeriod.TODAY) {
                    periodUsage
                } else {
                    TrafficUsage(
                        wifiRxBytes = cursor.getLong(11),
                        wifiTxBytes = cursor.getLong(12),
                        mobileRxBytes = cursor.getLong(13),
                        mobileTxBytes = cursor.getLong(14)
                    )
                }
                periodEnd = maxOf(periodEnd, cursor.getLong(15))
                calculatedAt = maxOf(calculatedAt, cursor.getLong(16))
                apps += AppTraffic(app, periodUsage, todayUsage)
            }
        }
        if (apps.isEmpty()) return null

        val chart = mutableListOf<ChartPoint>()
        readableDatabase.query(
            "chart_cache",
            arrayOf("bucket_start", "label", "wifi_bytes", "mobile_bytes"),
            "period_start = ?",
            arrayOf(periodStart.toString()),
            null,
            null,
            "bucket_start"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                chart += ChartPoint(
                    bucketStart = cursor.getLong(0),
                    label = cursor.getString(1),
                    wifiBytes = cursor.getLong(2),
                    mobileBytes = cursor.getLong(3)
                )
            }
        }
        return TrafficSnapshot(
            period = period,
            periodStart = periodStart,
            periodEnd = periodEnd,
            apps = apps,
            chart = chart,
            calculatedAt = calculatedAt
        )
    }

    fun clearTrafficCache() {
        val db = writableDatabase
        db.inTransaction {
            db.delete("traffic_daily", null, null)
            db.delete("traffic_period_cache", null, null)
            db.delete("chart_cache", null, null)
        }
    }

    private fun upsertApp(db: SQLiteDatabase, app: AppRecord) {
        db.insertWithOnConflict(
            "apps",
            null,
            ContentValues().apply {
                put("package_name", app.packageName)
                put("uid", app.uid)
                put("app_name", app.appName)
                put("icon_cache_path", app.iconCachePath)
                put("is_system", app.isSystemApp.asInt())
                put("is_removed", app.isRemoved.asInt())
                put("last_seen_at", app.lastSeenAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun trafficValues(row: TrafficRow, calculatedAt: Long) = ContentValues().apply {
        put("uid", row.app.uid)
        put("package_name", row.app.packageName)
        put("wifi_rx_bytes", row.usage.wifiRxBytes)
        put("wifi_tx_bytes", row.usage.wifiTxBytes)
        put("mobile_rx_bytes", row.usage.mobileRxBytes)
        put("mobile_tx_bytes", row.usage.mobileTxBytes)
        put("total_rx_bytes", row.usage.rxBytes)
        put("total_tx_bytes", row.usage.txBytes)
        put("calculated_at", calculatedAt)
    }

    private fun Cursor.toAppRecord() = AppRecord(
        packageName = getString(0),
        uid = getInt(1),
        appName = getString(2),
        iconCachePath = getStringOrNull(3),
        isSystemApp = getInt(4) != 0,
        isRemoved = getInt(5) != 0,
        lastSeenAt = getLong(6)
    )

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Boolean.asInt() = if (this) 1 else 0

    private inline fun SQLiteDatabase.inTransaction(block: () -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val DATABASE_NAME = "traffic_monitor.db"
        const val DATABASE_VERSION = 1
        val APP_COLUMNS = arrayOf(
            "package_name",
            "uid",
            "app_name",
            "icon_cache_path",
            "is_system",
            "is_removed",
            "last_seen_at"
        )
    }
}
