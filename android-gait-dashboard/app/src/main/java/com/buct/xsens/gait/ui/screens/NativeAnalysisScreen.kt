package com.buct.xsens.gait.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buct.xsens.dot.ui.components.Badge
import com.buct.xsens.dot.ui.components.BadgeType
import com.buct.xsens.dot.ui.components.NeutralButton
import com.buct.xsens.dot.ui.components.Panel
import com.buct.xsens.dot.ui.components.SuccessButton
import com.buct.xsens.dot.ui.theme.Accent
import com.buct.xsens.dot.ui.theme.Bg
import com.buct.xsens.dot.ui.theme.Border
import com.buct.xsens.dot.ui.theme.Card
import com.buct.xsens.dot.ui.theme.Green
import com.buct.xsens.dot.ui.theme.Muted
import com.buct.xsens.dot.ui.theme.Orange
import com.buct.xsens.dot.ui.theme.Red
import com.buct.xsens.dot.ui.theme.Surface as AppSurface
import com.buct.xsens.dot.ui.theme.Text as AppText
import com.buct.xsens.gait.analysis.AnalysisHistoryItem
import com.buct.xsens.gait.analysis.AnalysisMode
import com.buct.xsens.gait.analysis.AnalysisResult
import com.buct.xsens.gait.analysis.AnalysisSection
import com.buct.xsens.gait.analysis.AnalysisUiState
import com.buct.xsens.gait.analysis.AnalysisViewModel
import com.buct.xsens.gait.analysis.AthleteProfile
import com.buct.xsens.gait.analysis.CapturedAttempt
import com.buct.xsens.gait.analysis.FootSide
import com.buct.xsens.gait.analysis.GaitStride
import com.buct.xsens.gait.analysis.LanShareUiConfig
import com.buct.xsens.gait.ui.components.AnalysisChartPoint
import com.buct.xsens.gait.ui.components.AnalysisLineChart
import com.buct.xsens.gait.ui.components.SignalEventChart
import java.text.SimpleDateFormat
import java.util.Locale

private enum class MetricKey(
    val label: String,
    val unit: String,
    val color: Color,
    val decimals: Int,
    val value: (GaitStride) -> Double?,
) {
    Velocity("步速", "m/s", Accent, 2, value = { it.velocityMps }),
    StrideLength("步幅", "m", Color(0xFF06B6D4), 2, value = { it.strideLengthM }),
    Frequency("步频", "spm", Green, 0, value = { it.stepFrequencySpm }),
    Contact("触地时间", "ms", Color(0xFF84CC16), 0, value = { it.contactTimeMs }),
    DoubleSupport("双足支撑", "ms", Color(0xFF14B8A6), 0, value = { it.doubleSupportTimeMs }),
    Flight("腾空时间", "ms", Color(0xFFF59E0B), 0, value = { it.flightTimeMs }),
    Swing("摆动时间", "ms", Orange, 0, value = { it.swingTimeMs }),
    Vgrf(
        "峰值垂直力",
        "BW",
        Color(0xFFA855F7),
        2,
        value = { it.vgrfPeakBw },
    ),
    ;

    fun format(value: Double): String =
        String.format(Locale.US, "%.${decimals}f", value)
}

