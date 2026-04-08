package com.buct.xsens.dot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.buct.xsens.dot.ui.theme.*

@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Muted
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    type: BadgeType = BadgeType.Info
) {
    val color = when (type) {
        BadgeType.Ok -> Green
        BadgeType.Err -> ErrorRed
        BadgeType.Warn -> Orange
        BadgeType.Info -> Accent
    }
    Surface(
        modifier = modifier.border(1.dp, Border, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        color = Surface
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

enum class BadgeType { Ok, Err, Warn, Info }

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Accent),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(text)
    }
}

@Composable
fun SuccessButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Green),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(text)
    }
}

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Red),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(text)
    }
}

@Composable
fun NeutralButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Text),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(text)
    }
}
