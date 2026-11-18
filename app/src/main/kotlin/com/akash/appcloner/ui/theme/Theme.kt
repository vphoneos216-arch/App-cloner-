package com.akash.appcloner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF4DA3FF)
private val Green = Color(0xFF6FD08C)
private val Amber = Color(0xFFE8B04B)
private val Red = Color(0xFFE05A5A)

private val DarkScheme = darkColorScheme(
    primary = Blue,
    secondary = Green,
    tertiary = Amber,
    error = Red,
    background = Color(0xFF0F1720),
    surface = Color(0xFF16202B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1C6FD0),
    secondary = Color(0xFF2E8B57),
    tertiary = Color(0xFFB8860B),
    error = Color(0xFFB3261E),
)

/** Severity colours used by both the findings list and the metric cards. */
object StatusColors {
    val ok: Color get() = Green
    val warn: Color get() = Amber
    val bad: Color get() = Red
    val unknown: Color get() = Color(0xFF8A99A8)
}

@Composable
fun AppClonerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