@Composable
fun NativeAnalysisScreen(
    viewModel: AnalysisViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAthleteDialog by rememberSaveable { mutableStateOf(false) }
    var editingAthlete by remember { mutableStateOf<AthleteProfile?>(null) }
    var showDataDialog by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importAthletes)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCapturedAttempts()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        AnalysisTabs(
            selected = state.section,
            onSelect = viewModel::setSection,
        )
        when (state.section) {
            AnalysisSection.Main -> MainAnalysisContent(
                state = state,
                onSelectMode = viewModel::setAnalysisMode,
                onSelectAthlete = viewModel::selectAthlete,
                onAttemptNoChange = viewModel::updateAttemptNo,
                onNewAthlete = {
                    editingAthlete = null
                    showAthleteDialog = true
                },
                onEditAthlete = {
                    editingAthlete = state.selectedAthlete
                    showAthleteDialog = true
                },
                onImportAthletes = { importLauncher.launch("application/json") },
                onSelectData = {
                    viewModel.refreshCapturedAttempts()
                    showDataDialog = true
                },
                onAnalyze = { viewModel.analyze(false) },
            )

            AnalysisSection.Advanced -> AdvancedAnalysisContent(
                state = state,
                onStartChange = viewModel::updateRangeStart,
                onEndChange = viewModel::updateRangeEnd,
                onClearRange = viewModel::clearRange,
                onApplyRange = { viewModel.analyze(true) },
                onLanConfigChange = viewModel::updateLanConfig,
                onSaveLan = viewModel::saveLanConfig,
                onTestLan = viewModel::testLanConnection,
                onUploadCurrent = viewModel::uploadCurrentResult,
            )

            AnalysisSection.History -> HistoryContent(
                state = state,
                onSelectAthlete = viewModel::selectHistoryAthlete,
                onSelectDate = viewModel::selectHistoryDate,
                onOpen = viewModel::openHistory,
                onDelete = viewModel::deleteHistory,
            )
        }
    }

    if (showAthleteDialog) {
        AthleteEditorDialog(
            initial = editingAthlete,
            onDismiss = { showAthleteDialog = false },
            onSave = {
                viewModel.saveAthlete(it)
                showAthleteDialog = false
            },
        )
    }

    if (showDataDialog) {
        DataPickerDialog(
            attempts = state.attempts,
            selectedKey = state.selectedAttemptKey,
            onDismiss = { showDataDialog = false },
            onSelect = {
                viewModel.selectAttempt(it.key)
                showDataDialog = false
            },
        )
    }
}

@Composable
private fun AnalysisTabs(
    selected: AnalysisSection,
    onSelect: (AnalysisSection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            AnalysisSection.Main to "步态分析",
            AnalysisSection.Advanced to "进阶分析",
            AnalysisSection.History to "历史记录",
        ).forEach { (section, label) ->
            FilterChip(
                selected = selected == section,
                onClick = { onSelect(section) },
                label = { Text(label, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Card,
                    labelColor = Muted,
                    selectedContainerColor = Green.copy(alpha = 0.14f),
                    selectedLabelColor = Green,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == section,
                    borderColor = Border,
                    selectedBorderColor = Green.copy(alpha = 0.35f),
                ),
                shape = RoundedCornerShape(999.dp),
            )
        }
    }
}

@Composable
private fun MainAnalysisContent(
    state: AnalysisUiState,
    onSelectMode: (AnalysisMode) -> Unit,
    onSelectAthlete: (String) -> Unit,
    onAttemptNoChange: (String) -> Unit,
    onNewAthlete: () -> Unit,
    onEditAthlete: () -> Unit,
    onImportAthletes: () -> Unit,
    onSelectData: () -> Unit,
    onAnalyze: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AnalysisPreparationPanel(
                state = state,
                onSelectMode = onSelectMode,
                onSelectAthlete = onSelectAthlete,
                onAttemptNoChange = onAttemptNoChange,
                onNewAthlete = onNewAthlete,
                onEditAthlete = onEditAthlete,
                onImportAthletes = onImportAthletes,
                onSelectData = onSelectData,
                onAnalyze = onAnalyze,
            )
        }
        if (state.isAnalyzing) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Green,
                    trackColor = Border,
                )
            }
        }
        if (state.errorMessage.isNotBlank()) {
            item { MessageBand(state.errorMessage, error = true) }
        } else if (state.statusMessage.isNotBlank()) {
            item { MessageBand(state.statusMessage, error = false) }
        }
        state.result?.let { result ->
            item { AnalysisResultPanel(result) }
        }
    }
}

