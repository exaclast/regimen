@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.data.AutoBackupError
import com.exaclast.regimen.data.AutoBackupFrequency
import com.exaclast.regimen.data.AutoBackupStatus
import com.exaclast.regimen.sync.AutoBackupRetention
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.ui.components.AdaptiveSingleChoiceRow
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.CenteredContentBox
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.SwitchRow
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.components.groupListItemColors
import com.exaclast.regimen.ui.navigation.BackupEncryptionMode

@Composable
fun SettingsBackupScreen(
  backupEncryptionConfigured: Boolean,
  backupKeyLost: Boolean,
  pendingExport: Boolean,
  onConsumePendingExport: () -> Unit,
  suggestedBackupFileName: (encrypted: Boolean) -> String,
  onExportBackup: (uri: Uri, encrypted: Boolean) -> Unit,
  onRestoreFilePicked: (Uri) -> Unit,
  onOpenBackupEncryption: (BackupEncryptionMode) -> Unit,
  onRequestExportAfterSetup: () -> Unit,
  autoBackupStatus: AutoBackupStatus,
  autoBackupBusy: Boolean,
  pendingAutoBackupSetup: Boolean,
  onConsumePendingAutoBackupSetup: () -> Unit,
  onRequestAutoBackupAfterSetup: () -> Unit,
  onEnableAutoBackupWithFolder: (Uri) -> Unit,
  onSetAutoBackupFolder: (Uri) -> Unit,
  onSetAutoBackupEnabled: (Boolean) -> Unit,
  onSetAutoBackupFrequency: (AutoBackupFrequency) -> Unit,
  onSetAutoBackupKeepCount: (Int) -> Unit,
  onBackUpNow: () -> Unit,
  onClearAllData: () -> Unit,
  onResetSampleData: (() -> Unit)? = null,
  onBack: () -> Unit,
) {
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

  var pickingFolderToEnable by remember { mutableStateOf(false) }
  val folderPickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree(),
  ) { uri ->
    val enableOnPick = pickingFolderToEnable
    pickingFolderToEnable = false
    if (uri != null) {
      if (enableOnPick || !autoBackupStatus.enabled || autoBackupStatus.treeUri == null) {
        onEnableAutoBackupWithFolder(uri)
      } else {
        onSetAutoBackupFolder(uri)
      }
    }
  }
  var confirmPlainExport by remember { mutableStateOf(false) }

  // Export was tapped before a passphrase existed (or while the device key was lost): continue once unlocked.
  LaunchedEffect(pendingExport, backupEncryptionConfigured) {
    if (!pendingExport || !backupEncryptionConfigured) return@LaunchedEffect
    onConsumePendingExport()
    exportEncryptedLauncher.launch(suggestedBackupFileName(true))
  }

  val needsFolderSelection = autoBackupStatus.treeUri == null ||
    autoBackupStatus.lastError == AutoBackupError.PERMISSION_LOST ||
    autoBackupStatus.lastError == AutoBackupError.FOLDER_MISSING

  // Auto-backup was toggled on before a passphrase existed (or while the device key was lost).
  LaunchedEffect(pendingAutoBackupSetup, backupEncryptionConfigured) {
    if (!pendingAutoBackupSetup || !backupEncryptionConfigured) return@LaunchedEffect
    onConsumePendingAutoBackupSetup()
    if (needsFolderSelection) {
      pickingFolderToEnable = true
      folderPickerLauncher.launch(null)
    } else {
      onSetAutoBackupEnabled(true)
    }
  }

  var confirmClear by remember { mutableStateOf(false) }
  var confirmDemo by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_category_backup)) },
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
      item(key = "backup-section") {
        SectionHeader(stringResource(R.string.settings_backup))
        GroupedColumn {
          GroupItem(0, 3, onClick = {
            when {
              backupEncryptionConfigured -> exportEncryptedLauncher.launch(suggestedBackupFileName(true))
              backupKeyLost -> {
                onRequestExportAfterSetup()
                onOpenBackupEncryption(BackupEncryptionMode.UNLOCK_DEVICE)
              }
              else -> {
                onRequestExportAfterSetup()
                onOpenBackupEncryption(BackupEncryptionMode.SETUP)
              }
            }
          }) {
            ListItem(
              supportingContent = {
                Text(
                  stringResource(
                    when {
                      backupEncryptionConfigured -> R.string.settings_export_help
                      backupKeyLost -> R.string.settings_key_lost_help
                      else -> R.string.settings_export_help_setup
                    },
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
          when {
            backupEncryptionConfigured -> {
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
            }
            backupKeyLost -> {
              GroupItem(0, 2, onClick = { onOpenBackupEncryption(BackupEncryptionMode.UNLOCK_DEVICE) }) {
                ListItem(
                  supportingContent = { Text(stringResource(R.string.settings_key_lost_help)) },
                  colors = groupListItemColors(),
                ) { Text(stringResource(R.string.settings_key_lost_title), color = MaterialTheme.colorScheme.error) }
              }
              GroupItem(1, 2, onClick = { onOpenBackupEncryption(BackupEncryptionMode.RESET) }) {
                ListItem(
                  supportingContent = { Text(stringResource(R.string.backup_reset_help)) },
                  colors = groupListItemColors(),
                ) { Text(stringResource(R.string.backup_reset_passphrase_title)) }
              }
            }
            else -> {
              GroupItem(0, 1, onClick = { onOpenBackupEncryption(BackupEncryptionMode.SETUP) }) {
                ListItem(
                  supportingContent = { Text(stringResource(R.string.settings_passphrase_not_set)) },
                  colors = groupListItemColors(),
                ) { Text(stringResource(R.string.backup_passphrase_title)) }
              }
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

      item(key = "auto-backup-section") {
        Spacer(Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.auto_backup_section))
        val showConfigRows = autoBackupStatus.enabled || autoBackupStatus.treeUri != null
        val canRunNow = autoBackupStatus.treeUri != null &&
          backupEncryptionConfigured &&
          autoBackupStatus.lastError != AutoBackupError.PERMISSION_LOST &&
          autoBackupStatus.lastError != AutoBackupError.FOLDER_MISSING
        val rowCount = if (showConfigRows) 5 else 1

        GroupedColumn {
          GroupItem(0, rowCount) {
            SwitchRow(
              title = stringResource(R.string.auto_backup_switch),
              subtitle = stringResource(R.string.auto_backup_switch_help),
              checked = autoBackupStatus.enabled,
              onCheckedChange = { wantEnabled ->
                if (!wantEnabled) {
                  onSetAutoBackupEnabled(false)
                } else {
                  when {
                    !backupEncryptionConfigured -> {
                      onRequestAutoBackupAfterSetup()
                      onOpenBackupEncryption(
                        if (backupKeyLost) BackupEncryptionMode.UNLOCK_DEVICE else BackupEncryptionMode.SETUP,
                      )
                    }
                    needsFolderSelection -> {
                      pickingFolderToEnable = true
                      folderPickerLauncher.launch(null)
                    }
                    else -> onSetAutoBackupEnabled(true)
                  }
                }
              },
            )
          }
          if (showConfigRows) {
            GroupItem(1, rowCount, onClick = {
              pickingFolderToEnable = !autoBackupStatus.enabled
              folderPickerLauncher.launch(null)
            }) {
              ListItem(
                supportingContent = {
                  Text(autoBackupStatus.folderLabel ?: stringResource(R.string.auto_backup_folder_not_set))
                },
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.auto_backup_folder)) }
            }
            GroupItem(2, rowCount) {
              Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.auto_backup_frequency), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                AdaptiveSingleChoiceRow(
                  options = AutoBackupFrequency.entries,
                  selected = autoBackupStatus.frequency,
                  onSelect = onSetAutoBackupFrequency,
                  label = { freq ->
                    stringResource(
                      when (freq) {
                        AutoBackupFrequency.DAILY -> R.string.auto_backup_freq_daily
                        AutoBackupFrequency.WEEKLY -> R.string.auto_backup_freq_weekly
                      },
                    )
                  },
                )
              }
            }
            GroupItem(3, rowCount) {
              Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.auto_backup_keep_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                  stringResource(R.string.auto_backup_keep_help),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AdaptiveSingleChoiceRow(
                  options = AutoBackupRetention.KEEP_COUNT_PRESETS,
                  selected = autoBackupStatus.keepCount,
                  onSelect = onSetAutoBackupKeepCount,
                  label = { count -> stringResource(R.string.auto_backup_keep_option, count) },
                )
              }
            }
            GroupItem(
              index = 4,
              count = rowCount,
              onClick = if (canRunNow && !autoBackupBusy) onBackUpNow else null,
            ) {
              val lastSuccess = autoBackupStatus.lastSuccessEpochMillis
              val lastKept = autoBackupStatus.lastKeptCount
              val statusText = when {
                autoBackupBusy -> stringResource(R.string.auto_backup_running)
                lastSuccess != null && lastKept != null -> pluralStringResource(
                  R.plurals.auto_backup_status,
                  lastKept,
                  UiFormat.dateTime(LocalResources.current, lastSuccess),
                  lastKept,
                )
                else -> stringResource(R.string.auto_backup_never)
              }
              ListItem(
                supportingContent = { Text(statusText) },
                trailingContent = if (autoBackupBusy) {
                  { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else {
                  null
                },
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.auto_backup_now)) }
            }
          }
        }

        val effectiveError = when {
          autoBackupStatus.lastError != AutoBackupError.NONE -> autoBackupStatus.lastError
          autoBackupStatus.enabled && backupKeyLost -> AutoBackupError.KEY_LOST
          else -> AutoBackupError.NONE
        }
        if (effectiveError != AutoBackupError.NONE) {
          val errorRes = when (effectiveError) {
            AutoBackupError.PERMISSION_LOST -> R.string.auto_backup_err_permission
            AutoBackupError.FOLDER_MISSING -> R.string.auto_backup_err_folder_missing
            AutoBackupError.KEY_LOST -> R.string.auto_backup_err_key_lost
            AutoBackupError.NOT_CONFIGURED -> R.string.auto_backup_err_not_configured
            AutoBackupError.WRITE_FAILED -> R.string.auto_backup_err_write_failed
            AutoBackupError.NONE -> null
          }
          val onErrorClick: (() -> Unit)? = when (effectiveError) {
            AutoBackupError.KEY_LOST -> ({ onOpenBackupEncryption(BackupEncryptionMode.UNLOCK_DEVICE) })
            AutoBackupError.PERMISSION_LOST,
            AutoBackupError.FOLDER_MISSING,
            -> ({
              pickingFolderToEnable = true
              folderPickerLauncher.launch(null)
            })
            else -> null
          }
          errorRes?.let { resId ->
            Spacer(Modifier.height(8.dp))
            if (onErrorClick != null) {
              Surface(
                onClick = onErrorClick,
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = stringResource(resId),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.padding(12.dp),
                )
              }
            } else {
              Text(
                text = stringResource(resId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
              )
            }
          }
        }
      }

      item(key = "danger-section") {
        Spacer(Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.settings_danger))
        GroupedColumn {
          if (onResetSampleData != null) {
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
          } else {
            GroupItem(0, 1, onClick = { confirmClear = true }) {
              ListItem(
                supportingContent = { Text(stringResource(R.string.settings_delete_all_help)) },
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.settings_delete_all), color = MaterialTheme.colorScheme.error) }
            }
          }
        }
      }
    }
    }
  }

  if (confirmDemo && onResetSampleData != null) {
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
