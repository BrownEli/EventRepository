package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6750A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

// Professional Polish Theme Colors
val PolishBackground = Color(0xFFFEF7FF)
val PolishText = Color(0xFF1D1B20)
val PolishPrimary = Color(0xFF6750A4)
val PolishSecondaryContainer = Color(0xFFE8DEF8)
val PolishSurface = Color(0xFFFFFFFF)
val PolishBorder = Color(0xFFCAC4D0)
val PolishAlarmBg = Color(0xFFFFF1F1)
val PolishAlarmBorder = Color(0xFFF2B8B5)
val PolishAlarmText = Color(0xFFB3261E)
val PolishStickyBg = Color(0xFFFFD9E2)
val PolishStickyBorder = Color(0xFFFFB3C5)
val PolishStickyButton = Color(0xFF31111D)
val PolishOnSurfaceVariant = Color(0xFF49454F)

// Professional Polish Dark Theme Colors
val PolishBackgroundDark = Color(0xFF141218)
val PolishTextDark = Color(0xFFE6E1E5)
val PolishPrimaryDark = Color(0xFFD0BCFF)
val PolishSecondaryContainerDark = Color(0xFF4A4458)
val PolishSurfaceDark = Color(0xFF1D1B20)
val PolishBorderDark = Color(0xFF49454F)
val PolishAlarmBgDark = Color(0xFF2B1616)
val PolishAlarmBorderDark = Color(0xFF8C1D18)
val PolishAlarmTextDark = Color(0xFFF2B8B5)
val PolishStickyBgDark = Color(0xFF31111D)
val PolishStickyBorderDark = Color(0xFF85223D)
val PolishStickyButtonDark = Color(0xFFFFD9E2)
val PolishOnSurfaceVariantDark = Color(0xFFCAC4D0)

data class PolishColors(
    val background: Color,
    val text: Color,
    val primary: Color,
    val secondaryContainer: Color,
    val surface: Color,
    val border: Color,
    val alarmBg: Color,
    val alarmBorder: Color,
    val alarmText: Color,
    val stickyBg: Color,
    val stickyBorder: Color,
    val stickyButton: Color,
    val onSurfaceVariant: Color
)

val LightPolishColors = PolishColors(
    background = PolishBackground,
    text = PolishText,
    primary = PolishPrimary,
    secondaryContainer = PolishSecondaryContainer,
    surface = PolishSurface,
    border = PolishBorder,
    alarmBg = PolishAlarmBg,
    alarmBorder = PolishAlarmBorder,
    alarmText = PolishAlarmText,
    stickyBg = PolishStickyBg,
    stickyBorder = PolishStickyBorder,
    stickyButton = PolishStickyButton,
    onSurfaceVariant = PolishOnSurfaceVariant
)

val DarkPolishColors = PolishColors(
    background = PolishBackgroundDark,
    text = PolishTextDark,
    primary = PolishPrimaryDark,
    secondaryContainer = PolishSecondaryContainerDark,
    surface = PolishSurfaceDark,
    border = PolishBorderDark,
    alarmBg = PolishAlarmBgDark,
    alarmBorder = PolishAlarmBorderDark,
    alarmText = PolishAlarmTextDark,
    stickyBg = PolishStickyBgDark,
    stickyBorder = PolishStickyBorderDark,
    stickyButton = PolishStickyButtonDark,
    onSurfaceVariant = PolishOnSurfaceVariantDark
)

val LocalPolishColors = staticCompositionLocalOf { LightPolishColors }