@Composable
private fun AnalysisPreparationPanel(
    state: AnalysisUiState,
    onSelectMode: (AnalysisMode) -> Unit,
    onSelectAthlete: (String) -> Unit,
    onAttemptNoChange: (String) -> Unit,
    onNewAthlete: () -> Unit,
    onEditAthlete: () -> Unit,
    onImportAthletes: () -> Unit,
    onSelectData: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val selectedAttempt = state.selectedAttempt
    Panel(title = "分析模式") {
        AnalysisModeSelector(
            selected = state.analysisMode,
            onSelect = onSelectMode,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 14.dp),
            color = Border,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreparationLabel("运动员")
            AthleteDropdown(
                athletes = state.athletes,
                selectedId = state.selectedAthleteId,
                onSelect = onSelectAthlete,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.attemptNo,
                onValueChange = onAttemptNoChange,
                label = {
                    Text(
                        if (state.analysisMode == AnalysisMode.LongJump) "试跳编号" else "记录编号"
                    )
                },
                singleLine = true,
                modifier = Modifier.width(112.dp),
                colors = analysisTextFieldColors(),
            )
            CompactIconButton(Icons.Outlined.Add, "新建运动员", onNewAthlete)
            CompactIconButton(
                Icons.Outlined.Edit,
                "编辑运动员",
                onEditAthlete,
                enabled = state.selectedAthlete != null,
            )
            CompactIconButton(Icons.Outlined.FileOpen, "导入名单", onImportAthletes)
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 14.dp),
            color = Border,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreparationLabel("训练数据")
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp)
                    .clickable(onClick = onSelectData),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = Green,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedAttempt?.let {
                            "${it.dateLabel}  ${it.timeLabel}"
                        } ?: "选择采集记录",
                        color = AppText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (selectedAttempt != null) {
                        Spacer(Modifier.height(5.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SideTag(FootSide.Left, selectedAttempt.leftPath != null)
                            SideTag(FootSide.Right, selectedAttempt.rightPath != null)
                        }
                    }
                }
                if (selectedAttempt != null) {
                    Text(
                        text = "更换",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Muted,
                )
            }
            SuccessButton(
                text = if (state.isAnalyzing) "分析中" else "开始分析",
                onClick = onAnalyze,
                enabled = state.canAnalyze,
                modifier = Modifier.width(160.dp),
            )
        }
    }
}

