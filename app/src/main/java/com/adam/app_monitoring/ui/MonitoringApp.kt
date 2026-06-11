package com.adam.app_monitoring.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.ChartPoint
import com.adam.app_monitoring.core.model.NetworkMode
import com.adam.app_monitoring.core.model.SortMode
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.ByteFormatter
import com.adam.app_monitoring.background.XiaomiBackgroundSupport
import com.adam.app_monitoring.data.ThemePreference
import com.adam.app_monitoring.data.UpdateInterval
import com.adam.app_monitoring.data.WidgetUpdateInterval
import com.adam.app_monitoring.data.SettingsStore
import com.adam.app_monitoring.data.ActiveConnection
import com.adam.app_monitoring.data.NetworkStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private val StatusGreen = Color(0xFF2E7D32)
private val StatusAmber = Color(0xFFF9A825)
private val StatusRed = Color(0xFFC62828)

private enum class NotificationPermissionTarget {
    NONE,
    SPEED,
    LIMIT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringApp(
    state: TrafficUiState,
    viewModel: TrafficViewModel,
    onExit: () -> Unit
) {
    val selected = state.selectedApp
    val context = LocalContext.current
    var lastBackPressAt by remember { mutableLongStateOf(0L) }

    BackHandler {
        if (selected != null) {
            viewModel.selectApp(null)
            return@BackHandler
        }
        if (state.selectedChartPoint != null) {
            viewModel.clearChartSelection()
            return@BackHandler
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressAt <= EXIT_CONFIRMATION_WINDOW_MS) {
            onExit()
        } else {
            lastBackPressAt = now
            Toast.makeText(
                context,
                "Нажмите ещё раз для выхода",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            selected != null -> selected.app.appName
                            state.tab == MainTab.STATUS -> "Статус сети"
                            else -> "Трафик приложений"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (selected != null) {
                        TextButton(onClick = { viewModel.selectApp(null) }) {
                            Text("Назад")
                        }
                    }
                },
                actions = {
                    if (
                        selected == null &&
                        state.tab in setOf(MainTab.OVERVIEW, MainTab.APPS)
                    ) {
                        TextButton(
                            enabled = !state.loading,
                            onClick = { viewModel.refresh(forceAppScan = true) }
                        ) {
                            Text(if (state.loading) "Обновление..." else "Обновить")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (selected == null) {
                BottomNavigation(
                    selected = state.tab,
                    onSelected = viewModel::setTab
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selected != null) {
                AppDetailScreen(
                    app = selected,
                    units = state.settings.units,
                    intervalSelected = state.selectedChartPoint != null,
                    loadIcon = viewModel::loadIcon
                )
            } else {
                when (state.tab) {
                    MainTab.OVERVIEW -> OverviewScreen(state, viewModel)
                    MainTab.APPS -> AppsScreen(state, viewModel)
                    MainTab.STATUS -> StatusScreen(state, viewModel)
                    MainTab.SETTINGS -> SettingsScreen(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(
    selected: MainTab,
    onSelected: (MainTab) -> Unit
) {
    NavigationBar(modifier = Modifier.navigationBarsPadding()) {
        NavigationBarItem(
            selected = selected == MainTab.OVERVIEW,
            onClick = { onSelected(MainTab.OVERVIEW) },
            icon = { Text("О") },
            label = { Text("Обзор") }
        )
        NavigationBarItem(
            selected = selected == MainTab.APPS,
            onClick = { onSelected(MainTab.APPS) },
            icon = { Text("П") },
            label = { Text("Приложения") }
        )
        NavigationBarItem(
            selected = selected == MainTab.STATUS,
            onClick = { onSelected(MainTab.STATUS) },
            icon = { Text("С") },
            label = { Text("Статус") }
        )
        NavigationBarItem(
            selected = selected == MainTab.SETTINGS,
            onClick = { onSelected(MainTab.SETTINGS) },
            icon = { Text("Н") },
            label = { Text("Настройки") }
        )
    }
}

@Composable
private fun OverviewScreen(state: TrafficUiState, viewModel: TrafficViewModel) {
    val apps = filteredApps(state)
    val intervalTitle = selectedIntervalTitle(state)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            PermissionBanner(state, viewModel)
        }
        item {
            PeriodSelector(state.period, viewModel::setPeriod)
        }
        item {
            NetworkSelector(state.networkMode, viewModel::setNetworkMode)
        }
        if (state.error != null) {
            item { ErrorCard(state.error) }
        }
        item {
            ChartCard(
                points = state.snapshot.chart,
                mode = state.networkMode,
                total = state.snapshot.totalUsage.bytesFor(state.networkMode),
                trafficRemainingBytes = state.trafficRemainingBytes,
                units = state.settings.units,
                selectedPoint = state.selectedChartPoint,
                onPointSelected = viewModel::selectChartPoint,
                onSelectionCleared = viewModel::clearChartSelection
            )
        }
        item {
            SummaryCard(
                usage = state.snapshot.totalUsage,
                units = state.settings.units,
                todayUsage = state.snapshot.todayTotalUsage
                    .takeIf { state.period != TrafficPeriod.TODAY }
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    intervalTitle?.let { "Приложения за $it" } ?: "Приложения",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (state.loading || state.intervalLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }
        }
        val visibleApps = if (state.selectedChartPoint == null) apps.take(20) else apps
        items(visibleApps, key = { it.app.packageName }) { app ->
            AppTrafficRow(
                app = app,
                period = state.period,
                intervalLabel = intervalTitle,
                settings = state.settings,
                onClick = { viewModel.selectApp(app) },
                loadIcon = viewModel::loadIcon
            )
        }
        if (!state.loading && !state.intervalLoading && apps.isEmpty()) {
            item { EmptyDataCard(state.permissions.usageAccessGranted) }
        }
    }
}

@Composable
private fun AppsScreen(state: TrafficUiState, viewModel: TrafficViewModel) {
    val apps = filteredApps(state)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            PeriodSelector(state.period, viewModel::setPeriod)
        }
        item {
            NetworkSelector(state.networkMode, viewModel::setNetworkMode)
        }
        item {
            SortSelector(state.sortMode, viewModel::setSortMode)
        }
        items(apps, key = { it.app.packageName }) { app ->
            AppTrafficRow(
                app = app,
                period = state.period,
                settings = state.settings,
                onClick = { viewModel.selectApp(app) },
                loadIcon = viewModel::loadIcon
            )
        }
        if (!state.loading && apps.isEmpty()) {
            item { EmptyDataCard(state.permissions.usageAccessGranted) }
        }
    }
}

@Composable
private fun StatusScreen(state: TrafficUiState, viewModel: TrafficViewModel) {
    val context = LocalContext.current
    val status = state.networkStatus
    val locationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val nearbyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    val wifiDetailsGranted = locationGranted && nearbyGranted
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshNetworkStatus()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StatusCard("Подключение") {
                StatusLine("Используется", status.connection.label())
                StatusLine("Доступ в интернет", yesNo(status.validated))
                StatusLine("VPN", if (status.vpnActive) "Включён" else "Не обнаружен")
                StatusLine("Локальный IP", status.localIp ?: "Нет данных")
                StatusLine(
                    "Внешний IP",
                    when {
                        status.externalIpLoading -> "Определяется..."
                        status.externalIp != null -> status.externalIp
                        status.connected -> "Не удалось определить"
                        else -> "Нет подключения"
                    }
                )
            }
        }
        if (status.connection == ActiveConnection.WIFI) {
            item {
                StatusCard("Wi-Fi") {
                    StatusLine("Сеть", status.wifiSsid ?: "Нет доступа к имени")
                    StatusLine("Диапазон", wifiBand(status.wifiFrequencyMhz))
                    StatusLine(
                        "Частота",
                        status.wifiFrequencyMhz?.let { "$it МГц" } ?: "Нет данных"
                    )
                    StatusLine(
                        "Уровень сигнала",
                        signalText(status.wifiSignalDbm, status.wifiSignalLevel)
                    )
                    StatusLine(
                        "MAC точки доступа",
                        status.wifiBssid ?: "Нет доступа"
                    )
                    StatusLine(
                        "MAC устройства",
                        status.deviceMac ?: "Скрыт системой Android"
                    )
                }
            }
            if (!wifiDetailsGranted) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Дополнительные данные Wi-Fi",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Разрешение нужно для имени сети и MAC точки доступа. " +
                                    "Приложение не использует эти данные для геолокации.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                onClick = {
                                    val permissions = buildList {
                                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                        }
                                    }
                                    wifiPermissionLauncher.launch(permissions.toTypedArray())
                                }
                            ) {
                                Text("Разрешить")
                            }
                        }
                    }
                }
            }
        }
        if (status.connection == ActiveConnection.MOBILE) {
            item {
                StatusCard("Мобильная сеть") {
                    StatusLine(
                        "Уровень сигнала",
                        signalText(status.mobileSignalDbm, status.mobileSignalLevel)
                    )
                }
            }
        }
        item {
            Text(
                "Данные обновляются только пока открыт этот экран. " +
                    "MAC устройства может быть недоступен из-за ограничений Android.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusLine(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PeriodSelector(
    selected: TrafficPeriod,
    onSelected: (TrafficPeriod) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Период", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == TrafficPeriod.TODAY,
                onClick = { onSelected(TrafficPeriod.TODAY) },
                label = { Text("День") }
            )
            FilterChip(
                selected = selected == TrafficPeriod.MONTH,
                onClick = { onSelected(TrafficPeriod.MONTH) },
                label = { Text("Дни") }
            )
        }
    }
}

