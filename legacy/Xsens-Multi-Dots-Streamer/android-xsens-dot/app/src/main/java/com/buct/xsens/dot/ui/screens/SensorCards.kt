package com.buct.xsens.dot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buct.xsens.dot.data.SensorData
import com.buct.xsens.dot.ui.theme.Bg
import com.buct.xsens.dot.ui.theme.Border
import com.buct.xsens.dot.ui.theme.Orange

@Composable
fun SensorCardsView(
    deviceIds: List<String>,
    sensorData: Map<String, SensorData>
) {
    if (deviceIds.isEmpty()) {
        Text(
            text = "未连接设备",
            color = com.buct.xsens.dot.ui.theme.Muted,
            modifier = Modifier.padding(8.dp)
        )
    } else {
        // 必须使用固定高度，否则在 Column(verticalScroll) 内会因无限高度约束崩溃
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            modifier = Modifier.height(400.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(deviceIds, key = { it }) { deviceId ->
                val key = deviceId.replace(":", "").replace("-", "").uppercase()
                val data = sensorData[key]
                SensorCard(
                    deviceId = key,
                    data = data
                )
            }
        }
    }
}

@Composable
fun SensorCard(
    deviceId: String,
    data: SensorData?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = deviceId,
            color = Orange,
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        data?.let { d ->
            // 按当前 Payload 模式自动显示（哪个字段非 null 就显示哪个）：
            // Mode 1: Euler + FreeAcc + Gyro（默认）
            // Mode 2: Euler + FreeAcc + Mag
            // Mode 3: Quat（已删除显示）+ Gyro → 仅 Gyro 可见
            // Mode 4: 完整数据
            // Mode 5: Quat + RawAcc + Gyro → Acc + Gyro 可见
            d.acc?.let { Text("加速度: [${it[0].format()}, ${it[1].format()}, ${it[2].format()}]", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            d.gyro?.let { Text("角速度: [${it[0].format()}, ${it[1].format()}, ${it[2].format()}]", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            d.euler?.let { Text("欧拉角: [${it[0].format()}, ${it[1].format()}, ${it[2].format()}]", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            d.mag?.let { Text("磁场: [${it[0].format()}, ${it[1].format()}, ${it[2].format()}]", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
        } ?: Text("—", color = com.buct.xsens.dot.ui.theme.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
}

private fun Float.format() = "%.3f".format(this)
