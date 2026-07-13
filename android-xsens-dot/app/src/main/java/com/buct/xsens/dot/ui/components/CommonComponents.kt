package com.buct.xsens.dot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
            style = MaterialTheme.typography.labelLarge,
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
        modifier = modifier.border(1.dp, Border, RoundedCornerShape(999.dp)),
        shape = RoundedCornerShape(999.dp),
        color = Surface
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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
    val controlShape = RoundedCornerShape(8.dp)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 42.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Color.White,
            disabledContainerColor = Accent.copy(alpha = 0.42f),
            disabledContentColor = Color.White.copy(alpha = 0.72f)
        ),
        shape = controlShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SuccessButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val controlShape = RoundedCornerShape(8.dp)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 42.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Green,
            contentColor = Color.White,
            disabledContainerColor = Green.copy(alpha = 0.42f),
            disabledContentColor = Color.White.copy(alpha = 0.72f)
        ),
        shape = controlShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val controlShape = RoundedCornerShape(8.dp)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 42.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Red,
            contentColor = Color.White,
            disabledContainerColor = Red.copy(alpha = 0.42f),
            disabledContentColor = Color.White.copy(alpha = 0.72f)
        ),
        shape = controlShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun NeutralButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val controlShape = RoundedCornerShape(8.dp)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 42.dp),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Surface,
            contentColor = Text,
            disabledContainerColor = Surface.copy(alpha = 0.42f),
            disabledContentColor = Text.copy(alpha = 0.55f)
        ),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = androidx.compose.ui.graphics.SolidColor(Border)
        ),
        shape = controlShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}
