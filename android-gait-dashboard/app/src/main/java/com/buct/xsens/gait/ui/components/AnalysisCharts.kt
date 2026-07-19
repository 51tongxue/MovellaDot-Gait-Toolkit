package com.buct.xsens.gait.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buct.xsens.dot.ui.theme.Border
import com.buct.xsens.dot.ui.theme.Green
import com.buct.xsens.dot.ui.theme.Muted
import com.buct.xsens.dot.ui.theme.Surface
import com.buct.xsens.dot.ui.theme.Text as AppText
import com.buct.xsens.gait.analysis.GaitEvents
import com.buct.xsens.gait.analysis.SideSignalResult
import kotlin.math.abs
import kotlin.math.max
import java.util.Locale

data class AnalysisChartPoint(
    val x: Double,
    val y: Double,
    val stepNumber: Int,
    val sideLabel: String?,
)

data class AnalysisChartThreshold(
    val value: Double,
    val label: String,
    val summary: String,
    val color: Color,
)

@Composable
fun AnalysisLineChart(
    leftPoints: List<AnalysisChartPoint>,
    rightPoints: List<AnalysisChartPoint>,
    singlePoints: List<AnalysisChartPoint>,
    lineColor: Color,
    xLabel: String,
    yLabel: String,
    valueLabel: String,
    valueUnit: String,
    valueDecimals: Int,
    threshold: AnalysisChartThreshold? = null,
    chartHeight: Dp = 220.dp,
    headerLabel: String? = null,
    selectionResetKey: Int = 0,
    modifier: Modifier = Modifier,
) {
    val hasBothSides = leftPoints.isNotEmpty() && rightPoints.isNotEmpty()
    val series = when {
        hasBothSides -> listOf(
            Triple(leftPoints, lineColor.copy(alpha = 0.5f), 0f),
            Triple(rightPoints, lineColor, 0.14f),
        )
        leftPoints.isNotEmpty() -> listOf(Triple(leftPoints, lineColor, 0.14f))
        rightPoints.isNotEmpty() -> listOf(Triple(rightPoints, lineColor, 0.14f))
        else -> listOf(Triple(singlePoints, lineColor, 0.14f))
    }.filter { it.first.isNotEmpty() }
    val allPoints = series.flatMap { it.first }
    val minX = allPoints.minOfOrNull { it.x } ?: 0.0
    val maxX = allPoints.maxOfOrNull { it.x } ?: 1.0
    val yValues = allPoints.map { it.y } + listOfNotNull(threshold?.value)
    val minY = yValues.minOrNull() ?: 0.0
    val maxY = yValues.maxOrNull() ?: 1.0
    val xRange = (maxX - minX).takeIf { it > 0.0 } ?: 1.0
    val yPadding = ((maxY - minY) * 0.08).takeIf { it > 0.0 } ?: 0.5
    val chartMinY = minY - yPadding
    val chartMaxY = maxY + yPadding
    val yRange = chartMaxY - chartMinY
    var selectedPoint by remember(allPoints, selectionResetKey) {
        mutableStateOf<AnalysisChartPoint?>(null)
    }

    Column(modifier = modifier) {
        if (headerLabel != null || hasBothSides) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (headerLabel != null) {
                    Text(
                        text = headerLabel,
                        color = AppText,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hasBothSides) {
                    ChartLegendItem("左脚", lineColor.copy(alpha = 0.5f), fillAlpha = 0f)
                    ChartLegendItem("右脚", lineColor, fillAlpha = 0.14f)
                }
            }
        }
        threshold?.let {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChartThresholdLegend(it)
            }
        }
        ChartFrame(
            modifier = Modifier,
            height = chartHeight,
            canvasModifier = Modifier.pointerInput(
                allPoints,
                minX,
                xRange,
                chartMinY,
                yRange,
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    selectedPoint = findNearestChartPoint(
                        touch = down.position,
                        canvasWidth = size.width.toFloat(),
                        canvasHeight = size.height.toFloat(),
                        points = allPoints,
                        minX = minX,
                        xRange = xRange,
                        minY = chartMinY,
                        yRange = yRange,
                    )
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            selectedPoint = findNearestChartPoint(
                                touch = change.position,
                                canvasWidth = size.width.toFloat(),
                                canvasHeight = size.height.toFloat(),
                                points = allPoints,
                                minX = minX,
                                xRange = xRange,
                                minY = chartMinY,
                                yRange = yRange,
                            )
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        ) {
            drawAxes(xLabel, yLabel)
            threshold?.let {
                drawChartThreshold(
                    threshold = it,
                    minY = chartMinY,
                    yRange = yRange,
                )
            }
            if (allPoints.size >= 2) {
                series.forEach { (points, color, fillAlpha) ->
                    drawMetricSeries(
                        points = points,
                        color = color,
                        fillAlpha = fillAlpha,
                        minX = minX,
                        xRange = xRange,
                        minY = chartMinY,
                        yRange = yRange,
                    )
                }
            }
            drawRangeLabels(minX, maxX, chartMinY, chartMaxY)
            selectedPoint?.let { point ->
                drawChartSelection(
                    point = point,
                    seriesColor = lineColor,
                    valueLabel = valueLabel,
                    valueUnit = valueUnit,
                    valueDecimals = valueDecimals,
                    minX = minX,
                    xRange = xRange,
                    minY = chartMinY,
                    yRange = yRange,
                )
            }
        }
    }
}