@Composable
private fun AnalysisModeSelector(
    selected: AnalysisMode,
    onSelect: (AnalysisMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(AppSurface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnalysisMode.values().forEach { mode ->
            val active = selected == mode
            Surface(
                color = if (active) Green.copy(alpha = 0.16f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(mode) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = mode.label,
                        color = if (active) Green else Muted,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreparationLabel(text: String) {
    Text(
        text = text,
        color = Muted,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.width(72.dp),
        maxLines = 1,
    )
}

@Composable
private fun AnalysisResultPanel(result: AnalysisResult) {
    var metric by remember(result.mode, result.rawJson) { mutableStateOf(MetricKey.Velocity) }
    val metrics = remember(result.mode) {
        MetricKey.values().filter { key ->
            result.mode == AnalysisMode.GeneralGait || key != MetricKey.DoubleSupport
        }
    }
    val chartPoints = remember(result, metric) {
        val rawPoints = result.strides.mapIndexedNotNull { index, stride ->
            metric.value(stride)?.let { value ->
                Triple(stride.side, stride.toTimestampMs, IndexedValue(index, value))
            }
        }
        val timestampOrigin = rawPoints.mapNotNull { it.second }.minOrNull()
        fun pointsFor(side: FootSide?): List<AnalysisChartPoint> =
            rawPoints
                .filter { it.first == side }
                .map { (_, timestampMs, indexedValue) ->
                    val elapsedSeconds = if (timestampOrigin != null && timestampMs != null) {
                        (timestampMs - timestampOrigin) / 1000.0
                    } else {
                        indexedValue.index.toDouble()
                    }
                    AnalysisChartPoint(
                        x = elapsedSeconds,
                        y = indexedValue.value,
                        stepNumber = indexedValue.index + 1,
                        sideLabel = side?.label,
                    )
                }
        Triple(
            pointsFor(FootSide.Left),
            pointsFor(FootSide.Right),
            pointsFor(null),
        )
    }

    Panel(title = "分析结果") {
        AnalysisLineChart(
            leftPoints = chartPoints.first,
            rightPoints = chartPoints.second,
            singlePoints = chartPoints.third,
            lineColor = metric.color,
            xLabel = "时间 (s)",
            yLabel = "${metric.label} (${metric.unit})",
            valueLabel = metric.label,
            valueUnit = metric.unit,
            valueDecimals = metric.decimals,
        )
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Border, RoundedCornerShape(8.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(AppSurface, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "指标",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "均值",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(112.dp),
                )
                Text(
                    text = "左右对称性",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(252.dp),
                )
            }
            HorizontalDivider(color = Border)
            metrics.forEachIndexed { index, item ->
                MetricSelectorRow(
                    item = item,
                    selected = item == metric,
                    value = summaryValue(result, item),
                    leftValue = metricSideAverage(result, item, FootSide.Left),
                    rightValue = metricSideAverage(result, item, FootSide.Right),
                    onClick = { metric = item },
                )
                if (index != metrics.lastIndex) {
                    HorizontalDivider(color = Border)
                }
            }
        }
    }
}

@Composable
private fun AdvancedAnalysisContent(
    state: AnalysisUiState,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onClearRange: () -> Unit,
    onApplyRange: () -> Unit,
    onLanConfigChange: ((LanShareUiConfig) -> LanShareUiConfig) -> Unit,
    onSaveLan: () -> Unit,
    onTestLan: () -> Unit,
    onUploadCurrent: () -> Unit,
) {
    val signalResult = state.result?.takeIf {
        it.primarySignal != null || it.secondarySignal != null
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(title = "分析范围") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = state.rangeStartS,
                        onValueChange = onStartChange,
                        label = { Text("开始时间 (s)") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp),
                        colors = analysisTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = state.rangeEndS,
                        onValueChange = onEndChange,
                        label = { Text("结束时间 (s)") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp),
                        colors = analysisTextFieldColors(),
                    )
                    NeutralButton("重置", onClearRange)
                    SuccessButton(
                        "应用范围",
                        onApplyRange,
                        enabled = state.canAnalyze,
                    )
                }
            }
        }
        if (signalResult != null) {
            item {
                Panel(title = "步态事件信号") {
                    SignalLegend()
                    Spacer(Modifier.height(10.dp))
                    SignalEventChart(
                        primary = signalResult.primarySignal,
                        secondary = signalResult.secondarySignal,
                    )
                }
            }
        }
        item {
            LanUploadPanel(
                state = state,
                onConfigChange = onLanConfigChange,
                onSave = onSaveLan,
                onTest = onTestLan,
                onUpload = onUploadCurrent,
            )
        }
        if (state.errorMessage.isNotBlank()) {
            item { MessageBand(state.errorMessage, error = true) }
        }
    }
}

@Composable
private fun HistoryContent(
    state: AnalysisUiState,
    onSelectAthlete: (String) -> Unit,
    onSelectDate: (String) -> Unit,
    onOpen: (AnalysisHistoryItem) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val dates = state.history.map { it.trainingDate }.distinct()
    val records = state.history.filter {
        state.selectedHistoryDate == null || it.trainingDate == state.selectedHistoryDate
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(title = "历史记录") {
                AthleteDropdown(
                    athletes = state.athletes,
                    selectedId = state.historyAthleteId,
                    onSelect = onSelectAthlete,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (dates.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dates) { date ->
                            DateChip(
                                date = date,
                                selected = date == state.selectedHistoryDate,
                                count = state.history.count { it.trainingDate == date },
                                onClick = { onSelectDate(date) },
                            )
                        }
                    }
                }
            }
        }
        if (state.historyAthleteId == null) {
            item { EmptyBand("请先选择运动员") }
        } else if (records.isEmpty()) {
            item { EmptyBand("该日期暂无分析记录") }
        } else {
            items(records, key = { it.id }) { item ->
                HistoryAttemptRow(
                    item = item,
                    onOpen = { onOpen(item) },
                    onDelete = { onDelete(item.id) },
                )
            }
        }
    }
}

