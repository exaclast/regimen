@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.groupListItemColors
import com.exaclast.regimen.ui.navigation.BackupEncryptionMode

@Composable
fun SettingsBackupScreen(
  backupEncryptionConfigured: Boolean,
  pendingExport: Boolean,
  onConsumePendingExport: () -> Unit,
  suggestedBackupFileName: (encrypted: Boolean) -> String,
  onExportBackup: (uri: Uri, encrypted: Boolean) -> Unit,
  onRestoreFilePicked: (Uri) -> Unit,
  onOpenBackupEncryption: (BackupEncryptionMode) -> Unit,
  onRequestExportAfterSetup: () -> Unit,
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
  var confirmPlainExport by remember { mutableStateOf(false) }

  // Export was tapped before a passphrase existed: continue once setup is done.
  LaunchedEffect(pendingExport, backupEncryptionConfigured) {
    if (!pendingExport || !backupEncryptionConfigured) return@LaunchedEffect
    onConsumePendingExport()
    exportEncryptedLauncher.launch(suggestedBackupFileName(true))
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
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
      item(key = "backup-section") {
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
