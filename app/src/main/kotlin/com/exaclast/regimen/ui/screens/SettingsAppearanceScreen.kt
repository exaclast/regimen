@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.ThemeMode
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.SwitchRow

@Composable
fun SettingsAppearanceScreen(
  settings: AppSettings,
  onSaveSettings: (AppSettings) -> Unit,
  onBack: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_appearance)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
          }
        },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
      item(key = "appearance-section") {
        SectionHeader(stringResource(R.string.settings_appearance))
        GroupedColumn {
          GroupItem(0, 2) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
              Spacer(Modifier.height(8.dp))
              val modes = ThemeMode.entries
              SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, mode ->
                  SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { onSaveSettings(settings.copy(themeMode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    icon = {},
                    label = {
                      Text(
                        when (mode) {
                          ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                          ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                          ThemeMode.DARK -> stringResource(R.string.theme_dark)
                        },
                        maxLines = 1,
                      )
                    },
                  )
                }
              }
            }
          }
          GroupItem(1, 2) {
            SwitchRow(
              title = stringResource(R.string.settings_wallpaper_colors),
              subtitle = stringResource(R.string.settings_wallpaper_colors_help),
              checked = settings.dynamicColor,
              onCheckedChange = { onSaveSettings(settings.copy(dynamicColor = it)) },
            )
          }
        }
      }
    }
  }
}