@Composable
private fun LanUploadPanel(
    state: AnalysisUiState,
    onConfigChange: ((LanShareUiConfig) -> LanShareUiConfig) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onUpload: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Panel(title = "局域网上传") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = state.lanConfig.enabled,
                onCheckedChange = { checked ->
                    onConfigChange { it.copy(enabled = checked) }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Green,
                    uncheckedTrackColor = Border,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (state.lanConfig.enabled) "已启用" else "未启用",
                color = if (state.lanConfig.enabled) Green else Muted,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Outlined.Tune, contentDescription = "上传设置", tint = AppText)
            }
            NeutralButton(
                text = "上传本次结果",
                onClick = onUpload,
                enabled = state.manifestPath.isNotBlank() && !state.isLanBusy,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LanField("设备 IP / 主机名", state.lanConfig.host) {
                    onConfigChange { config -> config.copy(host = it) }
                }
                LanField("共享文件夹", state.lanConfig.shareName) {
                    onConfigChange { config -> config.copy(shareName = it) }
                }
                LanField("目录", state.lanConfig.remoteDir) {
                    onConfigChange { config -> config.copy(remoteDir = it) }
                }
                LanField("用户名", state.lanConfig.username) {
                    onConfigChange { config -> config.copy(username = it) }
                }
                LanField("密码", state.lanConfig.password, password = true) {
                    onConfigChange { config -> config.copy(password = it) }
                }
                LanField("域", state.lanConfig.domain) {
                    onConfigChange { config -> config.copy(domain = it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.lanConfig.uploadSourceCsv,
                        onCheckedChange = { checked ->
                            onConfigChange { config -> config.copy(uploadSourceCsv = checked) }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("同时上传左右脚原始 CSV", color = AppText)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeutralButton("测试连接", onTest, enabled = !state.isLanBusy)
                    SuccessButton("保存设置", onSave, enabled = !state.isLanBusy)
                }
            }
        }
        if (state.isLanBusy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Green,
                trackColor = Border,
            )
        }
        if (state.lanMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(state.lanMessage, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AthleteDropdown(
    athletes: List<AthleteProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = athletes.firstOrNull { it.id == selectedId }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .background(AppSurface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .clickable(enabled = athletes.isNotEmpty()) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = Muted)
            Spacer(Modifier.width(10.dp))
            Text(
                text = selected?.name ?: "暂无运动员",
                color = if (selected == null) Muted else AppText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            selected?.dominantLeg?.let {
                Badge(text = it.label, type = BadgeType.Ok)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = Muted)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(AppSurface)
                .heightIn(max = 320.dp),
        ) {
            athletes.forEach { athlete ->
                DropdownMenuItem(
                    text = {
                        Text(athlete.name, color = AppText)
                    },
                    onClick = {
                        onSelect(athlete.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SideTag(side: FootSide, present: Boolean) {
    val color = when {
        !present -> Muted
        side == FootSide.Left -> Orange
        else -> Green
    }
    Surface(
        color = if (present) color.copy(alpha = 0.12f) else AppSurface,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.border(
            1.dp,
            if (present) color.copy(alpha = 0.35f) else Border,
            RoundedCornerShape(999.dp),
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Sensors,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = side.label,
                color = color,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MetricSelectorRow(
    item: MetricKey,
    selected: Boolean,
    value: Double?,
    leftValue: Double?,
    rightValue: Double?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp)
            .background(
                if (selected) item.color.copy(alpha = 0.08f) else Color.Transparent,
            )
            .clickable(onClick = onClick),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(item.color),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(
                        1.5.dp,
                        item.color.copy(alpha = if (selected) 1f else 0.72f),
                        RoundedCornerShape(999.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(item.color, RoundedCornerShape(999.dp)),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = if (selected) item.color else AppText,
                                fontWeight = if (selected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                            ),
                        ) {
                            append(item.label)
                        }
                        append("  ")
                        withStyle(SpanStyle(color = Muted)) {
                            append(item.unit)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value?.let { "${item.format(it)} ${item.unit}" } ?: "— ${item.unit}",
                color = if (selected) item.color else AppText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(112.dp),
            )
            MetricSymmetry(
                leftValue = leftValue,
                rightValue = rightValue,
                color = item.color,
                formatter = item::format,
                selected = selected,
                modifier = Modifier.width(252.dp),
            )
        }
    }
}

@Composable
private fun MetricSymmetry(
    leftValue: Double?,
    rightValue: Double?,
    color: Color,
    formatter: (Double) -> String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasBothSides = leftValue != null && rightValue != null
    val mean = if (leftValue != null && rightValue != null) {
        (kotlin.math.abs(leftValue) + kotlin.math.abs(rightValue)) / 2.0
    } else {
        0.0
    }
    val signedDifference = if (
        leftValue != null &&
        rightValue != null &&
        mean > 0.0
    ) {
        ((rightValue - leftValue) / mean).coerceIn(-1.0, 1.0)
    } else {
        0.0
    }
    val activeSegments = kotlin.math.ceil(
        (kotlin.math.abs(signedDifference) / 0.3).coerceIn(0.0, 1.0) * 3.0,
    ).toInt()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = leftValue?.let(formatter) ?: "—",
            color = if (selected) AppText else Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(54.dp),
        )
        Text(
            text = "L",
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(20.dp),
        )
        Row(
            modifier = Modifier.width(84.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(7) { index ->
                val isCenter = index == 3
                val active = when {
                    isCenter -> hasBothSides && activeSegments == 0
                    signedDifference < 0 -> index in (3 - activeSegments)..2
                    signedDifference > 0 -> index in 4..(3 + activeSegments)
                    else -> false
                }
                Box(
                    modifier = Modifier
                        .width(if (isCenter) 3.dp else 7.dp)
                        .height(
                            when (index) {
                                0, 6 -> 9.dp
                                1, 5 -> 13.dp
                                2, 4 -> 17.dp
                                else -> 21.dp
                            },
                        )
                        .background(
                            if (active) color else Border.copy(alpha = 0.72f),
                            RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
        Text(
            text = "R",
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = rightValue?.let(formatter) ?: "—",
            color = if (selected) AppText else Muted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(54.dp),
        )
    }
}

private fun metricSideAverage(
    result: AnalysisResult,
    metric: MetricKey,
    side: FootSide,
): Double? {
    val values = result.strides
        .asSequence()
        .filter { it.side == side }
        .mapNotNull(metric.value)
        .toList()
    return values.takeIf { it.isNotEmpty() }?.average()
}

@Composable
private fun DateChip(
    date: String,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    val label = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
        SimpleDateFormat("MM月dd日", Locale.CHINA).format(parsed!!)
    }.getOrDefault(date)
    Surface(
        color = if (selected) Green.copy(alpha = 0.14f) else AppSurface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .border(
                1.dp,
                if (selected) Green.copy(alpha = 0.35f) else Border,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = if (selected) Green else AppText)
            Text("$count 跳", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HistoryAttemptRow(
    item: AnalysisHistoryItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = Card,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                item.timeLabel,
                color = AppText,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(76.dp),
            )
            SideTag(FootSide.Left, item.hasLeft)
            SideTag(FootSide.Right, item.hasRight)
            Spacer(Modifier.weight(1f))
            Text(item.attemptNo, color = Muted)
            Text(item.mode.label, color = Muted, style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除记录", tint = Red)
            }
        }
    }
}

@Composable
private fun DataPickerDialog(
    attempts: List<CapturedAttempt>,
    selectedKey: String?,
    onDismiss: () -> Unit,
    onSelect: (CapturedAttempt) -> Unit,
) {
    val dates = attempts.map { it.dateKey }.distinct()
    var selectedDate by rememberSaveable {
        mutableStateOf(
            attempts.firstOrNull { it.key == selectedKey }?.dateKey
                ?: dates.firstOrNull()
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Card,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 720.dp)
                .border(1.dp, Border, RoundedCornerShape(12.dp)),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = Green)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "选择数据",
                        color = AppText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${attempts.size} 跳", color = Muted)
                }
                Spacer(Modifier.height(14.dp))
                if (dates.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dates) { date ->
                            DateChip(
                                date = date,
                                selected = date == selectedDate,
                                count = attempts.count { it.dateKey == date },
                                onClick = { selectedDate = date },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (attempts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("暂无可分析的采集数据", color = Muted)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(attempts.filter { it.dateKey == selectedDate }, key = { it.key }) { item ->
                            Surface(
                                color = if (item.key == selectedKey) Green.copy(alpha = 0.1f) else AppSurface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (item.key == selectedKey) Green.copy(alpha = 0.35f) else Border,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onSelect(item) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        item.timeLabel,
                                        color = AppText,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(78.dp),
                                    )
                                    SideTag(FootSide.Left, item.leftPath != null)
                                    SideTag(FootSide.Right, item.rightPath != null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AthleteEditorDialog(
    initial: AthleteProfile?,
    onDismiss: () -> Unit,
    onSave: (AthleteProfile) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var code by rememberSaveable { mutableStateOf(initial?.code.orEmpty()) }
    var gender by rememberSaveable { mutableStateOf(initial?.gender.orEmpty()) }
    var birthDate by rememberSaveable { mutableStateOf(initial?.birthDate.orEmpty()) }
    var height by rememberSaveable { mutableStateOf(initial?.heightCm?.takeIf { it > 0 }?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(initial?.weightKg?.takeIf { it > 0 }?.toString().orEmpty()) }
    var group by rememberSaveable { mutableStateOf(initial?.groupName.orEmpty()) }
    var dominantLeg by rememberSaveable { mutableStateOf(initial?.dominantLeg ?: FootSide.Left) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Card,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 760.dp)
                .border(1.dp, Border, RoundedCornerShape(12.dp)),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (initial == null) "新建运动员" else "编辑运动员",
                    color = AppText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                AthleteField("姓名", name, { name = it })
                AthleteField("运动员编号", code, { code = it })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AthleteField("性别", gender, { gender = it }, Modifier.weight(1f))
                    AthleteField("出生日期", birthDate, { birthDate = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AthleteField("身高 (cm)", height, { height = it }, Modifier.weight(1f))
                    AthleteField("体重 (kg)", weight, { weight = it }, Modifier.weight(1f))
                }
                AthleteField("组别", group, { group = it })
                Text("惯用腿", color = Muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FootSide.values().forEach { side ->
                        FilterChip(
                            selected = dominantLeg == side,
                            onClick = { dominantLeg = side },
                            label = { Text(side.label) },
                            colors = analysisChipColors(),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    NeutralButton("取消", onDismiss)
                    Spacer(Modifier.width(8.dp))
                    SuccessButton(
                        "保存",
                        onClick = {
                            onSave(
                                AthleteProfile(
                                    id = initial?.id.orEmpty(),
                                    code = code,
                                    name = name,
                                    gender = gender,
                                    birthDate = birthDate,
                                    heightCm = height.toDoubleOrNull() ?: 0.0,
                                    weightKg = weight.toDoubleOrNull() ?: 0.0,
                                    groupName = group,
                                    dominantLeg = dominantLeg,
                                    extraJson = initial?.extraJson ?: "{}",
                                )
                            )
                        },
                        enabled = name.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AthleteField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        colors = analysisTextFieldColors(),
    )
}

@Composable
private fun LanField(
    label: String,
    value: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        colors = analysisTextFieldColors(),
    )
}

@Composable
private fun SignalLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot("IC", Color(0xFFEF4444))
        LegendDot("TC", Green)
        LegendDot("MS", Color(0xFFF59E0B))
        LegendDot("MSW", Color(0xFF8B5CF6))
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(999.dp))
        )
        Spacer(Modifier.width(5.dp))
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CompactIconButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(42.dp)
            .background(AppSurface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp)),
    ) {
        Icon(
            imageVector,
            contentDescription = description,
            tint = if (enabled) AppText else Muted,
        )
    }
}

@Composable
private fun MessageBand(message: String, error: Boolean) {
    Surface(
        color = if (error) Red.copy(alpha = 0.1f) else Green.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (error) Red.copy(alpha = 0.35f) else Green.copy(alpha = 0.35f),
                RoundedCornerShape(8.dp),
            ),
    ) {
        Text(
            message,
            color = if (error) Red else Green,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun EmptyBand(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun analysisTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppText,
    unfocusedTextColor = AppText,
    focusedContainerColor = AppSurface,
    unfocusedContainerColor = AppSurface,
    focusedBorderColor = Green.copy(alpha = 0.65f),
    unfocusedBorderColor = Border,
    focusedLabelColor = Green,
    unfocusedLabelColor = Muted,
    cursorColor = Green,
)

@Composable
private fun analysisChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = AppSurface,
    labelColor = Muted,
    selectedContainerColor = Green.copy(alpha = 0.14f),
    selectedLabelColor = Green,
)

private fun summaryValue(result: AnalysisResult, key: MetricKey): Double? {
    val rawValue = when (key) {
        MetricKey.Velocity -> result.summary.velocityMps
        MetricKey.StrideLength -> result.summary.strideLengthM
        MetricKey.Frequency -> result.summary.stepFrequencySpm
        MetricKey.Contact -> result.summary.contactTimeMs
        MetricKey.DoubleSupport -> result.summary.doubleSupportTimeMs
        MetricKey.Flight -> result.summary.flightTimeMs
        MetricKey.Swing -> result.summary.swingTimeMs
        MetricKey.Vgrf -> result.summary.vgrfPeakBw
    }
    return rawValue
}