@Composable
private fun NetworkSelector(
    selected: NetworkMode,
    onSelected: (NetworkMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == NetworkMode.WIFI,
            onClick = { onSelected(NetworkMode.WIFI) },
            label = { Text("Wi-Fi") }
        )
        FilterChip(
            selected = selected == NetworkMode.MOBILE,
            onClick = { onSelected(NetworkMode.MOBILE) },
            label = { Text("Моб. сеть") }
        )
        FilterChip(
            selected = selected == NetworkMode.ALL,
            onClick = { onSelected(NetworkMode.ALL) },
            label = { Text("Все") }
        )
    }
}

@Composable
private fun SortSelector(
    selected: SortMode,
    onSelected: (SortMode) -> Unit
) {
    val options = listOf(
        SortMode.TODAY to "Сегодня",
        SortMode.PERIOD to "Период",
        SortMode.NAME to "Имя",
        SortMode.WIFI to "Wi-Fi",
        SortMode.MOBILE to "Моб."
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, title) ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                label = { Text(title) }
            )
        }
    }
}

@Composable
private fun ChartCard(
    points: List<ChartPoint>,
    mode: NetworkMode,
    total: Long,
    trafficRemainingBytes: Long?,
    units: com.adam.app_monitoring.core.util.ByteUnitPreference,
    selectedPoint: ChartPoint?,
    onPointSelected: (ChartPoint) -> Unit,
    onSelectionCleared: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = selectedPoint != null,
                onClick = onSelectionCleared
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Расход по времени", style = MaterialTheme.typography.titleMedium)
            if (points.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Детализация графика появится после обновления",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                TrafficBarChart(
                    points = points,
                    mode = mode,
                    units = units,
                    selectedPoint = selectedPoint,
                    onPointSelected = onPointSelected
                )
            }
            Text(
                "Всего за период: ${ByteFormatter.format(total, units)}",
                fontWeight = FontWeight.SemiBold
            )
            if (trafficRemainingBytes != null) {
                Text(
                    "Остаток мобильного трафика: ${
                        ByteFormatter.format(trafficRemainingBytes, units)
                    }",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TrafficBarChart(
    points: List<ChartPoint>,
    mode: NetworkMode,
    units: com.adam.app_monitoring.core.util.ByteUnitPreference,
    selectedPoint: ChartPoint?,
    onPointSelected: (ChartPoint) -> Unit
) {
    val wifiColor = Color(0xFFF57C00)
    val mobileColor = Color(0xFFFFB74D)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val selectedBackground = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val values = points.map { it.bytesFor(mode).coerceAtLeast(0) }
    val scaleMax = remember(values) { niceChartMaximum(values.maxOrNull() ?: 0L) }
    val gridSteps = 4
    val selectedIndex = points.indexOfFirst {
        it.bucketStart == selectedPoint?.bucketStart
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selectedIndex in points.indices) {
            val selected = points[selectedIndex]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(selectedBackground)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selected.label,
                        modifier = Modifier.weight(1f),
                        color = labelColor
                    )
                    Text(
                        ByteFormatter.format(selected.bytesFor(mode), units),
                        fontWeight = FontWeight.Bold
                    )
                }
                if (mode == NetworkMode.ALL) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ChartLegendItem(
                            color = wifiColor,
                            title = "Wi-Fi",
                            value = ByteFormatter.format(selected.wifiBytes, units)
                        )
                        ChartLegendItem(
                            color = mobileColor,
                            title = "Мобильный",
                            value = ByteFormatter.format(selected.mobileBytes, units)
                        )
                    }
                }
            }
        } else {
            Text(
                "Нажмите на столбец, чтобы увидеть точное значение",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(165.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .pointerInput(values, onPointSelected) {
                        detectTapGestures { offset ->
                            val slotWidth = size.width / values.size.coerceAtLeast(1)
                            val tappedIndex = floor(offset.x / slotWidth)
                                .toInt()
                                .coerceIn(values.indices)
                            onPointSelected(points[tappedIndex])
                        }
                    }
            ) {
                val chartBottom = size.height
                val slotWidth = size.width / values.size.coerceAtLeast(1)
                val barWidth = (slotWidth * 0.55f).coerceAtMost(18.dp.toPx())
                val cornerRadius = barWidth / 2f

                repeat(gridSteps + 1) { step ->
                    val y = chartBottom * step / gridSteps
                    drawLine(
                        color = gridColor,
                        start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                values.forEachIndexed { index, value ->
                    val point = points[index]
                    val fraction = value.toFloat() / scaleMax.toFloat()
                    val barHeight = (chartBottom * fraction.coerceIn(0f, 1f))
                        .coerceAtLeast(if (value > 0) 3.dp.toPx() else 0f)
                    val left = index * slotWidth + (slotWidth - barWidth) / 2f
                    val top = chartBottom - barHeight

                    if (mode == NetworkMode.ALL && value > 0) {
                        val wifiHeight = barHeight * point.wifiBytes.coerceAtLeast(0) / value
                        val mobileHeight = barHeight - wifiHeight
                        val barPath = Path().apply {
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    rect = androidx.compose.ui.geometry.Rect(
                                        left = left,
                                        top = top,
                                        right = left + barWidth,
                                        bottom = chartBottom
                                    ),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        cornerRadius,
                                        cornerRadius
                                    )
                                )
                            )
                        }
                        clipPath(barPath) {
                            drawRect(
                                color = mobileColor,
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(barWidth, mobileHeight)
                            )
                            drawRect(
                                color = wifiColor,
                                topLeft = androidx.compose.ui.geometry.Offset(
                                    left,
                                    top + mobileHeight
                                ),
                                size = androidx.compose.ui.geometry.Size(barWidth, wifiHeight)
                            )
                        }
                    } else {
                        drawRoundRect(
                            color = if (mode == NetworkMode.MOBILE) mobileColor else wifiColor,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                cornerRadius,
                                cornerRadius
                            )
                        )
                    }
                    if (index == selectedIndex) {
                        drawLine(
                            color = wifiColor.copy(alpha = 0.35f),
                            start = androidx.compose.ui.geometry.Offset(
                                index * slotWidth + slotWidth / 2f,
                                0f
                            ),
                            end = androidx.compose.ui.geometry.Offset(
                                index * slotWidth + slotWidth / 2f,
                                chartBottom
                            ),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .width(58.dp)
                    .height(165.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                for (step in gridSteps downTo 0) {
                    Text(
                        chartAxisLabel(scaleMax * step / gridSteps),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        maxLines = 1
                    )
                }
            }
        }

        val labelIndexes = remember(points) {
            if (points.size <= 5) points.indices.toList() else {
                listOf(0, points.size / 2, points.lastIndex).distinct()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 58.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            labelIndexes.forEach { index ->
                Text(
                    points[index].label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor
                )
            }
        }
        if (mode == NetworkMode.ALL && selectedIndex !in points.indices) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ChartLegendItem(wifiColor, "Wi-Fi")
                ChartLegendItem(mobileColor, "Мобильный")
            }
        }
    }
}

