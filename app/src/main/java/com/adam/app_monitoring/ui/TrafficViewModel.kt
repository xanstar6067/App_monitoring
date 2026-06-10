package com.adam.app_monitoring.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.adam.app_monitoring.background.WorkScheduler
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.NetworkMode
import com.adam.app_monitoring.core.model.PermissionState
import com.adam.app_monitoring.core.model.SortMode
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficSnapshot
import com.adam.app_monitoring.data.ServiceLocator
import com.adam.app_monitoring.data.Services
import com.adam.app_monitoring.data.UsageAccessMissingException
import com.adam.app_monitoring.data.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab {
    OVERVIEW,
    APPS,
    CHARTS,
    SETTINGS
}

data class TrafficUiState(
    val period: TrafficPeriod = TrafficPeriod.TODAY,
    val networkMode: NetworkMode = NetworkMode.ALL,
    val sortMode: SortMode = SortMode.TODAY,
    val tab: MainTab = MainTab.OVERVIEW,
    val snapshot: TrafficSnapshot = TrafficSnapshot.empty(TrafficPeriod.TODAY),
    val permissions: PermissionState = PermissionState(false, false),
    val settings: UserSettings = UserSettings(),
    val selectedApp: AppTraffic? = null,
    val loading: Boolean = false,
    val error: String? = null
)

class TrafficViewModel(
    private val appContext: Context,
    private val services: Services
) : ViewModel() {
    private val _state = MutableStateFlow(
        TrafficUiState(
            permissions = services.permissions.state(),
            settings = services.settings.read(),
            snapshot = services.repository.loadCached(TrafficPeriod.TODAY)
                ?: TrafficSnapshot.empty(TrafficPeriod.TODAY)
        )
    )
    val state: StateFlow<TrafficUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        WorkScheduler.ensurePeriodic(appContext)
        maybeRefresh()
    }

    fun onResume() {
        val previous = _state.value.permissions.usageAccessGranted
        val current = services.permissions.state()
        _state.update { it.copy(permissions = current) }
        if (!previous && current.usageAccessGranted) refresh(forceAppScan = true)
    }

    fun setTab(tab: MainTab) {
        _state.update { it.copy(tab = tab, selectedApp = null) }
    }

    fun setPeriod(period: TrafficPeriod) {
        if (_state.value.period == period) return
        refreshJob?.cancel()
        val cached = services.repository.loadCached(period)
            ?: TrafficSnapshot.empty(period)
        _state.update {
            it.copy(
                period = period,
                snapshot = cached,
                selectedApp = null,
                error = null,
                loading = false
            )
        }
        maybeRefresh()
    }

    fun setNetworkMode(mode: NetworkMode) {
        _state.update { it.copy(networkMode = mode) }
    }

    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
    }

    fun selectApp(app: AppTraffic?) {
        _state.update { it.copy(selectedApp = app) }
    }

    fun refresh(forceAppScan: Boolean = false) {
        if (_state.value.loading) return
        val period = _state.value.period
        if (!_state.value.permissions.usageAccessGranted) {
            _state.update { it.copy(error = "Разрешите доступ к статистике использования") }
            return
        }
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val snapshot = services.repository.refreshSelected(
                    period = period,
                    forceAppScan = forceAppScan
                )
                _state.update { current ->
                    if (current.period == period) {
                        current.copy(
                            snapshot = snapshot,
                            loading = false,
                            permissions = services.permissions.state()
                        )
                    } else {
                        current.copy(loading = false)
                    }
                }
            } catch (_: UsageAccessMissingException) {
                _state.update {
                    it.copy(
                        loading = false,
                        permissions = services.permissions.state(),
                        error = "Нет доступа к статистике использования"
                    )
                }
            } catch (error: Exception) {
                services.settings.markRefreshError(
                    error.message ?: error.javaClass.simpleName
                )
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Не удалось обновить статистику"
                    )
                }
            }
        }
    }

    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        val updated = transform(_state.value.settings)
        services.settings.write(updated)
        _state.update { it.copy(settings = updated) }
        WorkScheduler.ensurePeriodic(appContext)
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            services.repository.clearCache()
            _state.update {
                it.copy(
                    snapshot = TrafficSnapshot.empty(it.period),
                    selectedApp = null
                )
            }
        }
    }

    suspend fun loadIcon(app: AppTraffic) = withContext(Dispatchers.IO) {
        services.icons.load(app.app.packageName, app.app.iconCachePath)
    }

    fun diagnosticLastRefreshAt(): Long = services.settings.lastSuccessfulRefreshAt()

    fun diagnosticLastBootAt(): Long =
        services.settings.lastBootCompletedReceivedAt(appContext)

    fun diagnosticLastBootRefreshAt(): Long =
        services.settings.lastBootRefreshSuccessAt()

    fun diagnosticLastError(): String? = services.settings.lastError()

    private fun maybeRefresh() {
        val snapshot = _state.value.snapshot
        val stale = System.currentTimeMillis() - snapshot.calculatedAt >= CACHE_FRESH_MS
        if (_state.value.permissions.usageAccessGranted && stale) refresh()
    }

    companion object {
        private const val CACHE_FRESH_MS = 10 * 60 * 1000L

        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    return TrafficViewModel(
                        appContext,
                        ServiceLocator.from(appContext)
                    ) as T
                }
            }
    }
}
