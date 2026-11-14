@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.notifications.DoseNotificationStage
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.groupListItemColors

/** Item shown in Settings → Diagnostics for sending test notifications. */
data class DiagnosticsTarget(val label: String)

@Composable
fun SettingsDiagnosticsScreen(
  settings: AppSettings,
  diagnosticsTarget: DiagnosticsTarget?,
  onTestNotification: (DoseNotificationStage) -> Unit,
  onBack: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_diagnostics)) },
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
      item(key = "diagnostics-section") {
        SectionHeader(stringResource(R.string.settings_diagnostics))
        val enabled = settings.notificationsEnabled
        val supporting = when {
          !settings.notificationsEnabled -> stringResource(R.string.settings_test_turn_on)
          diagnosticsTarget != null -> stringResource(R.string.settings_test_uses, diagnosticsTarget.label)
          else -> stringResource(R.string.settings_test_generic)
        }
        GroupedColumn {
          GroupItem(0, 2, onClick = if (enabled) ({ onTestNotification(DoseNotificationStage.WINDOW_START_PERSISTENT) }) else null) {
            ListItem(
              supportingContent = { Text(supporting) },
              colors = groupListItemColors(),
            ) { Text(stringResource(R.string.settings_test_reminder)) }
          }
          GroupItem(1, 2, onClick = if (enabled && settings.alarmsEnabled) ({ onTestNotification(DoseNotificationStage.WINDOW_END_ALARM) }) else null) {
            ListItem(
              supportingContent = { Text(if (!settings.alarmsEnabled) stringResource(R.string.settings_alarm_is_off) else supporting) },
              colors = groupListItemColors(),
            ) { Text(stringResource(R.string.settings_test_alarm)) }
          }
        }
      }
    }
  }
}
