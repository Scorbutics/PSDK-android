package com.psdk.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The app's palette (PSDK-android's colors.xml). */
private val COLORS = darkColorScheme(
    primary = Color(0xFF03DAC5),
    onPrimary = Color.Black,
    secondary = Color(0xFF0F70B0),
    background = Color(0xFF1E1E2E),
    surface = Color(0xFF2A2A3E),
    surfaceVariant = Color(0xFF3A3A4E),
    surfaceContainerHigh = Color(0xFF323248),
    onSurfaceVariant = Color(0xFF8B8B9E),
    error = Color(0xFFCF6679),
)

/** The module's screens look like the rest of the app. */
@Composable
fun PsdkTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = COLORS, content = content)
