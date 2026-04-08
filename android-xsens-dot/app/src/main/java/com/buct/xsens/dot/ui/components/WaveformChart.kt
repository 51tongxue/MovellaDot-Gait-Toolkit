package com.buct.xsens.dot.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.buct.xsens.dot.data.WaveSnapshot
import com.buct.xsens.dot.ui.theme.Bg

private val AccXColor = Color(0xFFef4444)
private val AccYColor = Color(0xFF22c55e)
private val AccZColor = Color(0xFF3b82f6)

@Composable
fun WaveformChart(
    title: String,
    snapshot: WaveSnapshot?,
    dataKeys: List<Pair<String, (WaveSnapshot) -> List<Float>>>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Bg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        val colors = listOf(AccXColor, AccYColor, AccZColor)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val w = size.width
            val h = size.height
            if (snapshot == null || snapshot.t.isEmpty()) return@Canvas
            try {
                val t = snapshot.t
                val tLen = t.size
                if (tLen < 1) return@Canvas
                val t0 = t[0] ?: return@Canvas
                val tLast = t[tLen - 1] ?: return@Canvas
                val tRange = (tLast - t0).coerceAtLeast(0.001)
                val allValues = dataKeys.flatMap { it.second(snapshot) }.filterNotNull()
                if (allValues.isEmpty()) return@Canvas
                val vMin = allValues.min()
                val vMax = allValues.max()
                val vRange = (vMax - vMin).coerceAtLeast(0.1f)
                val pad = vRange * 0.1f
                val vMinP = vMin - pad
                val vMaxP = vMax + pad
                val vRangeP = (vMaxP - vMinP).coerceAtLeast(0.1f)
                dataKeys.forEachIndexed { idx, (_, getter) ->
                    val values = getter(snapshot).filterNotNull()
                    val color = colors.getOrElse(idx) { Color.Gray }
                    val len = minOf(tLen, values.size)
                    if (len == 1) {
                        val x = ((t[0]!! - t0) / tRange * (w - 4) + 2).toFloat()
                        val y = h - 2 - (values[0] - vMinP) / vRangeP * (h - 4)
                        drawCircle(color = color, radius = 3f, center = Offset(x, y))
                        return@forEachIndexed
                    }
                    if (len < 2) return@forEachIndexed
                    for (i in 0 until len - 1) {
                        val ti  = t.getOrNull(i)  ?: break
                        val ti1 = t.getOrNull(i + 1) ?: break
                        val vi  = values.getOrNull(i)  ?: break
                        val vi1 = values.getOrNull(i + 1) ?: break
                        val x1 = ((ti  - t0) / tRange * (w - 4) + 2).toFloat()
                        val y1 = h - 2 - (vi  - vMinP) / vRangeP * (h - 4)
                        val x2 = ((ti1 - t0) / tRange * (w - 4) + 2).toFloat()
                        val y2 = h - 2 - (vi1 - vMinP) / vRangeP * (h - 4)
                        drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 2f)
                    }
                }
            } catch (_: Exception) {
                // 防止并发修改时偶发 NPE 导致崩溃，下一帧重新渲染
            }
        }
    }
}

@Composable
fun AccWaveformChart(snapshot: WaveSnapshot?, modifier: Modifier = Modifier) {
    WaveformChart(
        title = "加速度 (m/s²)",
        snapshot = snapshot,
        dataKeys = listOf(
            "Acc X" to { it.accX },
            "Acc Y" to { it.accY },
            "Acc Z" to { it.accZ },
        ),
        modifier = modifier
    )
}

@Composable
fun GyroWaveformChart(snapshot: WaveSnapshot?, modifier: Modifier = Modifier) {
    WaveformChart(
        title = "角速度 (rad/s)",
        snapshot = snapshot,
        dataKeys = listOf(
            "Gyro X" to { it.gyroX },
            "Gyro Y" to { it.gyroY },
            "Gyro Z" to { it.gyroZ },
        ),
        modifier = modifier
    )
}