@Composable
private fun ChartThresholdLegend(threshold: AnalysisChartThreshold) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 28.dp, height = 12.dp)) {
            drawLine(
                color = threshold.color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)),
            )
        }
        Text(
            text = threshold.summary,
            color = threshold.color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ChartLegendItem(
    label: String,
    color: Color,
    fillAlpha: Float,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 28.dp, height = 12.dp)) {
            if (fillAlpha > 0f) {
                drawRect(
                    color = color.copy(alpha = fillAlpha),
                    topLeft = Offset(0f, size.height / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height / 2f),
                )
            }
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 3f,
            )
        }
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMetricSeries(
    points: List<AnalysisChartPoint>,
    color: Color,
    fillAlpha: Float,
    minX: Double,
    xRange: Double,
    minY: Double,
    yRange: Double,
) {
    if (points.size < 2) return
    val coordinates = points.map { point ->
        Offset(
            x = plotLeft + ((point.x - minX) / xRange).toFloat() * plotWidth,
            y = plotBottom - ((point.y - minY) / yRange).toFloat() * plotHeight,
        )
    }
    if (fillAlpha > 0f) {
        val fillPath = Path().apply {
            moveTo(coordinates.first().x, plotBottom)
            coordinates.forEach { lineTo(it.x, it.y) }
            lineTo(coordinates.last().x, plotBottom)
            close()
        }
        drawPath(fillPath, color = color.copy(alpha = fillAlpha))
    }
    val linePath = Path().apply {
        coordinates.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
    }
    drawPath(linePath, color = color, style = Stroke(width = 3f))
    coordinates.forEach { point ->
        drawCircle(color = color, radius = 3.4f, center = point)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChartThreshold(
    threshold: AnalysisChartThreshold,
    minY: Double,
    yRange: Double,
) {
    val y = plotBottom -
        ((threshold.value - minY) / yRange).toFloat() * plotHeight
    drawLine(
        color = threshold.color,
        start = Offset(plotLeft, y),
        end = Offset(plotLeft + plotWidth, y),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
        ),
    )
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(
            (threshold.color.alpha * 255).toInt(),
            (threshold.color.red * 255).toInt(),
            (threshold.color.green * 255).toInt(),
            (threshold.color.blue * 255).toInt(),
        )
        textSize = 10.sp.toPx()
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
    }
    drawContext.canvas.nativeCanvas.drawText(
        threshold.label,
        plotLeft + plotWidth - 4.dp.toPx(),
        y - 5.dp.toPx(),
        labelPaint,
    )
}

