package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color


private val DarkColorScheme =
  darkColorScheme(
    primary = PolishPrimaryDark,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = PolishBackgroundDark,
    surface = PolishSurfaceDark,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = PolishTextDark,
    onSurface = PolishTextDark,
    onSurfaceVariant = PolishOnSurfaceVariantDark,
    secondaryContainer = PolishSecondaryContainerDark,
    error = PolishAlarmTextDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = PolishPrimary,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = PolishBackground,
    surface = PolishSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = PolishText,
    onSurface = PolishText,
    onSurfaceVariant = PolishOnSurfaceVariant,
    secondaryContainer = PolishSecondaryContainer,
    error = PolishAlarmText,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disable dynamic color so our custom Professional Polish identity takes priority, but support system light/dark toggles
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colors = if (darkTheme) DarkPolishColors else LightPolishColors
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  CompositionLocalProvider(LocalPolishColors provides colors) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
