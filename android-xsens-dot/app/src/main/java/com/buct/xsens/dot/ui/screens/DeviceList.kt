package com.buct.xsens.dot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buct.xsens.dot.data.ScannedDevice
import com.buct.xsens.dot.ui.theme.Bg
import com.buct.xsens.dot.ui.theme.Border
import com.buct.xsens.dot.ui.theme.Muted

@Composable
fun DeviceList(
    devices: List<ScannedDevice>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .background(Bg, RoundedCornerShape(8.dp))
    ) {
        if (devices.isEmpty()) {
            Text(
                text = "未发现设备，请确保传感器已开机",
                color = Muted,
                modifier = Modifier.padding(12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(devices) { index, device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onToggle(index) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected.contains(index),
                            onCheckedChange = { if (enabled) onToggle(index) },
                            enabled = enabled
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${device.displayName} — ${device.address}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
