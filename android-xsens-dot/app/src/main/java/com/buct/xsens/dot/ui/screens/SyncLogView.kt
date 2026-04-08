package com.buct.xsens.dot.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.buct.xsens.dot.ui.theme.Bg
import com.buct.xsens.dot.ui.theme.Muted

@Composable
fun SyncLogView(logs: List<String>) {
    // 固定高度避免在 verticalScroll 父级内 LazyColumn 收到无限约束
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(Bg, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs) { log ->
                Text(
                    text = log,
                    color = Muted,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
