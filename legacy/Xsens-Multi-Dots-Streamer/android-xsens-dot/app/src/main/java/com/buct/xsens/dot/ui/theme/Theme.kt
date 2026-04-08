package com.buct.xsens.dot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 复刻网页版 CSS 变量
val Bg = Color(0xFF0D0D0F)
val Surface = Color(0xFF16161A)
val Card = Color(0xFF1C1C21)
val Border = Color(0xFF2A2A32)
val Text = Color(0xFFE8E8ED)
val Muted = Color(0xFF71717A)
val Green = Color(0xFF22C55E)
val Orange = Color(0xFFF97316)
val Accent = Color(0xFF3B82F6)
val Red = Color(0xFFEF4444)
val ErrorRed = Color(0xFFF87171)

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Color.White,
    error = ErrorRed,
    onError = Color.White,
    background = Bg,
    onBackground = Text,
    surface = Surface,
    onSurface = Text,
    outline = Border
)

@Composable
fun XsensDotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
