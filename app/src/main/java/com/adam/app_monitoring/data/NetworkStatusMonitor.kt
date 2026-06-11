package com.adam.app_monitoring.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

enum class ActiveConnection {
    WIFI,
    MOBILE,
    ETHERNET,
    OTHER,
    NONE
}

data class NetworkStatus(
    val connection: ActiveConnection = ActiveConnection.NONE,
    val connected: Boolean = false,
    val validated: Boolean = false,
    val vpnActive: Boolean = false,
    val localIp: String? = null,
    val externalIp: String? = null,
    val externalIpLoading: Boolean = false,
    val wifiSsid: String? = null,
    val wifiBssid: String? = null,
    val deviceMac: String? = null,
    val wifiFrequencyMhz: Int? = null,
    val wifiSignalDbm: Int? = null,
    val wifiSignalLevel: Int? = null,
    val mobileSignalDbm: Int? = null,
    val mobileSignalLevel: Int? = null,
    val updatedAt: Long = 0L
)

private data class SignalReading(
    val dbm: Int?,
    val level: Int?
)

class NetworkStatusMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)

    private val _status = MutableStateFlow(NetworkStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private var monitorJob: Job? = null
    private var externalIp: String? = null
    private var externalIpNetwork: Network? = null
    private var externalIpCheckedAt = 0L

    fun start(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val snapshot = safelyRead { readStatus() }
                    ?: NetworkStatus(updatedAt = System.currentTimeMillis())
                val activeExternalIp = externalIp.takeIf {
                    snapshot.connected && connectivity.activeNetwork == externalIpNetwork
                }
                _status.value = snapshot.copy(
                    externalIp = activeExternalIp,
                    externalIpLoading = shouldRefreshExternalIp(snapshot)
                )
                if (shouldRefreshExternalIp(snapshot)) {
                    refreshExternalIp(snapshot)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun refresh() {
        externalIpCheckedAt = 0L
    }

    @SuppressLint("MissingPermission")
    private fun readStatus(): NetworkStatus {
        val activeNetwork = connectivity.activeNetwork
        val activeCapabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val vpnActive = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val transportNetwork = selectTransportNetwork(activeNetwork, activeCapabilities)
        val capabilities = transportNetwork?.let(connectivity::getNetworkCapabilities)
            ?: activeCapabilities
        val linkProperties = transportNetwork?.let(connectivity::getLinkProperties)
            ?: activeNetwork?.let(connectivity::getLinkProperties)
        val connection = connectionType(capabilities)
        val wifiInfo = if (connection == ActiveConnection.WIFI) {
            safelyRead { readWifiInfo(capabilities) }
        } else {
            null
        }
        val cellSignal = if (connection == ActiveConnection.MOBILE) {
            safelyRead { readMobileSignal() }
        } else {
            null
        }

        return NetworkStatus(
            connection = connection,
            connected = activeNetwork != null,
            validated = activeCapabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true,
            vpnActive = vpnActive,
            localIp = localIp(linkProperties),
            externalIp = externalIp,
            wifiSsid = wifiInfo?.ssid?.cleanWifiValue(),
            wifiBssid = wifiInfo?.bssid?.cleanWifiValue(),
            deviceMac = wifiInfo?.macAddress?.takeUnless { it == REDACTED_MAC },
            wifiFrequencyMhz = wifiInfo?.frequency?.takeIf { it > 0 },
            wifiSignalDbm = wifiInfo?.rssi?.takeIf { it in MIN_VALID_DBM..MAX_VALID_DBM },
            wifiSignalLevel = wifiInfo?.rssi
                ?.takeIf { it in MIN_VALID_DBM..MAX_VALID_DBM }
                ?.let { WifiManager.calculateSignalLevel(it, SIGNAL_LEVELS) },
            mobileSignalDbm = cellSignal?.dbm,
            mobileSignalLevel = cellSignal?.level,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun selectTransportNetwork(
        activeNetwork: Network?,
        activeCapabilities: NetworkCapabilities?
    ): Network? {
        if (connectionType(activeCapabilities) != ActiveConnection.OTHER) {
            return activeNetwork
        }
        return connectivity.allNetworks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                connectionType(capabilities) in setOf(
                    ActiveConnection.WIFI,
                    ActiveConnection.MOBILE,
                    ActiveConnection.ETHERNET
                )
        }
    }

    private fun connectionType(capabilities: NetworkCapabilities?): ActiveConnection =
        when {
            capabilities == null -> ActiveConnection.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                ActiveConnection.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                ActiveConnection.MOBILE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                ActiveConnection.ETHERNET
            else -> ActiveConnection.OTHER
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun readWifiInfo(capabilities: NetworkCapabilities?): WifiInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            capabilities?.transportInfo as? WifiInfo
        } else {
            wifi.connectionInfo
        }

    @SuppressLint("MissingPermission")
    private fun readMobileSignal(): SignalReading? {
        val signalStrength = telephony.signalStrength ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signalStrength.cellSignalStrengths
                .maxByOrNull { it.level }
                ?.let { signal ->
                    SignalReading(
                        dbm = signal.dbm.takeIf(::isValidDbm),
                        level = signal.level.takeIf(::isValidSignalLevel)
                    )
                }
                ?: signalStrength.toLevelOnlyReading()
        } else {
            signalStrength.toLegacySignalReading()
        }
    }

    private fun SignalStrength.toLevelOnlyReading() = SignalReading(
        dbm = null,
        level = level.takeIf(::isValidSignalLevel)
    )

    @Suppress("DEPRECATION")
    private fun SignalStrength.toLegacySignalReading(): SignalReading {
        val gsmDbm = gsmSignalStrength
            .takeIf { it in MIN_GSM_ASU..MAX_GSM_ASU }
            ?.let { GSM_DBM_OFFSET + GSM_DBM_MULTIPLIER * it }
        val cdmaDbm = listOf(cdmaDbm, evdoDbm)
            .filter(::isValidDbm)
            .maxOrNull()

        return SignalReading(
            dbm = gsmDbm ?: cdmaDbm,
            level = level.takeIf(::isValidSignalLevel)
        )
    }

    private fun isValidDbm(dbm: Int) = dbm in MIN_VALID_DBM..MAX_VALID_DBM

    private fun isValidSignalLevel(level: Int) = level in 0..4

    private inline fun <T> safelyRead(block: () -> T): T? =
        try {
            block()
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: LinkageError) {
            // Some vendor Android builds expose framework APIs inconsistently.
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun localIp(linkProperties: LinkProperties?): String? =
        linkProperties
            ?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
            ?: linkProperties
                ?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress

    private fun shouldRefreshExternalIp(status: NetworkStatus): Boolean {
        if (!status.connected || !status.validated) return false
        val networkChanged = connectivity.activeNetwork != externalIpNetwork
        val stale = System.currentTimeMillis() - externalIpCheckedAt >= EXTERNAL_IP_REFRESH_MS
        return networkChanged || stale || externalIpCheckedAt == 0L
    }

    private fun refreshExternalIp(status: NetworkStatus) {
        val network = connectivity.activeNetwork ?: return
        val checkedAt = System.currentTimeMillis()
        val result = runCatching {
            val connection = network.openConnection(URL(EXTERNAL_IP_URL)) as HttpURLConnection
            try {
                connection.connectTimeout = HTTP_TIMEOUT_MS
                connection.readTimeout = HTTP_TIMEOUT_MS
                connection.setRequestProperty("Accept", "text/plain")
                connection.inputStream.bufferedReader().use { reader ->
                    reader.readText().trim().takeIf { it.length in 3..64 }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        externalIp = result
        externalIpNetwork = network
        externalIpCheckedAt = checkedAt
        _status.update {
            status.copy(
                externalIp = result,
                externalIpLoading = false,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun String.cleanWifiValue(): String? =
        removeSurrounding("\"")
            .takeUnless {
                it.isBlank() ||
                    it.equals("<unknown ssid>", ignoreCase = true) ||
                    it == REDACTED_MAC
            }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val EXTERNAL_IP_REFRESH_MS = 60_000L
        const val HTTP_TIMEOUT_MS = 4_000
        const val SIGNAL_LEVELS = 5
        const val MIN_VALID_DBM = -200
        const val MAX_VALID_DBM = 0
        const val MIN_GSM_ASU = 0
        const val MAX_GSM_ASU = 31
        const val GSM_DBM_OFFSET = -113
        const val GSM_DBM_MULTIPLIER = 2
        const val REDACTED_MAC = "02:00:00:00:00:00"
        const val EXTERNAL_IP_URL = "https://api.ipify.org"
    }
}
