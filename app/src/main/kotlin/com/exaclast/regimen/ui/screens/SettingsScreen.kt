@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.exaclast.regimen.ui.screens

import android.Manifest
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exaclast.regimen.R
import com.exaclast.regimen.data.DrugBarcodeScanner
import com.exaclast.regimen.domain.WindowDeletion
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ThemeMode
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.notifications.DoseNotificationStage
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.ui.ScheduleText
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.SwitchRow
import com.exaclast.regimen.ui.components.TimePickerField
import com.exaclast.regimen.ui.components.groupListItemColors
import com.exaclast.regimen.ui.navigation.BackupEncryptionMode
import com.google.mlkit.genai.common.FeatureStatus
import java.util.UUID
import kotlinx.coroutines.launch

/** Item shown in Settings → Diagnostics for sending test notifications. */
data class DiagnosticsTarget(val label: String)

@Composable
fun SettingsScreen(
  settings: AppSettings,
  tracksProviders: Boolean,
  tracksIndications: Boolean,
  buckets: List<TimeOfDayBucket>,
  diagnosticsTarget: DiagnosticsTarget?,
  onBack: () -> Unit,
  onSaveSettings: (AppSettings) -> Unit,
  onOpenWindow: (String?) -> Unit,
  onResetWindows: () -> Unit,
  onTestNotification: (DoseNotificationStage) -> Unit,
  backupEncryptionConfigured: Boolean,
  pendingExport: Boolean,
  onConsumePendingExport: () -> Unit,
  suggestedBackupFileName: (encrypted: Boolean) -> String,
  onExportBackup: (uri: Uri, encrypted: Boolean) -> Unit,
  onRestoreFilePicked: (Uri) -> Unit,
  onOpenBackupEncryption: (BackupEncryptionMode) -> Unit,
  onRequestExportAfterSetup: () -> Unit,
  onClearAllData: () -> Unit,
  onResetSampleData: () -> Unit,
  onPermissionsChanged: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()
  var statusMessage by remember { mutableStateOf<String?>(null) }
  val exportEncryptedLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(BackupFiles.MIME_TYPE),
  ) { uri -> uri?.let { onExportBackup(it, true) } }
  val exportPlainLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/json"),
  ) { uri -> uri?.let { onExportBackup(it, false) } }
  val restoreLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
  ) { uri -> uri?.let(onRestoreFilePicked) }
  var confirmPlainExport by remember { mutableStateOf(false) }

  // Export was tapped before a passphrase existed: continue once setup is done. (Leaving setup
  // without finishing clears the request.)
  LaunchedEffect(pendingExport, backupEncryptionConfigured) {
    if (!pendingExport || !backupEncryptionConfigured) return@LaunchedEffect
    onConsumePendingExport()
    exportEncryptedLauncher.launch(suggestedBackupFileName(true))
  }
  var confirmResetWindows by remember { mutableStateOf(false) }
  var confirmClear by remember { mutableStateOf(false) }
  var confirmDemo by remember { mutableStateOf(false) }
  var nanoStatus by remember { mutableStateOf<Int?>(null) }
  var nanoProgressMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    nanoStatus = DrugBarcodeScanner.checkNanoStatus()
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        coroutineScope.launch {
          nanoStatus = DrugBarcodeScanner.checkNanoStatus()
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
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
      item(key = "reminders") {
        SectionHeader(stringResource(R.string.settings_reminders))
        GroupedColumn {
          GroupItem(0, 6) {
            SwitchRow(
              title = stringResource(R.string.settings_dose_reminders),
              subtitle = stringResource(R.string.settings_dose_reminders_help),
              checked = settings.notificationsEnabled,
              onCheckedChange = { onSaveSettings(settings.copy(notificationsEnabled = it)) },
            )
          }
          GroupItem(1, 6) {
            SwitchRow(
              title = stringResource(R.string.settings_end_alarm),
              subtitle = stringResource(R.string.settings_end_alarm_help),
              checked = settings.alarmsEnabled,
              enabled = settings.notificationsEnabled,
              onCheckedChange = { onSaveSettings(settings.copy(alarmsEnabled = it)) },
            )
          }
          GroupItem(2, 6) {
            SwitchRow(
              title = stringResource(R.string.settings_discreet),
              subtitle = stringResource(R.string.settings_discreet_help),
              checked = settings.discreetNotifications,
              enabled = settings.notificationsEnabled,
              onCheckedChange = { onSaveSettings(settings.copy(discreetNotifications = it)) },
            )
          }
          GroupItem(3, 6) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text(stringResource(R.string.settings_snooze_title), style = MaterialTheme.typography.bodyLarge)
              Text(
                stringResource(R.string.settings_snooze_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(8.dp))
              val presets = AppSettings.SNOOZE_PRESETS
              SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                presets.forEachIndexed { i, mins ->
                  SegmentedButton(
                    selected = settings.snoozeMinutes == mins,
                    onClick = { onSaveSettings(settings.copy(snoozeMinutes = mins)) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = presets.size),
                    icon = {},
                    label = { Text(stringResource(R.string.settings_minutes_short, mins), maxLines = 1) },
                  )
                }
              }
            }
          }
          GroupItem(4, 6) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text(stringResource(R.string.settings_repeat_title), style = MaterialTheme.typography.bodyLarge)
              Text(
                stringResource(R.string.settings_repeat_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(Modifier.height(8.dp))
              val repeatPresets = AppSettings.REPEAT_PRESETS
              SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                repeatPresets.forEachIndexed { i, mins ->
                  SegmentedButton(
                    selected = settings.reminderRepeatMinutes == mins,
                    onClick = { onSaveSettings(settings.copy(reminderRepeatMinutes = mins)) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = repeatPresets.size),
                    icon = {},
                    label = { Text(if (mins == 0) stringResource(R.string.value_off) else stringResource(R.string.settings_minutes_tiny, mins), maxLines = 1) },
                  )
                }
              }
            }
          }
          GroupItem(5, 6) {
            SwitchRow(
              title = stringResource(R.string.settings_confirm),
              subtitle = stringResource(R.string.settings_confirm_help),
              checked = settings.confirmBeforeTake,
              onCheckedChange = { onSaveSettings(settings.copy(confirmBeforeTake = it)) },
            )
          }
        }
      }

      item(key = "windows") {
        SectionHeader(stringResource(R.string.settings_windows)) {
          TextButton(onClick = { onOpenWindow(null) }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.action_add))
          }
        }
        val ordered = buckets.chronological()
        val count = ordered.size + 1
        GroupedColumn {
          ordered.forEachIndexed { i, bucket ->
            GroupItem(index = i, count = count, onClick = { onOpenWindow(bucket.id) }) {
              ListItem(
                supportingContent = { Text(windowSummary(LocalResources.current, bucket, settings)) },
                trailingContent = {
                  Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                colors = groupListItemColors(),
              ) { Text(bucket.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
          }
          GroupItem(index = ordered.size, count = count, onClick = { confirmResetWindows = true }) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_restore_windows_help)) },
              colors = groupListItemColors(),
            ) { Text(stringResource(R.string.settings_restore_windows)) }
          }
        }
      }

      item(key = "permissions") {
        SectionHeader(stringResource(R.string.settings_permissions))
        PermissionsChecklistSection(
          onPermissionStateChanged = onPermissionsChanged,
        )
      }

      item(key = "tracking") {
        SectionHeader(stringResource(R.string.settings_tracking))
        GroupedColumn {
          GroupItem(0, 2) {
            SwitchRow(
              title = stringResource(R.string.providers_title),
              subtitle = stringResource(R.string.settings_providers_help),
              checked = tracksProviders,
              onCheckedChange = { onSaveSettings(settings.copy(trackProviders = it)) },
            )
          }
          GroupItem(1, 2) {
            SwitchRow(
              title = stringResource(R.string.indications_title),
              subtitle = stringResource(R.string.settings_indications_help),
              checked = tracksIndications,
              onCheckedChange = { onSaveSettings(settings.copy(trackIndications = it)) },
            )
          }
        }
        Text(
          text = stringResource(R.string.settings_tracking_help),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
      }

      item(key = "appearance") {
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

      item(key = "scanning") {
        SectionHeader(stringResource(R.string.settings_scanning))
        val initiatingMessage = stringResource(R.string.nano_initiating)
        val downloadFailedMessage = stringResource(R.string.nano_download_failed)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          if (nanoStatus == FeatureStatus.UNAVAILABLE) {
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Default.Info,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = stringResource(R.string.settings_nano_unsupported),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          } else {
            NanoAiCard(
              nanoStatus = nanoStatus,
              nanoProgressMessage = nanoProgressMessage,
              onDownloadClick = {
                coroutineScope.launch {
                  nanoProgressMessage = initiatingMessage
                  DrugBarcodeScanner.startNanoDownload(
                    onProgress = { msg -> nanoProgressMessage = msg }
                  ).fold(
                    onSuccess = {
                      nanoStatus = DrugBarcodeScanner.checkNanoStatus()
                      onPermissionsChanged()
                    },
                    onFailure = { err ->
                      nanoProgressMessage = err.localizedMessage ?: downloadFailedMessage
                      DrugBarcodeScanner.openAiCoreSettings(context)
                    }
                  )
                }
              },
              onSettingsClick = {
                DrugBarcodeScanner.openAiCoreSettings(context)
              },
            )
          }

          // OpenFDA lookup works on every device (manual entry + "Match FDA"), AI or not.
          FdaLookupCard(
            allowFdaLookup = settings.allowFdaLookup,
            onAllowFdaLookupChange = { onSaveSettings(settings.copy(allowFdaLookup = it)) },
          )

          // The printed-name filter only matters for AI label scanning.
          if (nanoStatus != FeatureStatus.UNAVAILABLE) {
            PatientNameField(
              savedName = settings.patientName,
              onSave = { onSaveSettings(settings.copy(patientName = it)) },
            )
          }
        }
      }

      item(key = "diagnostics") {
        SectionHeader(stringResource(R.string.settings_diagnostics))
        val enabled = diagnosticsTarget != null && settings.notificationsEnabled
        val supporting = when {
          !settings.notificationsEnabled -> stringResource(R.string.settings_test_turn_on)
          diagnosticsTarget == null -> stringResource(R.string.settings_test_no_dose)
          else -> stringResource(R.string.settings_test_uses, diagnosticsTarget.label)
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

      item(key = "data") {
        SectionHeader(stringResource(R.string.settings_backup))
        GroupedColumn {
          GroupItem(0, 3, onClick = {
            if (backupEncryptionConfigured) {
              exportEncryptedLauncher.launch(suggestedBackupFileName(true))
            } else {
              onRequestExportAfterSetup()
              onOpenBackupEncryption(BackupEncryptionMode.SETUP)
            }
          }) {
            ListItem(
              supportingContent = {
                Text(
                  stringResource(
                    if (backupEncryptionConfigured) R.string.settings_export_help else R.string.settings_export_help_setup,
                  ),
                )
              },
              colors = groupListItemColors(),
            ) { Text(stringResource(R.string.settings_export)) }
          }
          GroupItem(1, 3, onClick = { restoreLauncher.launch(arrayOf("*/*")) }) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_restore_help)) },
              colors = groupListItemColors(),
            ) { Text(stringResource(R.string.settings_restore)) }
          }
          GroupItem(2, 3, onClick = { confirmPlainExport = true }) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_export_plain_help)) },
              colors = groupListItemColors(),
            ) { Text(stringResource(R.string.settings_export_plain)) }
          }
        }
        Spacer(Modifier.height(12.dp))
        GroupedColumn {
          if (backupEncryptionConfigured) {
            GroupItem(0, 2, onClick = { onOpenBackupEncryption(BackupEncryptionMode.CHANGE) }) {
              ListItem(
                supportingContent = { Text(stringResource(R.string.settings_change_passphrase_help)) },
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.settings_change_passphrase)) }
            }
            GroupItem(1, 2, onClick = { onOpenBackupEncryption(BackupEncryptionMode.SHOW_CODE) }) {
              ListItem(
                supportingContent = { Text(stringResource(R.string.settings_show_code_help)) },
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.settings_show_code)) }
            }
          } else {
            GroupItem(0, 1, onClick = { onOpenBackupEncryption(BackupEncryptionMode.SETUP) }) {
              ListItem(
                supportingContent = { Text(stringResource(R.string.settings_passphrase_not_set)) },
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.backup_passphrase_title)) }
            }
          }
        }
        statusMessage?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
          )
        }
      }

      item(key = "danger") {
        SectionHeader(stringResource(R.string.settings_danger))
        GroupedColumn {
          GroupItem(0, 2, onClick = { confirmDemo = true }) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_demo_help)) },
              colors = groupListItemColors(),
            ) { Text(stringResource(R.string.demo_load)) }
          }
          GroupItem(1, 2, onClick = { confirmClear = true }) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_delete_all_help)) },
              colors = groupListItemColors(),
            ) { Text(stringResource(R.string.settings_delete_all), color = MaterialTheme.colorScheme.error) }
          }
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
  if (confirmDemo) {
    val demoLoadedMessage = stringResource(R.string.settings_demo_loaded)
    ConfirmDialog(
      title = stringResource(R.string.demo_load_title),
      text = stringResource(R.string.settings_demo_text),
      confirmLabel = stringResource(R.string.demo_load),
      destructive = true,
      onConfirm = {
        confirmDemo = false
        onResetSampleData()
        statusMessage = demoLoadedMessage
      },
      onDismiss = { confirmDemo = false },
    )
  }
  if (confirmPlainExport) {
    AlertDialog(
      onDismissRequest = { confirmPlainExport = false },
      title = { Text(stringResource(R.string.settings_plain_title)) },
      text = { Text(stringResource(R.string.settings_plain_text)) },
      confirmButton = {
        TextButton(onClick = {
          confirmPlainExport = false
          exportPlainLauncher.launch(suggestedBackupFileName(false))
        }) { Text(stringResource(R.string.settings_plain_confirm)) }
      },
      dismissButton = { TextButton(onClick = { confirmPlainExport = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }

  if (confirmClear) {
    val allDeletedMessage = stringResource(R.string.settings_all_deleted)
    ConfirmDialog(
      title = stringResource(R.string.settings_delete_all_title),
      text = stringResource(R.string.settings_delete_all_text),
      confirmLabel = stringResource(R.string.settings_delete_all_confirm),
      destructive = true,
      onConfirm = {
        confirmClear = false
        onClearAllData()
        statusMessage = allDeletedMessage
      },
      onDismiss = { confirmClear = false },
    )
  }
}

private fun windowSummary(res: Resources, bucket: TimeOfDayBucket, settings: AppSettings): String {
  val reminder = res.getString(
    when {
      !settings.notificationsEnabled || !bucket.notificationsEnabled -> R.string.window_reminders_off
      settings.alarmsEnabled && bucket.alarmAtWindowEnd -> R.string.window_reminder_and_alarm
      else -> R.string.window_reminder_only
    },
  )
  val time = if (bucket.crossesMidnight) res.getString(R.string.today_window_overnight, bucket.formattedTime()) else bucket.formattedTime()
  return res.getString(R.string.window_summary, time, reminder)
}

/** Saves when editing finishes (Done or focus leaves), not on every keystroke. */
@Composable
private fun PatientNameField(savedName: String, onSave: (String) -> Unit) {
  var text by rememberSaveable(savedName) { mutableStateOf(savedName) }
  val focusManager = LocalFocusManager.current
  fun commit() {
    if (text.trim() != savedName) onSave(text.trim())
  }
  Column {
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      label = { Text(stringResource(R.string.settings_label_name)) },
      supportingText = { Text(stringResource(R.string.settings_label_name_help)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = {
        commit()
        focusManager.clearFocus()
      }),
      modifier = Modifier
        .fillMaxWidth()
        .onFocusChanged { if (!it.isFocused) commit() },
    )
  }
}

@Composable
fun DoseWindowEditorScreen(
  initialBucket: TimeOfDayBucket?,
  canDelete: Boolean,
  allWindows: List<TimeOfDayBucket>,
  schedules: List<MedicationSchedule>,
  medications: List<Medication>,
  globalNotificationsEnabled: Boolean,
  globalAlarmsEnabled: Boolean,
  onClose: () -> Unit,
  onSave: (TimeOfDayBucket) -> Unit,
  /** Receives the window the deleted window's doses move to (null when none are scheduled in it). */
  onDelete: (String?) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf(initialBucket?.name.orEmpty()) }
  var startHour by rememberSaveable { mutableIntStateOf(initialBucket?.hour ?: 8) }
  var startMinute by rememberSaveable { mutableIntStateOf(initialBucket?.minute ?: 0) }
  var endHour by rememberSaveable { mutableIntStateOf(initialBucket?.endHour ?: 10) }
  var endMinute by rememberSaveable { mutableIntStateOf(initialBucket?.endMinute ?: 0) }
  var notifEnabled by rememberSaveable { mutableStateOf(initialBucket?.notificationsEnabled ?: true) }
  var alarmAtEnd by rememberSaveable { mutableStateOf(initialBucket?.alarmAtWindowEnd ?: true) }
  var confirmDelete by remember { mutableStateOf(false) }

  val startTotal = startHour * 60 + startMinute
  val endTotal = endHour * 60 + endMinute
  val crossesMidnight = endTotal < startTotal
  val durationMinutes = if (crossesMidnight) 24 * 60 - startTotal + endTotal else endTotal - startTotal
  val durationText = listOfNotNull(
    (durationMinutes / 60).takeIf { it > 0 }?.let { stringResource(R.string.duration_hours, it) },
    (durationMinutes % 60).takeIf { it > 0 }?.let { stringResource(R.string.duration_minutes, it) },
  ).joinToString(" ")
  val timeError = if (durationMinutes <= 0) stringResource(R.string.window_same_time) else null

  EditorScaffold(
    title = stringResource(if (initialBucket == null) R.string.window_add else R.string.window_edit),
    onClose = onClose,
    saveEnabled = name.isNotBlank() && timeError == null,
    onSave = {
      onSave(
        TimeOfDayBucket(
          id = initialBucket?.id ?: UUID.randomUUID().toString(),
          name = name.trim(),
          hour = startHour,
          minute = startMinute,
          endHour = endHour,
          endMinute = endMinute,
          sortOrder = initialBucket?.sortOrder ?: 0,
          notificationsEnabled = notifEnabled,
          alarmAtWindowEnd = alarmAtEnd,
        )
      )
    },
  ) {
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text(stringResource(R.string.field_name_required)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    if (initialBucket == null) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stringArrayResource(R.array.window_name_presets).forEach { preset ->
          FilterChip(
            selected = name.equals(preset, ignoreCase = true),
            onClick = { name = preset },
            label = { Text(preset) },
          )
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      TimePickerField(
        label = stringResource(R.string.window_starts),
        hour = startHour,
        minute = startMinute,
        onTimeChange = { h, m -> startHour = h; startMinute = m },
        modifier = Modifier.weight(1f),
      )
      TimePickerField(
        label = stringResource(R.string.field_ends),
        hour = endHour,
        minute = endMinute,
        onTimeChange = { h, m -> endHour = h; endMinute = m },
        modifier = Modifier.weight(1f),
      )
    }
    Text(
      text = timeError ?: if (crossesMidnight) stringResource(R.string.window_ends_next_morning, durationText) else durationText,
      style = MaterialTheme.typography.bodyMedium,
      color = if (timeError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GroupedColumn {
      GroupItem(0, 2) {
        SwitchRow(
          title = stringResource(R.string.window_reminder_switch),
          subtitle = if (!globalNotificationsEnabled) stringResource(R.string.window_reminders_off_in_settings) else null,
          checked = notifEnabled,
          enabled = globalNotificationsEnabled,
          onCheckedChange = { notifEnabled = it },
        )
      }
      GroupItem(1, 2) {
        SwitchRow(
          title = stringResource(R.string.window_alarm_switch),
          subtitle = if (!globalAlarmsEnabled) stringResource(R.string.window_alarms_off_in_settings) else null,
          checked = alarmAtEnd,
          enabled = globalNotificationsEnabled && globalAlarmsEnabled && notifEnabled,
          onCheckedChange = { alarmAtEnd = it },
        )
      }
    }
    if (initialBucket != null && canDelete) {
      Spacer(Modifier.height(8.dp))
      OutlinedButton(
        onClick = { confirmDelete = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
      ) { Text(stringResource(R.string.window_delete)) }
    }
  }

  if (confirmDelete && initialBucket != null) {
    DeleteWindowDialog(
      window = initialBucket,
      allWindows = allWindows,
      schedules = schedules,
      medications = medications,
      onConfirm = { moveToId ->
        confirmDelete = false
        onDelete(moveToId)
      },
      onDismiss = { confirmDelete = false },
    )
  }
}

/** Deleting a window changes when medications are taken, so the user picks where the doses go. */
@Composable
private fun DeleteWindowDialog(
  window: TimeOfDayBucket,
  allWindows: List<TimeOfDayBucket>,
  schedules: List<MedicationSchedule>,
  medications: List<Medication>,
  onConfirm: (String?) -> Unit,
  onDismiss: () -> Unit,
) {
  val others = allWindows.filter { it.id != window.id }.chronological()
  val affected = WindowDeletion.affectedSchedules(window.id, schedules)
  if (affected.isEmpty() || others.isEmpty()) {
    ConfirmDialog(
      title = stringResource(R.string.provider_delete_title, window.name),
      text = stringResource(R.string.window_delete_none_scheduled),
      confirmLabel = stringResource(R.string.action_delete),
      destructive = true,
      onConfirm = { onConfirm(null) },
      onDismiss = onDismiss,
    )
    return
  }

  var targetId by rememberSaveable { mutableStateOf((WindowDeletion.nearestWindow(window, others) ?: others.first()).id) }
  val changes = WindowDeletion.plan(window.id, targetId, affected, emptyList(), 0L).scheduleChanges

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.provider_delete_title, window.name)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(stringResource(R.string.window_delete_choose))
        others.forEach { other ->
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .selectable(selected = other.id == targetId, role = Role.RadioButton, onClick = { targetId = other.id })
              .padding(vertical = 4.dp),
          ) {
            RadioButton(selected = other.id == targetId, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.window_name_time, other.name, other.formattedTime()), style = MaterialTheme.typography.bodyLarge)
          }
        }
        Text(stringResource(R.string.window_delete_what_changes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        changes.forEach { change ->
          val name = medications.find { it.id == change.before.medicationId }?.primaryName ?: stringResource(R.string.label_medication)
          val before = ScheduleText.windows(change.before, allWindows)
          val after = ScheduleText.windows(change.after, allWindows)
          Text(stringResource(R.string.window_delete_change, name, before, after), style = MaterialTheme.typography.bodyMedium)
          if (change.losesDose) {
            Text(
              stringResource(R.string.window_delete_loses_dose),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(targetId) },
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.error,
          contentColor = MaterialTheme.colorScheme.onError,
        ),
      ) { Text(stringResource(R.string.window_delete_and_move)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
fun StartupPermissionsDialog(
  settings: AppSettings,
  tracksProviders: Boolean,
  tracksIndications: Boolean,
  onSaveSettings: (AppSettings) -> Unit,
  onDismiss: () -> Unit,
  onPermissionsChanged: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()
  var refreshTick by remember { mutableIntStateOf(0) }
  var nanoProgressMessage by remember { mutableStateOf<String?>(null) }
  val initiatingMessage = stringResource(R.string.nano_initiating)
  val downloadFailedMessage = stringResource(R.string.nano_download_failed)

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        refreshTick++
        onPermissionsChanged()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val allCoreGranted = remember(refreshTick) {
    DoseNotificationManager.areCorePermissionsGranted(context)
  }
  val batteryUnrestricted = remember(refreshTick) {
    DoseNotificationManager.isIgnoringBatteryOptimizations(context)
  }

  var nanoStatus by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(refreshTick) {
    nanoStatus = DrugBarcodeScanner.checkNanoStatus()
  }
  val isNanoSupported = nanoStatus != null && nanoStatus != FeatureStatus.UNAVAILABLE

  // Local until the dialog closes: saving per keystroke trimmed the text and reset the field
  // (no spaces could be typed) and re-armed every alarm on each character.
  var patientNameText by rememberSaveable { mutableStateOf(settings.patientName) }

  /** One write on close: the name and the "setup seen" flag together. */
  fun finish() {
    onSaveSettings(
      settings.copy(
        patientName = patientNameText.trim(),
        permissionsPromptDismissedVersion = AppSettings.CURRENT_PERMISSIONS_VERSION,
      )
    )
    onDismiss()
  }

  AlertDialog(
    onDismissRequest = ::finish,
    title = { Text(stringResource(R.string.setup_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          text = stringResource(if (isNanoSupported) R.string.setup_intro_nano else R.string.setup_intro),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = stringResource(R.string.setup_reminders_timing),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          PermissionsChecklistSection(
            onPermissionStateChanged = {
              refreshTick++
              onPermissionsChanged()
            },
          )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = stringResource(R.string.setup_track_question),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = stringResource(R.string.setup_track_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          SwitchRow(
            title = stringResource(R.string.providers_title),
            subtitle = stringResource(R.string.setup_providers_help),
            checked = tracksProviders,
            onCheckedChange = { onSaveSettings(settings.copy(trackProviders = it)) },
          )
          SwitchRow(
            title = stringResource(R.string.indications_title),
            subtitle = stringResource(R.string.setup_indications_help),
            checked = tracksIndications,
            onCheckedChange = { onSaveSettings(settings.copy(trackIndications = it)) },
          )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = stringResource(if (isNanoSupported) R.string.setup_scanning_optional else R.string.setup_lookup_optional),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          if (isNanoSupported) {
            Text(
              text = stringResource(R.string.setup_nano_help),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            NanoAiCard(
              nanoStatus = nanoStatus,
              nanoProgressMessage = nanoProgressMessage,
              onDownloadClick = {
                coroutineScope.launch {
                  nanoProgressMessage = initiatingMessage
                  DrugBarcodeScanner.startNanoDownload(
                    onProgress = { msg -> nanoProgressMessage = msg }
                  ).fold(
                    onSuccess = {
                      nanoStatus = DrugBarcodeScanner.checkNanoStatus()
                      refreshTick++
                      onPermissionsChanged()
                    },
                    onFailure = { err ->
                      nanoProgressMessage = err.localizedMessage ?: downloadFailedMessage
                      DrugBarcodeScanner.openAiCoreSettings(context)
                    }
                  )
                }
              },
              onSettingsClick = {
                DrugBarcodeScanner.openAiCoreSettings(context)
              },
            )
          }

          FdaLookupCard(
            allowFdaLookup = settings.allowFdaLookup,
            onAllowFdaLookupChange = { onSaveSettings(settings.copy(allowFdaLookup = it)) },
          )

          if (isNanoSupported) {
            Text(
              text = stringResource(R.string.setup_name_title),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 6.dp),
            )
            Text(
              text = stringResource(R.string.setup_name_help),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
              value = patientNameText,
              onValueChange = { patientNameText = it },
              label = { Text(stringResource(R.string.setup_name_label)) },
              placeholder = { Text(stringResource(R.string.setup_name_hint)) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
              ),
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = ::finish) {
        Text(
          stringResource(
            if (allCoreGranted && batteryUnrestricted) {
              R.string.setup_all_set
            } else if (allCoreGranted) {
              R.string.action_continue
            } else {
              R.string.setup_done_for_now
            },
          ),
        )
      }
    },
  )
}

@Composable
fun PermissionsChecklistSection(
  onPermissionStateChanged: () -> Unit = {},
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()
  var refreshTick by remember { mutableIntStateOf(0) }
  var nanoProgressMessage by remember { mutableStateOf<String?>(null) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        refreshTick++
        onPermissionStateChanged()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val notifGranted = remember(refreshTick) {
    DoseNotificationManager.hasNotificationPermission(context)
  }
  val exactAlarmsGranted = remember(refreshTick) {
    DoseNotificationManager.canScheduleExactAlarms(context)
  }
  val fullScreenGranted = remember(refreshTick) {
    DoseNotificationManager.canUseFullScreenIntent(context)
  }
  val batteryUnrestricted = remember(refreshTick) {
    DoseNotificationManager.isIgnoringBatteryOptimizations(context)
  }

  val notifPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    refreshTick++
    onPermissionStateChanged()
    if (!granted) {
      DoseNotificationManager.openAppNotificationSettings(context)
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PermissionItemCard(
      title = stringResource(R.string.perm_notifications),
      subtitle = stringResource(if (notifGranted) R.string.perm_notifications_granted else R.string.perm_notifications_needed),
      isGranted = notifGranted,
      actionLabel = stringResource(R.string.perm_allow),
      onActionClick = {
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      },
    )

    PermissionItemCard(
      title = stringResource(R.string.perm_exact_alarms),
      subtitle = stringResource(if (exactAlarmsGranted) R.string.perm_exact_alarms_granted else R.string.perm_exact_alarms_needed),
      isGranted = exactAlarmsGranted,
      actionLabel = stringResource(R.string.perm_allow),
      onActionClick = {
        DoseNotificationManager.openExactAlarmSettings(context)
      },
    )

    PermissionItemCard(
      title = stringResource(R.string.perm_full_screen),
      subtitle = stringResource(if (fullScreenGranted) R.string.perm_full_screen_granted else R.string.perm_full_screen_needed),
      isGranted = fullScreenGranted,
      actionLabel = stringResource(R.string.perm_allow),
      onActionClick = {
        DoseNotificationManager.openFullScreenIntentSettings(context)
      },
    )

    PermissionItemCard(
      title = stringResource(R.string.perm_battery),
      subtitle = stringResource(if (batteryUnrestricted) R.string.perm_battery_granted else R.string.perm_battery_needed),
      isGranted = batteryUnrestricted,
      actionLabel = stringResource(R.string.perm_exempt),
      onActionClick = {
        DoseNotificationManager.requestIgnoreBatteryOptimizations(context)
      },
    )
  }
}

@Composable
private fun NanoAiCard(
  nanoStatus: Int?,
  nanoProgressMessage: String?,
  onDownloadClick: () -> Unit,
  onSettingsClick: () -> Unit,
) {
  val isNanoReady = nanoStatus == FeatureStatus.AVAILABLE
  val nanoSubtitle = when (nanoStatus) {
    FeatureStatus.AVAILABLE -> stringResource(R.string.nano_ready)
    FeatureStatus.DOWNLOADING -> nanoProgressMessage ?: stringResource(R.string.nano_downloading)
    FeatureStatus.DOWNLOADABLE -> nanoProgressMessage ?: stringResource(R.string.nano_downloadable)
    FeatureStatus.UNAVAILABLE -> stringResource(R.string.nano_unsupported)
    else -> stringResource(R.string.nano_checking)
  }
  val nanoActionLabel = when (nanoStatus) {
    FeatureStatus.DOWNLOADING -> stringResource(R.string.nano_aicore_settings)
    FeatureStatus.DOWNLOADABLE -> stringResource(R.string.nano_download)
    else -> null
  }

  PermissionItemCard(
    title = stringResource(R.string.nano_title),
    subtitle = nanoSubtitle,
    isGranted = isNanoReady,
    actionLabel = nanoActionLabel,
    onActionClick = if (nanoActionLabel != null) {
      {
        if (nanoStatus == FeatureStatus.DOWNLOADING) {
          onSettingsClick()
        } else if (nanoStatus == FeatureStatus.DOWNLOADABLE) {
          onDownloadClick()
        }
      }
    } else null,
  )
}

@Composable
private fun FdaLookupCard(
  allowFdaLookup: Boolean,
  onAllowFdaLookupChange: (Boolean) -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = if (allowFdaLookup) {
      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    } else {
      MaterialTheme.colorScheme.surface
    },
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onAllowFdaLookupChange(!allowFdaLookup) },
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(
        checked = allowFdaLookup,
        onCheckedChange = onAllowFdaLookupChange,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.fda_title),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = if (allowFdaLookup) {
            MaterialTheme.colorScheme.onSecondaryContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          text = stringResource(R.string.fda_help),
          style = MaterialTheme.typography.bodySmall,
          color = if (allowFdaLookup) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
    }
  }
}

@Composable
private fun PermissionItemCard(
  title: String,
  subtitle: String,
  isGranted: Boolean,
  actionLabel: String? = null,
  onActionClick: (() -> Unit)? = null,
) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = if (isGranted) {
      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    } else {
      MaterialTheme.colorScheme.surface
    },
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(end = 8.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = if (isGranted) {
            MaterialTheme.colorScheme.onSecondaryContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = if (isGranted) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
      if (isGranted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.perm_granted),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = stringResource(R.string.perm_active),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      } else if (actionLabel != null && onActionClick != null) {
        FilledTonalButton(onClick = onActionClick) {
          Text(actionLabel)
        }
      }
    }
  }
}
