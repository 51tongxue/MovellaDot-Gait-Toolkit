package com.buct.xsens.gait.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.res.Configuration
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
import com.buct.xsens.dot.R
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
import com.buct.xsens.gait.analysis.GaitEvents
import com.buct.xsens.gait.analysis.GaitStride
import com.buct.xsens.gait.analysis.LanShareUiConfig
import com.buct.xsens.gait.analysis.SideSignalResult
import com.buct.xsens.gait.ui.components.AnalysisChartPoint
import com.buct.xsens.gait.ui.components.AnalysisChartThreshold
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
        "峰值力",
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
    var showDataDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshAthletes()
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
                onSelectData = {
                    viewModel.refreshCapturedAttempts()
                    showDataDialog = true
                },
                onAnalyze = viewModel::analyze,
            )

            AnalysisSection.Advanced -> AdvancedAnalysisContent(
                state = state,
                onStartChange = viewModel::updateRangeStart,
                onEndChange = viewModel::updateRangeEnd,
                onClearRange = viewModel::clearRange,
                onApplyRange = viewModel::applyRange,
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

    if (showDataDialog) {
        DataPickerDialog(
            attempts = state.availableAttempts,
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
    BoxWithConstraints {
        val landscape = maxWidth >= 840.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (landscape) 48.dp else 44.dp)
                .background(AppSurface)
                .padding(horizontal = if (landscape) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (landscape) 10.dp else 4.dp),
        ) {
            listOf(
                AnalysisSection.Main to "步态分析",
                AnalysisSection.Advanced to "进阶分析",
                AnalysisSection.History to "历史记录",
            ).forEach { (section, label) ->
                val active = selected == section
                Column(
                    modifier = Modifier
                        .width(if (landscape) 86.dp else 92.dp)
                        .fillMaxHeight()
                        .clickable { onSelect(section) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (active) Green else Muted,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(if (landscape) 48.dp else 52.dp)
                            .height(3.dp)
                            .background(
                                if (active) Green else Color.Transparent,
                                RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun MainAnalysisContent(
    state: AnalysisUiState,
    onSelectMode: (AnalysisMode) -> Unit,
    onSelectAthlete: (String) -> Unit,
    onSelectData: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val result = state.result
    var metric by remember(result?.mode, result?.rawJson) {
        mutableStateOf(MetricKey.Velocity)
    }
    var selectedChartPoint by remember(result?.rawJson, metric) {
        mutableStateOf<AnalysisChartPoint?>(null)
    }
    fun clearChartSelection() {
        selectedChartPoint = null
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(result, metric) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    selectedChartPoint = null
                }
            },
    ) {
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val useLandscapeWorkspace = isLandscape && result != null
        val useTabletPortrait = !isLandscape && maxWidth >= 520.dp
        if (useLandscapeWorkspace && result != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AnalysisPreparationPanel(
                    state = state,
                    onSelectMode = {
                        clearChartSelection()
                        onSelectMode(it)
                    },
                    onSelectAthlete = {
                        clearChartSelection()
                        onSelectAthlete(it)
                    },
                    onSelectData = {
                        clearChartSelection()
                        onSelectData()
                    },
                    onAnalyze = {
                        clearChartSelection()
                        onAnalyze()
                    },
                )
                if (state.isAnalyzing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = Green,
                        trackColor = Border,
                    )
                }
                if (state.errorMessage.isNotBlank()) {
                    MessageBand(state.errorMessage, error = true)
                } else if (!state.isAnalyzing && state.statusMessage.isNotBlank()) {
                    MessageBand(state.statusMessage, error = false)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnalysisResultChartPanel(
                        result = result,
                        metric = metric,
                        compact = true,
                        fillAvailableHeight = true,
                        selectedPoint = selectedChartPoint,
                        onSelectedPointChange = { selectedChartPoint = it },
                        modifier = Modifier
                            .weight(2.05f)
                            .fillMaxHeight(),
                    )
                    AnalysisMetricTable(
                        result = result,
                        metric = metric,
                        onSelectMetric = {
                            clearChartSelection()
                            metric = it
                        },
                        scrollable = true,
                        compactRows = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    10.dp,
                ),
            ) {
                item {
                    AnalysisPreparationPanel(
                        state = state,
                        onSelectMode = {
                            clearChartSelection()
                            onSelectMode(it)
                        },
                        onSelectAthlete = {
                            clearChartSelection()
                            onSelectAthlete(it)
                        },
                        onSelectData = {
                            clearChartSelection()
                            onSelectData()
                        },
                        onAnalyze = {
                            clearChartSelection()
                            onAnalyze()
                        },
                    )
                }
                if (state.isAnalyzing) {
                    item {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = Green,
                            trackColor = Border,
                        )
                    }
                }
                if (state.errorMessage.isNotBlank()) {
                    item { MessageBand(state.errorMessage, error = true) }
                } else if (!state.isAnalyzing && state.statusMessage.isNotBlank()) {
                    item { MessageBand(state.statusMessage, error = false) }
                }
                result?.let { analysisResult ->
                    item {
                        AnalysisResultChartPanel(
                            result = analysisResult,
                            metric = metric,
                            chartHeight = if (useTabletPortrait) 300.dp else 240.dp,
                            selectedPoint = selectedChartPoint,
                            onSelectedPointChange = { selectedChartPoint = it },
                        )
                    }
                    item {
                        AnalysisMetricTable(
                            result = analysisResult,
                            metric = metric,
                            onSelectMetric = {
                                clearChartSelection()
                                metric = it
                            },
                            compactRows = useTabletPortrait,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisPreparationPanel(
    state: AnalysisUiState,
    onSelectMode: (AnalysisMode) -> Unit,
    onSelectAthlete: (String) -> Unit,
    onSelectData: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val selectedAttempt = state.selectedAttempt
    val hasResult = state.result != null
    BoxWithConstraints {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val tabletPortrait = !landscape && maxWidth >= 520.dp
        val compactControlHeight = if (landscape) 38.dp else 36.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .padding(
                    horizontal = if (tabletPortrait) 12.dp else 16.dp,
                    vertical = when {
                        landscape -> 8.dp
                        tabletPortrait -> 6.dp
                        else -> 12.dp
                    },
                ),
        ) {
            when {
                landscape -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AnalysisModeSelector(
                        selected = state.analysisMode,
                        onSelect = onSelectMode,
                        enabled = !state.isAnalyzing,
                        controlHeight = compactControlHeight,
                        modifier = Modifier.weight(0.9f),
                    )
                    AthleteDropdown(
                        athletes = state.athletes,
                        selectedId = state.selectedAthleteId,
                        onSelect = onSelectAthlete,
                        showDominantLeg = false,
                        controlHeight = compactControlHeight,
                        modifier = Modifier.weight(0.7f),
                    )
                    TrainingDataSelector(
                        selectedAttempt = selectedAttempt,
                        enabled = !state.isAnalyzing,
                        onClick = onSelectData,
                        compact = true,
                        controlHeight = compactControlHeight,
                        modifier = Modifier.weight(1.7f),
                    )
                    CompactAnalyzeButton(
                        text = when {
                            state.isAnalyzing -> "分析中"
                            hasResult -> "重新分析"
                            else -> "开始分析"
                        },
                        onClick = onAnalyze,
                        enabled = state.canAnalyze,
                        loading = state.isAnalyzing,
                        modifier = Modifier
                            .width(132.dp)
                            .height(compactControlHeight),
                    )
                }

                tabletPortrait -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PreparationField(
                            label = "分析模式",
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            AnalysisModeSelector(
                                selected = state.analysisMode,
                                onSelect = onSelectMode,
                                enabled = !state.isAnalyzing,
                                controlHeight = compactControlHeight,
                            )
                        }
                        PreparationField(
                            label = "运动员",
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            AthleteDropdown(
                                athletes = state.athletes,
                                selectedId = state.selectedAthleteId,
                                onSelect = onSelectAthlete,
                                showDominantLeg = false,
                                controlHeight = compactControlHeight,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PreparationField(
                            label = "采集记录",
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            TrainingDataSelector(
                                selectedAttempt = selectedAttempt,
                                enabled = !state.isAnalyzing,
                                onClick = onSelectData,
                                compact = true,
                                controlHeight = compactControlHeight,
                            )
                        }
                        CompactAnalyzeButton(
                            text = if (state.isAnalyzing) "分析中" else "开始分析",
                            onClick = onAnalyze,
                            enabled = state.canAnalyze,
                            loading = state.isAnalyzing,
                            modifier = Modifier
                                .width(132.dp)
                                .height(compactControlHeight),
                        )
                    }
                }

                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreparationField(label = "分析模式") {
                        AnalysisModeSelector(
                            selected = state.analysisMode,
                            onSelect = onSelectMode,
                            enabled = !state.isAnalyzing,
                            controlHeight = 50.dp,
                        )
                    }
                    PreparationField(label = "运动员") {
                        AthleteDropdown(
                            athletes = state.athletes,
                            selectedId = state.selectedAthleteId,
                            onSelect = onSelectAthlete,
                            controlHeight = 50.dp,
                        )
                    }
                    PreparationField(label = "采集记录") {
                        TrainingDataSelector(
                            selectedAttempt = selectedAttempt,
                            enabled = !state.isAnalyzing,
                            onClick = onSelectData,
                            controlHeight = 58.dp,
                        )
                    }
                    CompactAnalyzeButton(
                        text = if (state.isAnalyzing) "分析中" else "开始分析",
                        onClick = onAnalyze,
                        enabled = state.canAnalyze,
                        loading = state.isAnalyzing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalysisModeSelector(
    selected: AnalysisMode,
    onSelect: (AnalysisMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    controlHeight: Dp = 54.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(controlHeight)
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
                    .clickable(enabled = enabled) { onSelect(mode) },
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
private fun PreparationField(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 7.dp),
    ) {
        Text(
            text = label,
            color = Muted,
            style = if (compact) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.labelMedium
            },
        )
        content()
    }
}

@Composable
private fun CompactAnalyzeButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Green,
            contentColor = Color(0xFF062E18),
            disabledContainerColor = Green.copy(alpha = 0.42f),
            disabledContentColor = Color.White.copy(alpha = 0.72f),
        ),
        shape = RoundedCornerShape(7.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White.copy(alpha = 0.9f),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ShowChart,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TrainingDataSelector(
    selectedAttempt: CapturedAttempt?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    controlHeight: Dp = 58.dp,
) {
    Row(
        modifier = modifier
            .height(controlHeight)
            .background(AppSurface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarMonth,
            contentDescription = null,
            tint = Green,
            modifier = Modifier.size(if (compact) 18.dp else 22.dp),
        )
        if (compact) {
            Text(
                text = selectedAttempt?.let {
                    "${it.dateLabel.toIsoDate()} ${it.timeLabel}"
                } ?: "选择采集记录",
                color = if (selectedAttempt == null) Muted else AppText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selectedAttempt == null) {
                    FontWeight.Normal
                } else {
                    FontWeight.SemiBold
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selectedAttempt != null) {
                CompactSideTag(FootSide.Left, selectedAttempt.leftPath != null)
                CompactSideTag(FootSide.Right, selectedAttempt.rightPath != null)
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedAttempt?.let {
                        "${it.dateLabel}  ${it.timeLabel}"
                    } ?: "选择采集记录",
                    color = if (selectedAttempt == null) Muted else AppText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selectedAttempt == null) {
                        FontWeight.Normal
                    } else {
                        FontWeight.SemiBold
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectedAttempt != null) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SideTag(FootSide.Left, selectedAttempt.leftPath != null)
                        SideTag(FootSide.Right, selectedAttempt.rightPath != null)
                    }
                }
            }
        }
        if (!compact || selectedAttempt == null) {
            Text(
                text = if (selectedAttempt == null) "选择" else "更换",
                color = Green,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun String.toIsoDate(): String =
    replace("年", "-")
        .replace("月", "-")
        .removeSuffix("日")

@Composable
private fun AnalysisResultChartPanel(
    result: AnalysisResult,
    metric: MetricKey,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    chartHeight: Dp = 220.dp,
    fillAvailableHeight: Boolean = false,
    selectedPoint: AnalysisChartPoint?,
    onSelectedPointChange: (AnalysisChartPoint?) -> Unit,
) {
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
    val chartThreshold = remember(result, metric) {
        if (result.mode == AnalysisMode.RaceWalk && metric == MetricKey.Flight) {
            val thresholdMs = 40.0
            val exceededCount = result.strides.mapNotNull { it.flightTimeMs }
                .count { it > thresholdMs }
            AnalysisChartThreshold(
                value = thresholdMs,
                label = "40 ms",
                summary = "40 ms 阈值 · 超过 $exceededCount 步",
                color = Red,
            )
        } else {
            null
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(if (compact) 16.dp else 16.dp),
    ) {
        val average = summaryValue(result, metric)
        val leftAverage = metricSideAverage(result, metric, FootSide.Left)
        val rightAverage = metricSideAverage(result, metric, FootSide.Right)
        val headerReserve = if (chartThreshold == null) 62.dp else 90.dp
        val resolvedChartHeight = if (fillAvailableHeight) {
            (maxHeight - headerReserve).coerceAtLeast(170.dp)
        } else {
            chartHeight
        }
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = metric.label,
                        color = AppText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = average?.let(metric::format) ?: "—",
                            color = metric.color,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${metric.unit} 均值",
                            color = Muted,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                ChartSideAverage(
                    side = FootSide.Left,
                    value = leftAverage,
                    metric = metric,
                    color = metric.color.copy(alpha = 0.55f),
                )
                Spacer(Modifier.width(18.dp))
                ChartSideAverage(
                    side = FootSide.Right,
                    value = rightAverage,
                    metric = metric,
                    color = metric.color,
                )
            }
            HorizontalDivider(color = Border)
            Spacer(Modifier.height(10.dp))
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
                threshold = chartThreshold,
                chartHeight = resolvedChartHeight,
                showHeader = false,
                selectedPoint = selectedPoint,
                onSelectedPointChange = onSelectedPointChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChartSideAverage(
    side: FootSide,
    value: Double?,
    metric: MetricKey,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "${side.label} ${value?.let(metric::format) ?: "—"}",
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(2.dp)
                .background(color, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun AnalysisMetricTable(
    result: AnalysisResult,
    metric: MetricKey,
    onSelectMetric: (MetricKey) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    compactRows: Boolean = false,
) {
    val denseRows = scrollable || compactRows
    val averageWidth = when {
        scrollable -> 58.dp
        compactRows -> 128.dp
        else -> 128.dp
    }
    val symmetryWidth = when {
        scrollable -> 102.dp
        compactRows -> 184.dp
        else -> 252.dp
    }
    val rowHeight = when {
        scrollable -> 42.dp
        compactRows -> 44.dp
        else -> 64.dp
    }
    val metrics = remember(result.mode) {
        MetricKey.values().filter { key ->
            result.mode != AnalysisMode.LongJump || key != MetricKey.DoubleSupport
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(if (denseRows) 6.dp else 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.fillMaxHeight() else Modifier),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = if (denseRows) 10.dp else 16.dp),
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
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(averageWidth),
                )
                Spacer(Modifier.width(if (denseRows) 8.dp else 12.dp))
                Text(
                    text = "左 / 右",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(symmetryWidth),
                )
            }
            HorizontalDivider(color = Border)
            if (scrollable) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(metrics) { index, item ->
                        MetricSelectorRow(
                            item = item,
                            selected = item == metric,
                            value = summaryValue(result, item),
                            leftValue = metricSideAverage(result, item, FootSide.Left),
                            rightValue = metricSideAverage(result, item, FootSide.Right),
                            onClick = { onSelectMetric(item) },
                            compact = denseRows,
                            rowHeight = rowHeight,
                            valueWidth = averageWidth,
                            symmetryWidth = symmetryWidth,
                        )
                        if (index != metrics.lastIndex) {
                            HorizontalDivider(color = Border)
                        }
                    }
                }
            } else {
                metrics.forEachIndexed { index, item ->
                    MetricSelectorRow(
                        item = item,
                        selected = item == metric,
                        value = summaryValue(result, item),
                        leftValue = metricSideAverage(result, item, FootSide.Left),
                        rightValue = metricSideAverage(result, item, FootSide.Right),
                        onClick = { onSelectMetric(item) },
                        compact = denseRows,
                        rowHeight = rowHeight,
                        valueWidth = averageWidth,
                        symmetryWidth = symmetryWidth,
                    )
                    if (index != metrics.lastIndex) {
                        HorizontalDivider(color = Border)
                    }
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
    var signalSide by rememberSaveable {
        mutableStateOf(AdvancedSignalSide.Both)
    }
    val hasLeftSignal = signalResult?.let {
        listOfNotNull(it.primarySignal, it.secondarySignal)
            .any { signal -> signal.side == FootSide.Left }
    } == true
    val hasRightSignal = signalResult?.let {
        listOfNotNull(it.primarySignal, it.secondarySignal)
            .any { signal -> signal.side == FootSide.Right }
    } == true
    LaunchedEffect(signalResult, signalSide, hasLeftSignal, hasRightSignal) {
        val selectedSideAvailable = when (signalSide) {
            AdvancedSignalSide.Both -> hasLeftSignal || hasRightSignal
            AdvancedSignalSide.Left -> hasLeftSignal
            AdvancedSignalSide.Right -> hasRightSignal
        }
        if (!selectedSideAvailable) {
            signalSide = AdvancedSignalSide.Both
        }
    }
    val visiblePrimary = signalResult?.primarySignal
        ?.takeIf { signalSide.includes(it.side) }
    val visibleSecondary = signalResult?.secondarySignal
        ?.takeIf { signalSide.includes(it.side) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val eventStats = remember(
        signalResult,
        signalSide,
        state.appliedRangeStartS,
        state.appliedRangeEndS,
    ) {
        advancedEventStats(
            primary = signalResult?.primarySignal,
            secondary = signalResult?.secondarySignal,
            selectedSide = signalSide,
            startS = state.appliedRangeStartS,
            endS = state.appliedRangeEndS,
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (landscape && signalResult != null) {
            val chartHeight = (maxHeight - 72.dp).coerceAtLeast(260.dp)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AdvancedSignalPanel(
                    primary = visiblePrimary,
                    secondary = visibleSecondary,
                    selectedSide = signalSide,
                    hasLeft = hasLeftSignal,
                    hasRight = hasRightSignal,
                    onSelectSide = { signalSide = it },
                    rangeStartS = state.appliedRangeStartS,
                    rangeEndS = state.appliedRangeEndS,
                    chartHeight = chartHeight,
                    modifier = Modifier
                        .weight(1.8f)
                        .fillMaxHeight(),
                )
                Column(
                    modifier = Modifier
                        .weight(0.82f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AdvancedEventSummary(
                        stats = eventStats,
                        compact = true,
                        selectedSide = signalSide,
                    )
                    AdvancedRangePanel(
                        state = state,
                        stackedActions = true,
                        onStartChange = onStartChange,
                        onEndChange = onEndChange,
                        onClearRange = onClearRange,
                        onApplyRange = onApplyRange,
                    )
                    LanUploadPanel(
                        state = state,
                        onConfigChange = onLanConfigChange,
                        onSave = onSaveLan,
                        onTest = onTestLan,
                        onUpload = onUploadCurrent,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (signalResult != null) {
                    item {
                        AdvancedEventSummary(
                            stats = eventStats,
                            compact = false,
                            selectedSide = signalSide,
                        )
                    }
                }
                item {
                    AdvancedRangePanel(
                        state = state,
                        stackedActions = false,
                        onStartChange = onStartChange,
                        onEndChange = onEndChange,
                        onClearRange = onClearRange,
                        onApplyRange = onApplyRange,
                    )
                }
                if (signalResult != null) {
                    item {
                        AdvancedSignalPanel(
                            primary = visiblePrimary,
                            secondary = visibleSecondary,
                            selectedSide = signalSide,
                            hasLeft = hasLeftSignal,
                            hasRight = hasRightSignal,
                            onSelectSide = { signalSide = it },
                            rangeStartS = state.appliedRangeStartS,
                            rangeEndS = state.appliedRangeEndS,
                            chartHeight = if (maxWidth >= 520.dp) 360.dp else 300.dp,
                        )
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
            }
        }
    }
}

private enum class AdvancedSignalSide(
    val label: String,
) {
    Both("双脚"),
    Left("左脚"),
    Right("右脚");

    fun includes(side: FootSide?): Boolean = when (this) {
        Both -> true
        Left -> side == FootSide.Left
        Right -> side == FootSide.Right
    }
}

private data class AdvancedEventStat(
    val label: String,
    val color: Color,
    val total: Int,
    val left: Int,
    val right: Int,
)

private fun advancedEventStats(
    primary: SideSignalResult?,
    secondary: SideSignalResult?,
    selectedSide: AdvancedSignalSide,
    startS: Double?,
    endS: Double?,
): List<AdvancedEventStat> {
    val signals = listOfNotNull(primary, secondary)
        .filter { selectedSide.includes(it.side) }
    val startMs = startS?.times(1000.0)
    val endMs = endS?.times(1000.0)

    fun count(
        side: FootSide,
        selector: (GaitEvents) -> List<Double>,
    ): Int = signals
        .filter { it.side == side }
        .sumOf { signal ->
            selector(signal.events).count { timestamp ->
                (startMs == null || timestamp >= startMs) &&
                    (endMs == null || timestamp <= endMs)
            }
        }

    return listOf(
        "IC" to Color(0xFFEF4444),
        "TC" to Green,
        "MS" to Color(0xFFF59E0B),
        "MSW" to Color(0xFF8B5CF6),
    ).map { (label, color) ->
        val selector: (GaitEvents) -> List<Double> = when (label) {
            "IC" -> { events -> events.ic }
            "TC" -> { events -> events.tc }
            "MS" -> { events -> events.ms }
            else -> { events -> events.msw }
        }
        val left = count(FootSide.Left, selector)
        val right = count(FootSide.Right, selector)
        AdvancedEventStat(
            label = label,
            color = color,
            total = left + right,
            left = left,
            right = right,
        )
    }
}

@Composable
private fun AdvancedEventSummary(
    stats: List<AdvancedEventStat>,
    compact: Boolean,
    selectedSide: AdvancedSignalSide,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (selectedSide == AdvancedSignalSide.Both) {
                "事件摘要"
            } else {
                "${selectedSide.label}事件摘要"
            },
            color = AppText,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (compact) {
            stats.chunked(2).forEach { rowStats ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowStats.forEach { stat ->
                        AdvancedEventStatCell(
                            stat = stat,
                            selectedSide = selectedSide,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stats.forEach { stat ->
                    AdvancedEventStatCell(
                        stat = stat,
                        selectedSide = selectedSide,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedEventStatCell(
    stat: AdvancedEventStat,
    selectedSide: AdvancedSignalSide,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(76.dp)
            .background(AppSurface, RoundedCornerShape(7.dp))
            .border(1.dp, Border, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(stat.color, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stat.label,
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stat.total.toString(),
                color = AppText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = when (selectedSide) {
                    AdvancedSignalSide.Both -> "${stat.left}L · ${stat.right}R"
                    AdvancedSignalSide.Left -> "左脚"
                    AdvancedSignalSide.Right -> "右脚"
                },
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AdvancedRangePanel(
    state: AnalysisUiState,
    stackedActions: Boolean,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onClearRange: () -> Unit,
    onApplyRange: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "时间范围",
                color = AppText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            AdvancedRangeBadge(
                startS = state.appliedRangeStartS,
                endS = state.appliedRangeEndS,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdvancedRangeField(
                label = "开始 (s)",
                value = state.rangeStartS,
                onValueChange = onStartChange,
                modifier = Modifier.weight(1f),
            )
            AdvancedRangeField(
                label = "结束 (s)",
                value = state.rangeEndS,
                onValueChange = onEndChange,
                modifier = Modifier.weight(1f),
            )
        }
        if (stackedActions) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AdvancedRangeButton(
                    text = "重置",
                    primary = false,
                    enabled = true,
                    onClick = onClearRange,
                    modifier = Modifier.weight(1f),
                )
                AdvancedRangeButton(
                    text = "应用",
                    primary = true,
                    enabled = state.result != null && !state.isAnalyzing,
                    onClick = onApplyRange,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AdvancedRangeButton(
                    text = "重置",
                    primary = false,
                    enabled = true,
                    onClick = onClearRange,
                    modifier = Modifier.width(86.dp),
                )
                Spacer(Modifier.width(8.dp))
                AdvancedRangeButton(
                    text = "应用范围",
                    primary = true,
                    enabled = state.result != null && !state.isAnalyzing,
                    onClick = onApplyRange,
                    modifier = Modifier.width(112.dp),
                )
            }
        }
        if (state.rangeErrorMessage.isNotBlank()) {
            Text(
                text = state.rangeErrorMessage,
                color = Red,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AdvancedRangeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = "全部",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = analysisTextFieldColors(),
            shape = RoundedCornerShape(7.dp),
        )
    }
}

@Composable
private fun AdvancedRangeButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Green else AppSurface,
            contentColor = if (primary) Color(0xFF062E18) else AppText,
            disabledContainerColor = if (primary) Green.copy(alpha = 0.38f) else AppSurface,
            disabledContentColor = Muted,
        ),
        shape = RoundedCornerShape(7.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun AdvancedRangeBadge(
    startS: Double?,
    endS: Double?,
) {
    val label = when {
        startS == null && endS == null -> "全部数据"
        startS != null && endS != null -> "${formatRangeValue(startS)}–${formatRangeValue(endS)} s"
        startS != null -> "${formatRangeValue(startS)} s 起"
        else -> "至 ${formatRangeValue(endS!!)} s"
    }
    Surface(
        color = Green.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.border(
            1.dp,
            Green.copy(alpha = 0.32f),
            RoundedCornerShape(6.dp),
        ),
    ) {
        Text(
            text = label,
            color = Green,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun formatRangeValue(value: Double): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
private fun AdvancedSignalPanel(
    primary: SideSignalResult?,
    secondary: SideSignalResult?,
    selectedSide: AdvancedSignalSide,
    hasLeft: Boolean,
    hasRight: Boolean,
    onSelectSide: (AdvancedSignalSide) -> Unit,
    rangeStartS: Double?,
    rangeEndS: Double?,
    chartHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "步态事件信号",
                    color = AppText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${selectedSide.label}角速度与事件位置",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            AdvancedSignalSideSelector(
                selected = selectedSide,
                hasLeft = hasLeft,
                hasRight = hasRight,
                onSelect = onSelectSide,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalLegend()
            Spacer(Modifier.weight(1f))
            AdvancedRangeBadge(
                startS = rangeStartS,
                endS = rangeEndS,
            )
        }
        SignalEventChart(
            primary = primary,
            secondary = secondary,
            rangeStartS = rangeStartS,
            rangeEndS = rangeEndS,
            chartHeight = chartHeight,
        )
    }
}

@Composable
private fun AdvancedSignalSideSelector(
    selected: AdvancedSignalSide,
    hasLeft: Boolean,
    hasRight: Boolean,
    onSelect: (AdvancedSignalSide) -> Unit,
) {
    Row(
        modifier = Modifier
            .width(204.dp)
            .height(34.dp)
            .background(AppSurface, RoundedCornerShape(7.dp))
            .border(1.dp, Border, RoundedCornerShape(7.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        AdvancedSignalSide.values().forEach { option ->
            val enabled = when (option) {
                AdvancedSignalSide.Both -> hasLeft || hasRight
                AdvancedSignalSide.Left -> hasLeft
                AdvancedSignalSide.Right -> hasRight
            }
            val active = selected == option
            val optionColor = when (option) {
                AdvancedSignalSide.Both -> Green
                AdvancedSignalSide.Left -> Orange
                AdvancedSignalSide.Right -> Green
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        color = if (active) optionColor.copy(alpha = 0.16f) else Color.Transparent,
                        shape = RoundedCornerShape(5.dp),
                    )
                    .clickable(enabled = enabled) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    color = when {
                        !enabled -> Muted.copy(alpha = 0.4f)
                        active -> optionColor
                        else -> Muted
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HistoryContent(
    state: AnalysisUiState,
    onSelectAthlete: (String) -> Unit,
    onSelectDate: (String?) -> Unit,
    onOpen: (AnalysisHistoryItem) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var selectedModeCode by rememberSaveable(state.historyAthleteId) {
        mutableStateOf<String?>(null)
    }
    val dates = remember(state.history) {
        state.history
            .map { it.trainingDate }
            .distinct()
            .sortedDescending()
    }
    val records = remember(
        state.history,
        state.selectedHistoryDate,
        selectedModeCode,
    ) {
        state.history
            .asSequence()
            .filter {
                state.selectedHistoryDate == null ||
                    it.trainingDate == state.selectedHistoryDate
            }
            .filter {
                selectedModeCode == null || it.mode.code == selectedModeCode
            }
            .sortedWith(
                compareByDescending<AnalysisHistoryItem> { it.trainingDate }
                    .thenByDescending { it.timeLabel },
            )
            .toList()
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HistoryFilterBar(
            athletes = state.athletes,
            selectedAthleteId = state.historyAthleteId,
            dates = dates,
            selectedDate = state.selectedHistoryDate,
            selectedModeCode = selectedModeCode,
            recordCount = records.size,
            landscape = landscape,
            onSelectAthlete = onSelectAthlete,
            onSelectDate = onSelectDate,
            onSelectMode = { selectedModeCode = it },
        )
        when {
            state.historyAthleteId == null -> {
                EmptyBand("请先选择运动员")
            }

            records.isEmpty() -> {
                EmptyBand("当前筛选条件下暂无历史记录")
            }

            landscape -> {
                HistoryLandscapeTable(
                    records = records,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                HistoryPortraitList(
                    records = records,
                    onOpen = onOpen,
                    onDelete = onDelete,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HistoryFilterBar(
    athletes: List<AthleteProfile>,
    selectedAthleteId: String?,
    dates: List<String>,
    selectedDate: String?,
    selectedModeCode: String?,
    recordCount: Int,
    landscape: Boolean,
    onSelectAthlete: (String) -> Unit,
    onSelectDate: (String?) -> Unit,
    onSelectMode: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(
                horizontal = 12.dp,
                vertical = if (landscape) 8.dp else 10.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AthleteDropdown(
                    athletes = athletes,
                    selectedId = selectedAthleteId,
                    onSelect = onSelectAthlete,
                    showDominantLeg = false,
                    controlHeight = 38.dp,
                    modifier = Modifier.weight(1f),
                )
                HistoryChoiceDropdown(
                    label = selectedModeCode
                        ?.let(AnalysisMode::fromCode)
                        ?.label
                        ?: "全部模式",
                    options = listOf(null to "全部模式") +
                        AnalysisMode.values().map { it.code to it.label },
                    onSelect = onSelectMode,
                    leadingColor = selectedModeCode
                        ?.let(AnalysisMode::fromCode)
                        ?.let(::historyModeColor)
                        ?: Orange,
                    controlHeight = 38.dp,
                    modifier = Modifier.weight(0.85f),
                )
                HistoryChoiceDropdown(
                    label = selectedDate?.let(::historyDateLabel) ?: "全部日期",
                    options = listOf(null to "全部日期") +
                        dates.map { it to historyDateLabel(it) },
                    onSelect = onSelectDate,
                    leadingIcon = Icons.Outlined.CalendarMonth,
                    controlHeight = 38.dp,
                    modifier = Modifier.weight(1.1f),
                )
                HistoryCountBadge(recordCount)
            }
        } else {
            HistoryFilterField(label = "运动员") {
                AthleteDropdown(
                    athletes = athletes,
                    selectedId = selectedAthleteId,
                    onSelect = onSelectAthlete,
                    showDominantLeg = false,
                    controlHeight = 46.dp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HistoryChoiceDropdown(
                    label = selectedDate?.let(::historyDateLabel) ?: "全部日期",
                    options = listOf(null to "全部日期") +
                        dates.map { it to historyDateLabel(it) },
                    onSelect = onSelectDate,
                    leadingIcon = Icons.Outlined.CalendarMonth,
                    controlHeight = 44.dp,
                    modifier = Modifier.weight(1f),
                )
                HistoryChoiceDropdown(
                    label = selectedModeCode
                        ?.let(AnalysisMode::fromCode)
                        ?.label
                        ?: "全部模式",
                    options = listOf(null to "全部模式") +
                        AnalysisMode.values().map { it.code to it.label },
                    onSelect = onSelectMode,
                    leadingColor = selectedModeCode
                        ?.let(AnalysisMode::fromCode)
                        ?.let(::historyModeColor)
                        ?: Orange,
                    controlHeight = 44.dp,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                HistoryCountBadge(recordCount)
            }
        }
    }
}

@Composable
private fun HistoryCountBadge(recordCount: Int) {
    Surface(
        color = Green.copy(alpha = 0.1f),
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .height(38.dp)
            .border(1.dp, Green.copy(alpha = 0.42f), RoundedCornerShape(7.dp)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$recordCount 条",
                color = Green,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HistoryFilterField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
        )
        content()
    }
}

@Composable
private fun HistoryChoiceDropdown(
    label: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingColor: Color? = null,
    controlHeight: Dp = 44.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(controlHeight)
                .background(AppSurface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingColor?.let { color ->
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .border(1.5.dp, color, RoundedCornerShape(999.dp)),
                )
                Spacer(Modifier.width(8.dp))
            }
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = Green,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = label,
                color = AppText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(AppSurface)
                .heightIn(max = 320.dp),
        ) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = {
                        Text(optionLabel, color = AppText)
                    },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryLandscapeTable(
    records: List<AnalysisHistoryItem>,
    onOpen: (AnalysisHistoryItem) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近分析",
                color = AppText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "按采集时间倒序",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        HorizontalDivider(color = Border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HistoryTableHeader("日期 / 时间", 154.dp)
            HistoryTableHeader("模式", 136.dp)
            HistoryTableHeader("数据", 150.dp)
            HistoryTableHeader("步频", 104.dp)
            HistoryTableHeader("步幅", 104.dp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(40.dp))
        }
        HorizontalDivider(color = Border)
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(records, key = { _, item -> item.id }) { index, item ->
                HistoryLandscapeRow(
                    item = item,
                    highlighted = index == 0,
                    onOpen = { onOpen(item) },
                    onDelete = { onDelete(item.id) },
                )
                if (index != records.lastIndex) {
                    HorizontalDivider(color = Border.copy(alpha = 0.65f))
                }
            }
        }
    }
}

@Composable
private fun HistoryTableHeader(
    label: String,
    width: Dp,
) {
    Text(
        text = label,
        color = Muted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun HistoryLandscapeRow(
    item: AnalysisHistoryItem,
    highlighted: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(
                if (highlighted) Accent.copy(alpha = 0.07f) else Color.Transparent,
            )
            .clickable(onClick = onOpen),
    ) {
        if (highlighted) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(Green),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(154.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = historyDateLabel(item.trainingDate),
                    color = AppText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.timeLabel,
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            HistoryModeCell(
                mode = item.mode,
                modifier = Modifier.width(136.dp),
            )
            Row(
                modifier = Modifier.width(150.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistorySideTag(FootSide.Left, item.hasLeft)
                HistorySideTag(FootSide.Right, item.hasRight)
            }
            HistoryMetricCell(
                value = item.stepFrequencySpm,
                unit = "spm",
                decimals = 0,
                modifier = Modifier.width(104.dp),
            )
            HistoryMetricCell(
                value = item.strideLengthM,
                unit = "m",
                decimals = 2,
                modifier = Modifier.width(104.dp),
            )
            Spacer(Modifier.weight(1f))
            HistoryMoreMenu(onDelete = onDelete)
        }
    }
}

@Composable
private fun HistoryMetricCell(
    value: Double?,
    unit: String,
    decimals: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = AppText,
                    fontWeight = FontWeight.SemiBold,
                ),
            ) {
                append(
                    value?.let {
                        String.format(Locale.US, "%.${decimals}f", it)
                    } ?: "—",
                )
            }
            append(" ")
            withStyle(SpanStyle(color = Muted)) {
                append(unit)
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun HistoryPortraitList(
    records: List<AnalysisHistoryItem>,
    onOpen: (AnalysisHistoryItem) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "最近活动",
                color = AppText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
        itemsIndexed(records, key = { _, item -> item.id }) { index, item ->
            HistoryPortraitRow(
                item = item,
                highlighted = index == 0,
                onOpen = { onOpen(item) },
                onDelete = { onDelete(item.id) },
            )
        }
    }
}

@Composable
private fun HistoryPortraitRow(
    item: AnalysisHistoryItem,
    highlighted: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp)
            .background(
                if (highlighted) Accent.copy(alpha = 0.07f) else Card,
                RoundedCornerShape(8.dp),
            )
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen),
    ) {
        if (highlighted) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(Green),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HistoryModeMark(mode = item.mode, size = 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = historyModeTitle(item.mode),
                    color = AppText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${historyDateLabel(item.trainingDate)}  ${item.timeLabel}",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistorySideTag(FootSide.Left, item.hasLeft)
                HistorySideTag(FootSide.Right, item.hasRight)
            }
            HistoryMoreMenu(onDelete = onDelete)
        }
    }
}

@Composable
private fun HistoryModeCell(
    mode: AnalysisMode,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryModeMark(mode = mode, size = 32.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = mode.label,
            color = AppText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HistoryModeMark(
    mode: AnalysisMode,
    size: Dp,
) {
    val color = historyModeColor(mode)
    Box(
        modifier = Modifier
            .size(size)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(historyModeIcon(mode)),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

@Composable
private fun HistorySideTag(
    side: FootSide,
    present: Boolean,
) {
    val color = when {
        !present -> Muted
        side == FootSide.Left -> Orange
        else -> Green
    }
    Surface(
        color = if (present) color.copy(alpha = 0.12f) else AppSurface,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier
            .width(54.dp)
            .height(28.dp)
            .border(
                1.dp,
                if (present) color.copy(alpha = 0.58f) else Border,
                RoundedCornerShape(7.dp),
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = side.label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HistoryMoreMenu(
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "更多操作",
                tint = Muted,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AppSurface),
        ) {
            DropdownMenuItem(
                text = {
                    Text("删除记录", color = Red)
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

private fun historyModeIcon(mode: AnalysisMode): Int = when (mode) {
    AnalysisMode.GeneralGait -> R.drawable.ic_history_general_gait
    AnalysisMode.LongJump -> R.drawable.ic_history_long_jump
    AnalysisMode.RaceWalk -> R.drawable.ic_history_race_walk
}

private fun historyModeColor(mode: AnalysisMode): Color = when (mode) {
    AnalysisMode.GeneralGait -> Green
    AnalysisMode.LongJump -> Accent
    AnalysisMode.RaceWalk -> Orange
}

private fun historyModeTitle(mode: AnalysisMode): String = when (mode) {
    AnalysisMode.GeneralGait -> "通用步态分析"
    AnalysisMode.LongJump -> "跳远分析"
    AnalysisMode.RaceWalk -> "竞走分析"
}

private fun historyDateLabel(date: String): String =
    runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
        SimpleDateFormat("MM月dd日", Locale.CHINA).format(parsed!!)
    }.getOrDefault(date)

private fun historyWeekdayLabel(date: String): String =
    runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
        SimpleDateFormat("EEEE", Locale.CHINA).format(parsed!!)
    }.getOrDefault("")

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
    showDominantLeg: Boolean = true,
    controlHeight: Dp = 54.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = athletes.firstOrNull { it.id == selectedId }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(controlHeight)
                .background(AppSurface, RoundedCornerShape(8.dp))
                .border(1.dp, Border, RoundedCornerShape(8.dp))
                .clickable(enabled = athletes.isNotEmpty()) { expanded = true }
                .padding(horizontal = if (controlHeight <= 40.dp) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(if (controlHeight <= 40.dp) 18.dp else 24.dp),
            )
            Spacer(Modifier.width(if (controlHeight <= 40.dp) 7.dp else 10.dp))
            Text(
                text = selected?.name ?: "暂无运动员",
                color = if (selected == null) Muted else AppText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            selected?.dominantLeg?.takeIf { showDominantLeg }?.let {
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
private fun CompactSideTag(side: FootSide, present: Boolean) {
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
        ),
    ) {
        Text(
            text = side.label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
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
    compact: Boolean = false,
    rowHeight: Dp = if (compact) 58.dp else 64.dp,
    valueWidth: Dp = if (compact) 78.dp else 112.dp,
    symmetryWidth: Dp = if (compact) 150.dp else 252.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
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
                .padding(
                    start = if (compact) 6.dp else 16.dp,
                    end = if (compact) 6.dp else 16.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 16.dp else 20.dp)
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
                            .size(if (compact) 8.dp else 10.dp)
                            .background(item.color, RoundedCornerShape(999.dp)),
                    )
                }
            }
            Spacer(Modifier.width(if (compact) 7.dp else 12.dp))
            Text(
                modifier = Modifier.weight(1f),
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
                    append(" ")
                    withStyle(SpanStyle(color = Muted)) {
                        append(item.unit)
                    }
                },
                style = if (compact) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value?.let(item::format) ?: "—",
                color = if (selected) item.color else AppText,
                style = if (compact) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(valueWidth),
            )
            Spacer(Modifier.width(if (compact) 6.dp else 12.dp))
            MetricSymmetry(
                leftValue = leftValue,
                rightValue = rightValue,
                color = item.color,
                formatter = item::format,
                selected = selected,
                compact = compact,
                modifier = Modifier.width(symmetryWidth),
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
    compact: Boolean = false,
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

    if (compact) {
        CompactMetricSymmetry(
            leftValue = leftValue,
            rightValue = rightValue,
            color = color,
            formatter = formatter,
            selected = selected,
            signedDifference = signedDifference,
            hasBothSides = hasBothSides,
            modifier = modifier,
        )
        return
    }

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
            modifier = Modifier.width(if (compact) 30.dp else 54.dp),
        )
        Text(
            text = "L",
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(if (compact) 12.dp else 20.dp),
        )
        BoxWithConstraints(
            modifier = Modifier
                .width(if (compact) 48.dp else 84.dp)
                .height(if (compact) 24.dp else 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 4.dp),
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
                            .width(
                                when {
                                    isCenter -> 3.dp
                                    compact -> 4.dp
                                    else -> 7.dp
                                },
                            )
                            .height(
                                when (index) {
                                    0, 6 -> if (compact) 7.dp else 9.dp
                                    1, 5 -> if (compact) 10.dp else 13.dp
                                    2, 4 -> if (compact) 13.dp else 17.dp
                                    else -> if (compact) 16.dp else 21.dp
                                },
                            )
                            .background(
                                if (active) color else Border.copy(alpha = 0.72f),
                                RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
        }
        Text(
            text = "R",
            color = Muted,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(if (compact) 12.dp else 20.dp),
        )
        Text(
            text = rightValue?.let(formatter) ?: "—",
            color = if (selected) AppText else Muted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(if (compact) 30.dp else 54.dp),
        )
    }
}

@Composable
private fun CompactMetricSymmetry(
    leftValue: Double?,
    rightValue: Double?,
    color: Color,
    formatter: (Double) -> String,
    selected: Boolean,
    signedDifference: Double,
    hasBothSides: Boolean,
    modifier: Modifier = Modifier,
) {
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
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(28.dp),
        )
        Spacer(Modifier.width(3.dp))
        Row(
            modifier = Modifier
                .width(40.dp)
                .height(16.dp),
            horizontalArrangement = Arrangement.Center,
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
                        .padding(horizontal = 1.dp)
                        .width(if (isCenter) 3.dp else 4.dp)
                        .height(
                            when (index) {
                                0, 6 -> 7.dp
                                1, 5 -> 10.dp
                                2, 4 -> 13.dp
                                else -> 16.dp
                            },
                        )
                        .background(
                            if (active) color else Border.copy(alpha = 0.72f),
                            RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.width(3.dp))
        Text(
            text = rightValue?.let(formatter) ?: "—",
            color = if (selected) AppText else Muted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.width(28.dp),
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
        .filter {
            it.side == side && it.bilaterallyPaired
        }
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
