package com.adam.app_monitoring.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adam.app_monitoring.core.model.AppTraffic
import com.adam.app_monitoring.core.model.ChartPoint
import com.adam.app_monitoring.core.model.NetworkMode
import com.adam.app_monitoring.core.model.SortMode
import com.adam.app_monitoring.core.model.TrafficPeriod
import com.adam.app_monitoring.core.model.TrafficUsage
import com.adam.app_monitoring.core.util.ByteFormatter
import com.adam.app_monitoring.data.ThemePreference
import com.adam.app_monitoring.data.UpdateInterval
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
                        if (selected == null) "Трафик приложений" else selected.app.appName,
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
                    if (selected == null && state.tab != MainTab.SETTINGS) {
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
                    MainTab.CHARTS -> ChartsScreen(state, viewModel)
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
            selected = selected == MainTab.CHARTS,
            onClick = { onSelected(MainTab.CHARTS) },
            icon = { Text("Г") },
            label = { Text("Графики") }
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
                units = state.settings.units,
                selectedPoint = state.selectedChartPoint,
                onPointSelected = viewModel::selectChartPoint,
                onSelectionCleared = viewModel::clearChartSelection
            )
        }
        item {
            SummaryCard(
                usage = state.snapshot.totalUsage,
                units = state.settings.units
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
private fun ChartsScreen(state: TrafficUiState, viewModel: TrafficViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PeriodSelector(state.period, viewModel::setPeriod) }
        item { NetworkSelector(state.networkMode, viewModel::setNetworkMode) }
        item {
            ChartCard(
                points = state.snapshot.chart,
                mode = state.networkMode,
                total = state.snapshot.totalUsage.bytesFor(state.networkMode),
                units = state.settings.units,
                selectedPoint = state.selectedChartPoint,
                onPointSelected = viewModel::selectChartPoint,
                onSelectionCleared = viewModel::clearChartSelection
            )
        }
        item { SummaryCard(state.snapshot.totalUsage, state.settings.units) }
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
    units: com.adam.app_monitoring.core.util.ByteUnitPreference
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Сводка", style = MaterialTheme.typography.titleMedium)
            SummaryLine("Всего", ByteFormatter.format(usage.totalBytes, units))
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
    val lastRefresh = viewModel.diagnosticLastRefreshAt()
    val lastBoot = viewModel.diagnosticLastBootAt()
    val lastBootRefresh = viewModel.diagnosticLastBootRefreshAt()
    val nextRefresh = if (lastRefresh > 0 && settings.backgroundEnabled) {
        lastRefresh + settings.updateInterval.minutes * 60_000
    } else {
        0
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Разрешения и надёжность") }
        item {
            PermissionSettingCard(
                title = "Доступ к статистике использования",
                description = "Нужен для просмотра трафика всех приложений.",
                status = if (state.permissions.usageAccessGranted) "Разрешено" else "Не разрешено",
                color = if (state.permissions.usageAccessGranted) StatusGreen else StatusRed,
                button = "Открыть настройки",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )
        }
        item {
            PermissionSettingCard(
                title = "Оптимизация батареи",
                description = "Исключение необязательно. Без него Android может задерживать обновления.",
                status = if (state.permissions.ignoringBatteryOptimizations) {
                    "Исключено из оптимизации"
                } else {
                    "Базовая работа доступна"
                },
                color = if (state.permissions.ignoringBatteryOptimizations) {
                    StatusGreen
                } else {
                    StatusAmber
                },
                button = "Настройки батареи",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            )
        }
        item {
            PermissionSettingCard(
                title = "Автозапуск после перезагрузки",
                description = "Receiver только ставит короткую одноразовую WorkManager-задачу.",
                status = when {
                    lastBootRefresh > 0 -> "Последний запуск подтверждён"
                    lastBoot > 0 -> "Загрузка получена, обновление ожидается"
                    else -> "OEM-ограничение невозможно проверить"
                },
                color = when {
                    lastBootRefresh > 0 -> StatusGreen
                    lastBoot > 0 -> StatusAmber
                    else -> MaterialTheme.colorScheme.outline
                },
                button = "Настройки приложения",
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )
        }
        item {
            PermissionSettingCard(
                title = "Видимость приложений",
                description = "Сборка использует QUERY_ALL_PACKAGES для полного локального списка.",
                status = "Разрешение объявлено",
                color = StatusGreen
            )
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
            ToggleSetting(
                title = "Включить фоновое обновление",
                checked = settings.backgroundEnabled,
                onCheckedChange = { value ->
                    viewModel.updateSettings { it.copy(backgroundEnabled = value) }
                }
            )
        }
        item {
            ToggleSetting(
                title = "Не обновлять при низком заряде",
                checked = settings.requireBatteryNotLow,
                onCheckedChange = { value ->
                    viewModel.updateSettings { it.copy(requireBatteryNotLow = value) }
                }
            )
        }
        item {
            ToggleSetting(
                title = "Обновлять после перезагрузки",
                checked = settings.refreshAfterBoot,
                onCheckedChange = { value ->
                    viewModel.updateSettings { it.copy(refreshAfterBoot = value) }
                }
            )
        }
        item {
            Text("Интервал", style = MaterialTheme.typography.labelLarge)
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
                            viewModel.updateSettings { it.copy(updateInterval = interval) }
                        },
                        label = { Text(interval.label()) }
                    )
                }
            }
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
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::clearCache
            ) {
                Text("Очистить кэш статистики")
            }
        }
        item {
            Text(
                "Данные остаются только на устройстве. Постоянный сервис, VPN и root не используются.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
private fun ToggleSetting(
    title: String,
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
            Text(title, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
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

private fun UpdateInterval.label(): String = when (this) {
    UpdateInterval.MINUTES_15 -> "15 мин"
    UpdateInterval.MINUTES_30 -> "30 мин"
    UpdateInterval.HOUR_1 -> "1 час"
    UpdateInterval.HOURS_3 -> "3 часа"
    UpdateInterval.HOURS_6 -> "6 часов"
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