@Composable
fun SignalEventChart(
    primary: SideSignalResult?,
    secondary: SideSignalResult?,
    rangeStartS: Double? = null,
    rangeEndS: Double? = null,
    modifier: Modifier = Modifier,
) {
    ChartFrame(modifier = modifier.height(250.dp)) {
        drawAxes("时间 (s)", "角速度 (°/s)")
        val rangeStartMs = rangeStartS?.times(1000.0)
        val rangeEndMs = rangeEndS?.times(1000.0)
        val series = listOfNotNull(primary, secondary).mapNotNull { side ->
            side.signal?.let { signal ->
                val count = minOf(
                    signal.timestampsMs.size,
                    signal.gyroY.size,
                )
                val indices = (0 until count).filter { index ->
                    val timestamp = signal.timestampsMs[index]
                    (rangeStartMs == null || timestamp >= rangeStartMs) &&
                        (rangeEndMs == null || timestamp <= rangeEndMs)
                }
                if (indices.size < 2) {
                    null
                } else {
                    Triple(
                        side,
                        indices.map(signal.timestampsMs::get),
                        indices.map(signal.gyroY::get),
                    )
                }
            }
        }
        if (series.isEmpty()) return@ChartFrame
        val minX = series.minOf { it.second.minOrNull() ?: 0.0 }
        val maxX = series.maxOf { it.second.maxOrNull() ?: 1.0 }
        val allY = series.flatMap { it.third }
        val minY = allY.minOrNull() ?: -1.0
        val maxY = allY.maxOrNull() ?: 1.0
        val xRange = (maxX - minX).takeIf { it > 0.0 } ?: 1.0
        val yRange = (maxY - minY).takeIf { it > 0.0 } ?: 1.0

        series.forEachIndexed { index, item ->
            val timestamps = item.second
            val values = item.third
            val count = minOf(timestamps.size, values.size)
            if (count < 2) return@forEachIndexed
            val color = if (index == 0) Color.White else Green.copy(alpha = 0.7f)
            val path = Path()
            for (i in 0 until count) {
                val x = plotLeft + ((timestamps[i] - minX) / xRange).toFloat() * plotWidth
                val y = plotBottom - ((values[i] - minY) / yRange).toFloat() * plotHeight
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 2.2f))
            drawEventMarkers(item.first.events, timestamps, values, minX, xRange, minY, yRange)
        }
        drawRangeLabels(minX / 1000.0, maxX / 1000.0, minY, maxY)
    }
}

@Composable
private fun ChartFrame(
    modifier: Modifier,
    height: Dp = 220.dp,
    canvasModifier: Modifier = Modifier,
    content: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Surface, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .then(canvasModifier),
            onDraw = content,
        )
    }
}