@Composable
private fun ChartLegendItem(
    color: Color,
    title: String,
    value: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            if (value == null) title else "$title: $value",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun niceChartMaximum(value: Long): Long {
    if (value <= 0) return 1L
    val magnitude = 10.0.pow(floor(log10(value.toDouble())))
    val normalized = value / magnitude
    val niceNormalized = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return ceil(niceNormalized * magnitude).toLong().coerceAtLeast(1L)
}

private fun chartAxisLabel(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val mb = bytes / (1024.0 * 1024.0)
    if (mb < 1024) {
        return if (mb >= 10) "${mb.toInt()} МБ" else String.format("%.1f МБ", mb)
    }
    val gb = mb / 1024.0
    return if (gb >= 10) "${gb.toInt()} ГБ" else String.format("%.1f ГБ", gb)
}

@Composable
private fun SummaryCard(
    usage: TrafficUsage,
    units: com.adam.app_monitoring.core.util.ByteUnitPreference,
    todayUsage: TrafficUsage? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Сводка", style = MaterialTheme.typography.titleMedium)
            SummaryLine("Всего", ByteFormatter.format(usage.totalBytes, units))
            if (todayUsage != null) {
                SummaryLine("Сегодня", ByteFormatter.format(todayUsage.totalBytes, units))
            }
            SummaryLine("Wi-Fi", ByteFormatter.format(usage.wifiBytes, units))
            SummaryLine("Мобильная сеть", ByteFormatter.format(usage.mobileBytes, units))
            SummaryLine("Загружено", ByteFormatter.format(usage.rxBytes, units))
            SummaryLine("Отправлено", ByteFormatter.format(usage.txBytes, units))
        }
    }
}

