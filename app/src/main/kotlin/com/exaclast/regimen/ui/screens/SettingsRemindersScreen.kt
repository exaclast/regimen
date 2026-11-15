@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.ui.components.AdaptiveSingleChoiceRow
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.CenteredContentBox
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.SwitchRow
import com.exaclast.regimen.ui.components.groupListItemColors

@Composable
fun SettingsRemindersScreen(
  settings: AppSettings,
  buckets: List<TimeOfDayBucket>,
  onSaveSettings: (AppSettings) -> Unit,
  onOpenWindow: (String?) -> Unit,
  onResetWindows: () -> Unit,
  onPermissionsChanged: () -> Unit,
  onBack: () -> Unit,
) {
  val resources = LocalResources.current
  var confirmResetWindows by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_category_reminders)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
          }
        },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    CenteredContentBox(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) { extra ->
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp + extra, end = 16.dp + extra, bottom = 32.dp),
      ) {
      item(key = "reminders-section") {
        SectionHeader(stringResource(R.string.settings_reminders))
        GroupedColumn {
          GroupItem(0, 5) {
            SwitchRow(
              title = stringResource(R.string.settings_dose_reminders),
              subtitle = stringResource(R.string.settings_dose_reminders_help),
              checked = settings.notificationsEnabled,
              onCheckedChange = { onSaveSettings(settings.copy(notificationsEnabled = it)) },
            )
          }
          GroupItem(1, 5) {
            SwitchRow(
              title = stringResource(R.string.settings_end_alarm),
              subtitle = stringResource(R.string.settings_end_alarm_help),
              checked = settings.alarmsEnabled,
              enabled = settings.notificationsEnabled,
              onCheckedChange = { onSaveSettings(settings.copy(alarmsEnabled = it)) },
            )
          }
          GroupItem(2, 5) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text(stringResource(R.string.settings_snooze_title), style = MaterialTheme.typography.bodyLarge)
              Text(
                stringResource(R.string.settings_snooze_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(8.dp))
              val presets = AppSettings.SNOOZE_PRESETS
              AdaptiveSingleChoiceRow(
                options = presets,
                selected = settings.snoozeMinutes,
                onSelect = { onSaveSettings(settings.copy(snoozeMinutes = it)) },
                label = { stringResource(R.string.settings_minutes_short, it) },
              )
            }
          }
          GroupItem(3, 5) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text(stringResource(R.string.settings_repeat_title), style = MaterialTheme.typography.bodyLarge)
              Text(
                stringResource(R.string.settings_repeat_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(8.dp))
              val repeatPresets = AppSettings.REPEAT_PRESETS
              AdaptiveSingleChoiceRow(
                options = repeatPresets,
                selected = settings.reminderRepeatMinutes,
                onSelect = { onSaveSettings(settings.copy(reminderRepeatMinutes = it)) },
                label = { if (it == 0) stringResource(R.string.value_off) else stringResource(R.string.settings_minutes_tiny, it) },
              )
            }
          }
          GroupItem(4, 5) {
            SwitchRow(
              title = stringResource(R.string.settings_confirm),
              subtitle = stringResource(R.string.settings_confirm_help),
              checked = settings.confirmBeforeTake,
              onCheckedChange = { onSaveSettings(settings.copy(confirmBeforeTake = it)) },
            )
          }
        }
      }

      item(key = "windows-section") {
        SectionHeader(stringResource(R.string.settings_windows)) {
          IconButton(onClick = { onOpenWindow(null) }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.window_add))
          }
        }
        val sorted = remember(buckets) { buckets.chronological() }
        GroupedColumn {
          sorted.forEachIndexed { index, bucket ->
            GroupItem(index = index, count = sorted.size) {
              ListItem(
                supportingContent = { Text(windowSummary(resources, bucket, settings)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                colors = groupListItemColors(),
                modifier = Modifier.clickable { onOpenWindow(bucket.id) },
              ) {
                Text(bucket.name)
              }
            }
          }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
          onClick = { confirmResetWindows = true },
          modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_restore_windows)) }
      }

      item(key = "permissions-section") {
        SectionHeader(stringResource(R.string.settings_permissions))
        PermissionsChecklistSection(onPermissionStateChanged = onPermissionsChanged)
      }
    }
    }
  }

  if (confirmResetWindows) {
    ConfirmDialog(
      title = stringResource(R.string.settings_restore_windows_title),
      text = stringResource(R.string.settings_restore_windows_text),
      confirmLabel = stringResource(R.string.settings_restore_confirm),
      onConfirm = {
        confirmResetWindows = false
        onResetWindows()
      },
      onDismiss = { confirmResetWindows = false },
    )
  }
}