private fun androidx.compose.ui.unit.Density.findNearestChartPoint(
    touch: Offset,
    canvasWidth: Float,
    canvasHeight: Float,
    points: List<AnalysisChartPoint>,
    minX: Double,
    xRange: Double,
    minY: Double,
    yRange: Double,
): AnalysisChartPoint? {
    if (points.isEmpty()) return null
    val left = 66.dp.toPx()
    val top = 14.dp.toPx()
    val bottom = canvasHeight - 38.dp.toPx()
    val width = max(1f, canvasWidth - left - 10.dp.toPx())
    val height = max(1f, bottom - top)
    var nearestPoint: AnalysisChartPoint? = null
    var nearestDistanceSquared = Float.MAX_VALUE
    points.forEach { point ->
        val pointX = left + ((point.x - minX) / xRange).toFloat() * width
        val pointY = bottom - ((point.y - minY) / yRange).toFloat() * height
        val dx = pointX - touch.x
        val dy = pointY - touch.y
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared < nearestDistanceSquared) {
            nearestDistanceSquared = distanceSquared
            nearestPoint = point
        }
    }
    return nearestPoint.takeIf { nearestDistanceSquared <= 36.dp.toPx() * 36.dp.toPx() }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChartSelection(
    point: AnalysisChartPoint,
    seriesColor: Color,
    valueLabel: String,
    valueUnit: String,
    valueDecimals: Int,
    minX: Double,
    xRange: Double,
    minY: Double,
    yRange: Double,
) {
    val pointX = plotLeft + ((point.x - minX) / xRange).toFloat() * plotWidth
    val pointY = plotBottom - ((point.y - minY) / yRange).toFloat() * plotHeight
    drawLine(
        color = Color.White.copy(alpha = 0.42f),
        start = Offset(pointX, plotTop),
        end = Offset(pointX, plotBottom),
        strokeWidth = 1.dp.toPx(),
    )
    drawCircle(color = Surface, radius = 6.dp.toPx(), center = Offset(pointX, pointY))
    drawCircle(color = seriesColor, radius = 3.5.dp.toPx(), center = Offset(pointX, pointY))

    val tooltipWidth = minOf(164.dp.toPx(), plotWidth - 12.dp.toPx())
    val tooltipHeight = 68.dp.toPx()
    val tooltipGap = 8.dp.toPx()
    val plotRight = plotLeft + plotWidth
    val tooltipLeft = if (pointX + tooltipGap + tooltipWidth <= plotRight) {
        pointX + tooltipGap
    } else {
        pointX - tooltipGap - tooltipWidth
    }.coerceIn(plotLeft + 4.dp.toPx(), plotRight - tooltipWidth - 4.dp.toPx())
    val tooltipTop = if (pointY - tooltipHeight - tooltipGap >= plotTop) {
        pointY - tooltipHeight - tooltipGap
    } else {
        pointY + tooltipGap
    }.coerceIn(plotTop + 4.dp.toPx(), plotBottom - tooltipHeight - 4.dp.toPx())

    drawRoundRect(
        color = Color(0xF21A1A1E),
        topLeft = Offset(tooltipLeft, tooltipTop),
        size = Size(tooltipWidth, tooltipHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
    )
    drawRoundRect(
        color = seriesColor.copy(alpha = 0.75f),
        topLeft = Offset(tooltipLeft, tooltipTop),
        size = Size(tooltipWidth, tooltipHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = 11.sp.toPx()
        typeface = Typeface.DEFAULT_BOLD
    }
    val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.rgb(212, 212, 216)
        textSize = 10.sp.toPx()
    }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.argb(
            (seriesColor.alpha * 255).toInt(),
            (seriesColor.red * 255).toInt(),
            (seriesColor.green * 255).toInt(),
            (seriesColor.blue * 255).toInt(),
        )
        textSize = 10.sp.toPx()
        typeface = Typeface.DEFAULT_BOLD
    }
    val textLeft = tooltipLeft + 10.dp.toPx()
    val firstBaseline = tooltipTop + 18.dp.toPx()
    val lineGap = 19.dp.toPx()
    drawContext.canvas.nativeCanvas.apply {
        drawText(
            "第 ${point.stepNumber.toString().padStart(2, '0')} 步 · ${point.sideLabel ?: "步态"}",
            textLeft,
            firstBaseline,
            titlePaint,
        )
        drawText(
            "时间 ${formatChartNumber(point.x)} s",
            textLeft,
            firstBaseline + lineGap,
            detailPaint,
        )
        drawText(
            "$valueLabel ${formatChartMetric(point.y, valueDecimals)} $valueUnit",
            textLeft,
            firstBaseline + lineGap * 2f,
            valuePaint,
        )
    }
}

private fun formatChartNumber(value: Double): String =
    String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