@Composable
private fun SummaryLine(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AppTrafficRow(
    app: AppTraffic,
    period: TrafficPeriod,
    intervalLabel: String? = null,
    settings: com.adam.app_monitoring.data.UserSettings,
    onClick: () -> Unit,
    loadIcon: suspend (AppTraffic) -> Bitmap?
) {
    val bitmap by produceState<Bitmap?>(null, app.app.packageName) {
        value = loadIcon(app)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.app.appName.take(1).uppercase(), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.app.appName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (settings.showPackageName) {
                    Text(
                        app.app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${intervalLabel ?: if (period == TrafficPeriod.TODAY) "Сегодня" else "С 1 числа"}: " +
                        ByteFormatter.format(app.periodUsage.totalBytes, settings.units)
                )
                if (intervalLabel == null && period != TrafficPeriod.TODAY) {
                    Text(
                        "Сегодня: ${ByteFormatter.format(app.todayUsage.totalBytes, settings.units)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Wi-Fi ${ByteFormatter.format(app.periodUsage.wifiBytes, settings.units)} · " +
                        "Моб. ${ByteFormatter.format(app.periodUsage.mobileBytes, settings.units)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppDetailScreen(
    app: AppTraffic,
    units: com.adam.app_monitoring.core.util.ByteUnitPreference,
    intervalSelected: Boolean,
    loadIcon: suspend (AppTraffic) -> Bitmap?
) {
    val bitmap by produceState<Bitmap?>(null, app.app.packageName) {
        value = loadIcon(app)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(app.app.appName, style = MaterialTheme.typography.titleLarge)
                        Text(app.app.packageName, style = MaterialTheme.typography.bodySmall)
                        Text("UID: ${app.app.uid}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { SummaryCard(app.periodUsage, units) }
        if (!intervalSelected) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Сегодня", style = MaterialTheme.typography.titleMedium)
                        SummaryLine("Всего", ByteFormatter.format(app.todayUsage.totalBytes, units))
                        SummaryLine("Wi-Fi", ByteFormatter.format(app.todayUsage.wifiBytes, units))
                        SummaryLine(
                            "Мобильная сеть",
                            ByteFormatter.format(app.todayUsage.mobileBytes, units)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionBanner(state: TrafficUiState, viewModel: TrafficViewModel) {
    if (state.permissions.usageAccessGranted) return
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Нет доступа к статистике использования", fontWeight = FontWeight.Bold)
            Text("Разрешите доступ в настройках Android, чтобы увидеть трафик приложений.")
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) {
                Text("Открыть настройки")
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(message, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun EmptyDataCard(hasPermission: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (hasPermission) {
                "За выбранный период трафик не найден."
            } else {
                "Нет доступа к статистике использования."
            },
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun SettingsScreen(state: TrafficUiState, viewModel: TrafficViewModel) {
    val context = LocalContext.current
    val settings = state.settings
    var notificationPermissionTarget by remember {
        mutableStateOf(NotificationPermissionTarget.NONE)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onResume()
        if (granted) {
            when (notificationPermissionTarget) {
                NotificationPermissionTarget.SPEED -> {
                    viewModel.updateSettings {
                        it.copy(speedNotificationEnabled = true)
                    }
                    Toast.makeText(
                        context,
                        "Индикатор запущен. Смахните приложение из недавних и подождите до 10 секунд.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                NotificationPermissionTarget.LIMIT -> {
                    viewModel.updateSettings {
                        it.copy(
                            trafficLimitNotificationsEnabled = true,
                            backgroundEnabled = true
                        )
                    }
                }
                NotificationPermissionTarget.NONE -> Unit
            }
        }
        notificationPermissionTarget = NotificationPermissionTarget.NONE
    }
    val lastRefresh = viewModel.diagnosticLastRefreshAt()
    val lastBoot = viewModel.diagnosticLastBootAt()
    val lastBootRefresh = viewModel.diagnosticLastBootRefreshAt()
    val isXiaomi = XiaomiBackgroundSupport.isXiaomiDevice()
    val nextRefresh = if (lastRefresh > 0 && settings.backgroundEnabled) {
        lastRefresh + settings.updateInterval.minutes * 60_000
    } else {
        0
    }
    val permissionRow: @Composable (
        title: String,
        description: String,
        status: String,
        color: Color,
        button: String?,
        onClick: () -> Unit
    ) -> Unit = { title, description, status, color, button, onClick ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    if (button == null) description else "$description · $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            if (button != null) {
                OutlinedButton(onClick = onClick) {
                    Text(button)
                }
            } else {
                Text(
                    status,
                    color = color,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
    val toggleRow: @Composable (
        title: String,
        description: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) -> Unit = { title, description, checked, onCheckedChange ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Разрешения") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    permissionRow(
                        "Статистика использования",
                        "Нужна для просмотра трафика всех приложений.",
                        if (state.permissions.usageAccessGranted) "Разрешено" else "Не разрешено",
                        if (state.permissions.usageAccessGranted) StatusGreen else StatusRed,
                        if (state.permissions.usageAccessGranted) null else "Открыть"
                    ) {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    HorizontalDivider()
                    permissionRow(
                        "Без ограничений батареи",
                        "Помогает выполнять фоновые обновления вовремя.",
                        if (state.permissions.ignoringBatteryOptimizations) {
                            "Без ограничений"
                        } else {
                            "Может ограничиваться"
                        },
                        if (state.permissions.ignoringBatteryOptimizations) StatusGreen else StatusRed,
                        if (state.permissions.ignoringBatteryOptimizations) null else "Открыть"
                    ) {
                        val direct = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(direct) }
                            .onFailure {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                    }
                    HorizontalDivider()
                    permissionRow(
                        "Уведомления",
                        "Нужны для индикатора скорости и предупреждений.",
                        if (state.permissions.notificationsGranted) "Разрешены" else "Не разрешены",
                        if (state.permissions.notificationsGranted) StatusGreen else StatusRed,
                        if (state.permissions.notificationsGranted) null else "Разрешить"
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionTarget = NotificationPermissionTarget.NONE
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    HorizontalDivider()
                    permissionRow(
                        "Точные будильники",
                        "Снижают задержку восстановления после остановки.",
                        if (state.permissions.exactAlarmsGranted) "Разрешены" else "Не разрешены",
                        if (state.permissions.exactAlarmsGranted) StatusGreen else StatusRed,
                        if (state.permissions.exactAlarmsGranted) null else "Разрешить"
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}")
                            )
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    )
                                }
                        }
                    }
                    HorizontalDivider()
                    permissionRow(
                        "Автозапуск после перезагрузки",
                        "Запускает короткую одноразовую задачу обновления.",
                        when {
                            lastBootRefresh > 0 -> "Подтверждён"
                            lastBoot > 0 -> "Ожидается"
                            else -> "Нельзя проверить"
                        },
                        when {
                            lastBootRefresh > 0 -> StatusGreen
                            lastBoot > 0 -> StatusAmber
                            else -> MaterialTheme.colorScheme.outline
                        },
                        if (lastBootRefresh > 0) null else "Открыть"
                    ) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                    HorizontalDivider()
                    permissionRow(
                        "Видимость приложений",
                        "Нужна для полного локального списка приложений.",
                        "Объявлено",
                        StatusGreen,
                        null
                    ) {}
                }
            }
        }
        if (isXiaomi) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Автозапуск Xiaomi", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Включите автозапуск, режим батареи «Без ограничений» и закрепите приложение в недавних.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    XiaomiBackgroundSupport.autostartIntent(context)
                                )
                            }
                        ) {
                            Text("Открыть")
                        }
                    }
                }
            }
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Samsung: Настройки → Приложения → Трафик приложений → Батарея. " +
                            "Режим «Без ограничений» нужен только если штатные обновления заметно задерживаются.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        item { SectionTitle("Фоновое обновление") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    toggleRow(
                        "Включить фоновое обновление",
                        null,
                        settings.backgroundEnabled
                    ) { value ->
                        viewModel.updateSettings { it.copy(backgroundEnabled = value) }
                    }
                    HorizontalDivider()
                    toggleRow(
                        "Не обновлять при низком заряде",
                        null,
                        settings.requireBatteryNotLow
                    ) { value ->
                        viewModel.updateSettings { it.copy(requireBatteryNotLow = value) }
                    }
                    HorizontalDivider()
                    toggleRow(
                        "Обновлять после перезагрузки",
                        null,
                        settings.refreshAfterBoot
                    ) { value ->
                        viewModel.updateSettings { it.copy(refreshAfterBoot = value) }
                    }
                    HorizontalDivider()
                    toggleRow(
                        "Индикатор скорости сети",
                        "Постоянное уведомление со скоростью загрузки и передачи.",
                        settings.speedNotificationEnabled
                    ) { enabled ->
                        if (
                            enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !state.permissions.notificationsGranted
                        ) {
                            notificationPermissionTarget = NotificationPermissionTarget.SPEED
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } else {
                            viewModel.updateSettings {
                                it.copy(speedNotificationEnabled = enabled)
                            }
                        }
                    }
                    HorizontalDivider()
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Text("Интервал", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            UpdateInterval.entries.forEach { interval ->
                                FilterChip(
                                    selected = settings.updateInterval == interval,
                                    enabled = settings.backgroundEnabled,
                                    onClick = {
                                        viewModel.updateSettings {
                                            it.copy(updateInterval = interval)
                                        }
                                    },
                                    label = { Text(interval.label()) }
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            BackgroundReliabilityCard(
                notificationsGranted = state.permissions.notificationsGranted,
                batteryUnrestricted = state.permissions.ignoringBatteryOptimizations,
                exactAlarmsGranted = state.permissions.exactAlarmsGranted,
                isXiaomi = isXiaomi,
                heartbeatAt = state.serviceHeartbeatAt,
                lastExit = state.lastProcessExit?.let {
                    "${it.reason}, ${formatTime(it.timestamp)}"
                },
                events = state.serviceEvents.take(5).map {
                    "${formatTime(it.timestamp)} · ${it.type}" +
                        if (it.details.isBlank()) "" else " · ${it.details}"
                },
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionTarget = NotificationPermissionTarget.NONE
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onBattery = {
                    val direct = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { context.startActivity(direct) }
                        .onFailure {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                },
                onExactAlarms = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                )
                            }
                    }
                },
                onXiaomiAutostart = {
                    context.startActivity(XiaomiBackgroundSupport.autostartIntent(context))
                },
                onTest = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !state.permissions.notificationsGranted
                    ) {
                        notificationPermissionTarget = NotificationPermissionTarget.SPEED
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.testServiceRecovery()
                        Toast.makeText(
                            context,
                            "Индикатор запущен. Теперь смахните приложение из недавних и подождите до 10 секунд.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
        item {
            DiagnosticCard(
                lastRefresh = lastRefresh,
                lastBoot = lastBoot,
                lastBootRefresh = lastBootRefresh,
                nextRefresh = nextRefresh,
                error = viewModel.diagnosticLastError()
            )
        }

        item { SectionTitle("Лимит мобильного трафика") }
        item {
            ToggleSetting(
                title = "Предупреждать о приближении к лимиту",
                description = "Проверка выполняется при обновлении статистики. Фоновое обновление включается автоматически.",
                checked = settings.trafficLimitNotificationsEnabled,
                onCheckedChange = { enabled ->
                    if (enabled &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !state.permissions.notificationsGranted
                    ) {
                        notificationPermissionTarget = NotificationPermissionTarget.LIMIT
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.updateSettings {
                            it.copy(
                                trafficLimitNotificationsEnabled = enabled,
                                backgroundEnabled = if (enabled) true else it.backgroundEnabled
                            )
                        }
                    }
                }
            )
        }
        item {
            NumericSettingField(
                title = "Лимит на период",
                value = settings.monthlyTrafficLimitMb,
                suffix = "МБ",
                allowedRange = SettingsStore.MIN_LIMIT_MB..SettingsStore.MAX_LIMIT_MB,
                onValueChange = { value ->
                    viewModel.updateSettings { it.copy(monthlyTrafficLimitMb = value) }
                }
            )
        }
        item {
            TrafficRemainingSetting(
                value = settings.configuredTrafficRemainingMb
                    .coerceAtMost(settings.monthlyTrafficLimitMb),
                limitMb = settings.monthlyTrafficLimitMb,
                saving = state.trafficRemainingSaving,
                lastSavedValue = state.lastConfiguredTrafficRemainingMb,
                onApply = viewModel::configureTrafficRemaining
            )
        }
        item {
            NumericSettingField(
                title = "Предупредить при",
                value = settings.trafficWarningPercent,
                suffix = "%",
                allowedRange = 1..100,
                onValueChange = { value ->
                    viewModel.updateSettings { it.copy(trafficWarningPercent = value) }
                }
            )
        }
        item {
            NumericSettingField(
                title = "Первый день расчетного периода",
                value = settings.billingCycleStartDay,
                suffix = "число месяца",
                allowedRange = 1..31,
                onValueChange = { value ->
                    viewModel.updateSettings { it.copy(billingCycleStartDay = value) }
                }
            )
        }
        item {
            Text(
                "Остаток уменьшается только на мобильные данные после момента, когда он был задан. " +
                    "Для коротких месяцев день 29–31 переносится на последний день месяца.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item { SectionTitle("Виджет") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Интервал обновления",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Android может немного задерживать фоновые обновления для экономии батареи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WidgetUpdateInterval.entries.forEach { interval ->
                            FilterChip(
                                selected = settings.widgetUpdateInterval == interval,
                                onClick = {
                                    viewModel.updateSettings {
                                        it.copy(widgetUpdateInterval = interval)
                                    }
                                },
                                label = { Text(interval.label()) }
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle("Интерфейс") }
        item {
            Text("Тема", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemePreference.entries.forEach { theme ->
                    FilterChip(
                        selected = settings.theme == theme,
                        onClick = { viewModel.updateSettings { it.copy(theme = theme) } },
                        label = { Text(theme.label()) }
                    )
                }
            }
        }
        item {
            ToggleSetting(
                title = "Показывать package name",
                checked = settings.showPackageName,
                onCheckedChange = { value ->
                    viewModel.updateSettings { it.copy(showPackageName = value) }
                }
            )
        }
        item {
            ToggleSetting(
                title = "Показывать системные приложения",
                checked = settings.showSystemApps,
                onCheckedChange = { value ->
                    viewModel.updateSettings { it.copy(showSystemApps = value) }
                }
            )
        }
        item {
            ToggleSetting(
                title = "Показывать приложения без трафика",
                checked = settings.showAppsWithoutTraffic,
                onCheckedChange = { value ->
                    viewModel.updateSettings { it.copy(showAppsWithoutTraffic = value) }
                }
            )
        }

        item { SectionTitle("Данные") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::clearCache
                    ) {
                        Text("Очистить кэш статистики")
                    }
                    Text(
                        "Данные остаются только на устройстве. Постоянный сервис, VPN и root не используются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun PermissionSettingCard(
    title: String,
    description: String,
    status: String,
    color: Color,
    button: String? = null,
    onClick: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
            }
            Text(description, style = MaterialTheme.typography.bodySmall)
            Text(status, color = color, fontWeight = FontWeight.Medium)
            if (button != null) {
                OutlinedButton(onClick = onClick) { Text(button) }
            }
        }
    }
}

@Composable
private fun BackgroundReliabilityCard(
    notificationsGranted: Boolean,
    batteryUnrestricted: Boolean,
    exactAlarmsGranted: Boolean,
    isXiaomi: Boolean,
    heartbeatAt: Long,
    lastExit: String?,
    events: List<String>,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
    onExactAlarms: () -> Unit,
    onXiaomiAutostart: () -> Unit,
    onTest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Надёжность фоновой работы", style = MaterialTheme.typography.titleMedium)
            ReliabilityStatus(
                "Уведомления",
                if (notificationsGranted) "Разрешены" else "Не разрешены"
            )
            ReliabilityStatus(
                "Батарея",
                if (batteryUnrestricted) "Без оптимизации" else "Может ограничиваться"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ReliabilityStatus(
                    "Точные будильники",
                    if (exactAlarmsGranted) "Разрешены" else "Нет, рестарт может задержаться"
                )
            }
            if (isXiaomi) {
                ReliabilityStatus("Автозапуск Xiaomi", "Проверяется только вручную")
                Text(
                    "Включите «Автозапуск», режим батареи «Без ограничений» и закрепите " +
                        "карточку приложения замком, чтобы Xiaomi Cleaner её не закрывал.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ReliabilityStatus("Heartbeat сервиса", formatTime(heartbeatAt))
            lastExit?.let { ReliabilityStatus("Последнее завершение", it) }
            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(onClick = onNotifications) { Text("Разрешить уведомления") }
            }
            if (!batteryUnrestricted) {
                OutlinedButton(onClick = onBattery) { Text("Убрать ограничение батареи") }
            }
            if (!exactAlarmsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                OutlinedButton(onClick = onExactAlarms) { Text("Разрешить точные будильники") }
            }
            if (isXiaomi) {
                OutlinedButton(onClick = onXiaomiAutostart) { Text("Открыть автозапуск Xiaomi") }
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onTest) {
                Text("Проверить восстановление")
            }
            if (events.isNotEmpty()) {
                Text("Последние события", fontWeight = FontWeight.SemiBold)
                events.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "После принудительной остановки Android не позволяет приложению запустить себя " +
                    "до ручного открытия.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReliabilityStatus(title: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun TrafficRemainingSetting(
    value: Int,
    limitMb: Int,
    saving: Boolean,
    lastSavedValue: Int?,
    onApply: (Int) -> Unit
) {
    var text by remember(value, limitMb) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in SettingsStore.MIN_REMAINING_MB..limitMb
    val saved = lastSavedValue != null && parsed == lastSavedValue

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { raw -> text = raw.filter(Char::isDigit).take(9) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Задать остаток") },
                suffix = { Text("МБ") },
                supportingText = if (invalid) {
                    { Text("Допустимо: 0–$limitMb") }
                } else {
                    { Text("От этой величины будет вычитаться дальнейший расход") }
                },
                isError = invalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !invalid && !saving,
                onClick = { parsed?.let(onApply) }
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Сохраняем...")
                } else {
                    Text(if (saved) "Сохранено" else "Применить остаток")
                }
            }
            if (saved) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    Text(
                        text = "Остаток $lastSavedValue МБ успешно сохранён",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun NumericSettingField(
    title: String,
    value: Int,
    suffix: String,
    allowedRange: IntRange,
    onValueChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in allowedRange

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(9)
            text = digits
            digits.toIntOrNull()
                ?.takeIf { it in allowedRange }
                ?.let(onValueChange)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(title) },
        suffix = { Text(suffix) },
        supportingText = if (invalid) {
            {
                Text("Допустимо: ${allowedRange.first}–${allowedRange.last}")
            }
        } else {
            null
        },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun DiagnosticCard(
    lastRefresh: Long,
    lastBoot: Long,
    lastBootRefresh: Long,
    nextRefresh: Long,
    error: String?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Диагностика", style = MaterialTheme.typography.titleMedium)
            SummaryLine("Последнее обновление", formatTime(lastRefresh))
            SummaryLine("Сигнал загрузки", formatTime(lastBoot))
            SummaryLine("Boot-обновление", formatTime(lastBootRefresh))
            SummaryLine("Следующее примерно", formatTime(nextRefresh))
            if (!error.isNullOrBlank()) {
                HorizontalDivider()
                Text("Последняя ошибка: $error", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun filteredApps(state: TrafficUiState): List<AppTraffic> {
    val mode = state.networkMode
    val apps = if (state.selectedChartPoint == null) {
        state.snapshot.apps
    } else {
        state.intervalApps.orEmpty()
    }
    return apps
        .asSequence()
        .filter { state.settings.showSystemApps || !it.app.isSystemApp }
        .filter {
            state.settings.showAppsWithoutTraffic || it.periodUsage.bytesFor(mode) > 0
        }
        .sortedWith(
            when (state.sortMode) {
                SortMode.TODAY -> compareByDescending { it.todayUsage.bytesFor(mode) }
                SortMode.PERIOD -> compareByDescending { it.periodUsage.bytesFor(mode) }
                SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.app.appName }
                SortMode.WIFI -> compareByDescending { it.periodUsage.wifiBytes }
                SortMode.MOBILE -> compareByDescending { it.periodUsage.mobileBytes }
            }
        )
        .toList()
}

private fun selectedIntervalTitle(state: TrafficUiState): String? {
    val point = state.selectedChartPoint ?: return null
    val time = Instant.ofEpochMilli(point.bucketStart).atZone(ZoneId.systemDefault())
    return when (state.period) {
        TrafficPeriod.TODAY -> {
            val end = time.plusHours(1)
            "${time.format(DAY_MONTH_FORMATTER)}, " +
                "${time.format(HOUR_MINUTE_FORMATTER)}–${end.format(HOUR_MINUTE_FORMATTER)}"
        }
        TrafficPeriod.MONTH -> time.format(DAY_MONTH_FORMATTER)
    }
}

private fun ActiveConnection.label(): String = when (this) {
    ActiveConnection.WIFI -> "Wi-Fi"
    ActiveConnection.MOBILE -> "Мобильные данные"
    ActiveConnection.ETHERNET -> "Ethernet"
    ActiveConnection.OTHER -> "Другое подключение"
    ActiveConnection.NONE -> "Нет подключения"
}

private fun yesNo(value: Boolean): String = if (value) "Есть" else "Нет"

private fun wifiBand(frequencyMhz: Int?): String = when (frequencyMhz) {
    null -> "Нет данных"
    in 2_400..2_500 -> "2,4 ГГц"
    in 4_900..5_900 -> "5 ГГц"
    in 5_925..7_125 -> "6 ГГц"
    else -> "Другая частота"
}

private fun signalText(dbm: Int?, level: Int?): String {
    if (dbm == null && level == null) return "Недоступно"
    val quality = when (level) {
        4 -> "отличный"
        3 -> "хороший"
        2 -> "средний"
        1 -> "слабый"
        0 -> "очень слабый"
        else -> null
    }
    return listOfNotNull(
        dbm?.let { "$it dBm" },
        quality
    ).joinToString(", ")
}

private fun UpdateInterval.label(): String = when (this) {
    UpdateInterval.MINUTES_15 -> "15 мин"
    UpdateInterval.MINUTES_30 -> "30 мин"
    UpdateInterval.HOUR_1 -> "1 час"
    UpdateInterval.HOURS_3 -> "3 часа"
    UpdateInterval.HOURS_6 -> "6 часов"
}

private fun WidgetUpdateInterval.label(): String = when (this) {
    WidgetUpdateInterval.MINUTES_5 -> "5 мин"
    WidgetUpdateInterval.MINUTES_15 -> "15 мин"
    WidgetUpdateInterval.MINUTES_30 -> "30 мин"
    WidgetUpdateInterval.HOUR_1 -> "1 час"
    WidgetUpdateInterval.HOURS_3 -> "3 часа"
}

private fun ThemePreference.label(): String = when (this) {
    ThemePreference.SYSTEM -> "Система"
    ThemePreference.LIGHT -> "Светлая"
    ThemePreference.DARK -> "Тёмная"
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return "Нет данных"
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(TIME_FORMATTER)
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val DAY_MONTH_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM")
private val HOUR_MINUTE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm")

private const val EXIT_CONFIRMATION_WINDOW_MS = 2_000L
