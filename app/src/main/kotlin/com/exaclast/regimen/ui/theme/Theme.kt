package com.exaclast.regimen.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
  primary = TealPrimaryLight,
  onPrimary = OnTealPrimaryLight,
  primaryContainer = TealContainerLight,
  onPrimaryContainer = OnTealContainerLight,
  secondary = SlateSecondaryLight,
  onSecondary = OnSlateSecondaryLight,
  secondaryContainer = SlateContainerLight,
  onSecondaryContainer = OnSlateContainerLight,
  tertiary = IndigoTertiaryLight,
  onTertiary = OnIndigoTertiaryLight,
  tertiaryContainer = IndigoContainerLight,
  onTertiaryContainer = OnIndigoContainerLight,
)

private val DarkColorScheme = darkColorScheme(
  primary = TealPrimaryDark,
  onPrimary = OnTealPrimaryDark,
  primaryContainer = TealContainerDark,
  onPrimaryContainer = OnTealContainerDark,
  secondary = SlateSecondaryDark,
  onSecondary = OnSlateSecondaryDark,
  secondaryContainer = SlateContainerDark,
  onSecondaryContainer = OnSlateContainerDark,
  tertiary = IndigoTertiaryDark,
  onTertiary = OnIndigoTertiaryDark,
  tertiaryContainer = IndigoContainerDark,
  onTertiaryContainer = OnIndigoContainerDark,
)

@Composable
fun RegimenTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    content = content,
  )
}