private fun formatChartMetric(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private val androidx.compose.ui.graphics.drawscope.DrawScope.plotLeft: Float
    get() = 66.dp.toPx()
private val androidx.compose.ui.graphics.drawscope.DrawScope.plotTop: Float
    get() = 14.dp.toPx()
private val androidx.compose.ui.graphics.drawscope.DrawScope.plotBottom: Float
    get() = size.height - 38.dp.toPx()
private val androidx.compose.ui.graphics.drawscope.DrawScope.plotWidth: Float
    get() = max(1f, size.width - plotLeft - 10.dp.toPx())
private val androidx.compose.ui.graphics.drawscope.DrawScope.plotHeight: Float
    get() = max(1f, plotBottom - plotTop)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxes(
    xLabel: String,
    yLabel: String,
) {
    val grid = Border.copy(alpha = 0.65f)
    repeat(5) { index ->
        val y = plotTop + plotHeight * index / 4f
        drawLine(grid, Offset(plotLeft, y), Offset(plotLeft + plotWidth, y), 1f)
    }
    drawLine(Muted, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 1.5f)
    drawLine(Muted, Offset(plotLeft, plotBottom), Offset(plotLeft + plotWidth, plotBottom), 1.5f)
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(113, 113, 122)
        textSize = 11.sp.toPx()
        textAlign = Paint.Align.CENTER
    }
    val verticalCenter = plotTop + plotHeight / 2f
    val verticalTitleBaseline =
        verticalCenter - (titlePaint.ascent() + titlePaint.descent()) / 2f
    drawContext.canvas.nativeCanvas.apply {
        drawText(
            xLabel,
            plotLeft + plotWidth / 2f,
            size.height - 3.dp.toPx(),
            titlePaint,
        )
        save()
        rotate(-90f, 12.dp.toPx(), verticalCenter)
        drawText(yLabel, 12.dp.toPx(), verticalTitleBaseline, titlePaint)
        restore()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRangeLabels(
    minX: Double,
    maxX: Double,
    minY: Double,
    maxY: Double,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(113, 113, 122)
        textSize = 10.sp.toPx()
    }
    val xTickBaseline = plotBottom + 14.dp.toPx()
    val topTickBaseline = plotTop - paint.ascent()
    val bottomTickBaseline = plotBottom - paint.descent()
    drawContext.canvas.nativeCanvas.apply {
        paint.textAlign = Paint.Align.LEFT
        drawText("%.1f".format(minX), plotLeft, xTickBaseline, paint)
        paint.textAlign = Paint.Align.RIGHT
        drawText("%.1f".format(maxX), plotLeft + plotWidth, xTickBaseline, paint)

        val yTickX = plotLeft - 8.dp.toPx()
        paint.textAlign = Paint.Align.RIGHT
        drawText("%.1f".format(maxY), yTickX, topTickBaseline, paint)
        drawText("%.1f".format(minY), yTickX, bottomTickBaseline, paint)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEventMarkers(
    events: GaitEvents,
    timestamps: List<Double>,
    values: List<Double>,
    minX: Double,
    xRange: Double,
    minY: Double,
    yRange: Double,
) {
    val markerGroups = listOf(
        events.ic to Color(0xFFEF4444),
        events.tc to Green,
        events.ms to Color(0xFFF59E0B),
        events.msw to Color(0xFF8B5CF6),
    )
    markerGroups.forEach { (eventTimes, color) ->
        eventTimes.forEach eventLoop@{ eventTime ->
            if (eventTime < minX || eventTime > minX + xRange) {
                return@eventLoop
            }
            val index = timestamps.binarySearchNearest(eventTime)
            if (index !in values.indices) return@eventLoop
            val x = plotLeft + ((eventTime - minX) / xRange).toFloat() * plotWidth
            val y = plotBottom - ((values[index] - minY) / yRange).toFloat() * plotHeight
            drawCircle(color = color, radius = 5f, center = Offset(x, y))
        }
    }
}

private fun List<Double>.binarySearchNearest(target: Double): Int {
    if (isEmpty()) return -1
    var low = 0
    var high = lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        when {
            this[mid] < target -> low = mid + 1
            this[mid] > target -> high = mid - 1
            else -> return mid
        }
    }
    val lower = high.coerceIn(indices)
    val upper = low.coerceIn(indices)
    return if (abs(this[lower] - target) <= abs(this[upper] - target)) lower else upper
}
