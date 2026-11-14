package com.exaclast.regimen.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.sync.BackupFiles
import com.exaclast.regimen.ui.RestoreUiState
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.components.groupListItemColors
import com.exaclast.regimen.ui.navigation.BackupEncryptionMode

private const val MIN_PASSPHRASE_LENGTH = 8

/**
 * Backup passphrase tasks. Setup, reset and "show code" end on the recovery code; change ends
 * immediately. Key derivation takes a moment, so actions show progress.
 */
@Composable
fun BackupEncryptionScreen(
  mode: BackupEncryptionMode,
  onSetUp: (passphrase: String, reset: Boolean, onResult: (Result<String?>) -> Unit) -> Unit,
  onChange: (current: String, new: String, onResult: (Result<String?>) -> Unit) -> Unit,
  onReveal: (passphrase: String, onResult: (Result<String?>) -> Unit) -> Unit,
  onReset: () -> Unit,
  onDone: (changed: Boolean) -> Unit,
  onClose: () -> Unit,
) {
  var current by rememberSaveable { mutableStateOf("") }
  var newPass by rememberSaveable { mutableStateOf("") }
  var confirm by rememberSaveable { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  // Not saveable: the code shouldn't be written into saved instance state.
  var recoveryCode by remember { mutableStateOf<String?>(null) }
  var savedConfirmed by rememberSaveable { mutableStateOf(false) }

  val needsCurrent = mode == BackupEncryptionMode.CHANGE || mode == BackupEncryptionMode.SHOW_CODE
  val needsNew = mode != BackupEncryptionMode.SHOW_CODE
  val newTooShort = newPass.isNotEmpty() && newPass.length < MIN_PASSPHRASE_LENGTH
  val mismatch = confirm.isNotEmpty() && confirm != newPass
  val inputValid = (!needsCurrent || current.isNotEmpty()) &&
    (!needsNew || (newPass.length >= MIN_PASSPHRASE_LENGTH && confirm == newPass))

  val title = when (mode) {
    BackupEncryptionMode.SETUP -> stringResource(R.string.backup_passphrase_title)
    BackupEncryptionMode.CHANGE -> stringResource(R.string.backup_change_passphrase_title)
    BackupEncryptionMode.SHOW_CODE -> stringResource(R.string.backup_recovery_code)
    BackupEncryptionMode.RESET -> stringResource(R.string.backup_reset_passphrase_title)
  }
  val genericError = stringResource(R.string.error_generic)

  fun submit() {
    if (!inputValid || busy) return
    busy = true
    error = null
    val handle: (Result<String?>) -> Unit = { result ->
      busy = false
      result.fold(
        onSuccess = { code -> if (code != null) recoveryCode = code else onDone(true) },
        onFailure = { error = it.message ?: genericError },
      )
    }
    when (mode) {
      BackupEncryptionMode.SETUP -> onSetUp(newPass, false, handle)
      BackupEncryptionMode.RESET -> onSetUp(newPass, true, handle)
      BackupEncryptionMode.CHANGE -> onChange(current, newPass, handle)
      BackupEncryptionMode.SHOW_CODE -> onReveal(current, handle)
    }
  }

  val code = recoveryCode
  if (code != null) {
    val mustConfirm = mode != BackupEncryptionMode.SHOW_CODE
    EditorScaffold(
      title = title,
      onClose = { onDone(mustConfirm) },
      onSave = { onDone(mustConfirm) },
      saveEnabled = !mustConfirm || savedConfirmed,
      saveLabel = stringResource(R.string.action_done),
    ) {
      Text(
        stringResource(R.string.backup_code_help),
        style = MaterialTheme.typography.bodyMedium,
      )
      RecoveryCodeCard(code)
      if (mustConfirm) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = savedConfirmed, onCheckedChange = { savedConfirmed = it })
          Text(stringResource(R.string.backup_code_saved), style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
    return
  }

  EditorScaffold(
    title = title,
    onClose = onClose,
    onSave = ::submit,
    saveEnabled = inputValid && !busy,
    saveLabel = when (mode) {
      BackupEncryptionMode.SHOW_CODE -> stringResource(R.string.action_show)
      BackupEncryptionMode.CHANGE -> stringResource(R.string.action_change)
      else -> stringResource(R.string.action_continue)
    },
  ) {
    Text(
      when (mode) {
        BackupEncryptionMode.SETUP ->
          stringResource(R.string.backup_setup_help)
        BackupEncryptionMode.CHANGE -> stringResource(R.string.backup_change_help)
        BackupEncryptionMode.SHOW_CODE -> stringResource(R.string.backup_show_code_help)
        BackupEncryptionMode.RESET -> stringResource(R.string.backup_reset_help)
      },
      style = MaterialTheme.typography.bodyMedium,
    )
    if (needsCurrent) {
      PassphraseField(
        value = current,
        onValueChange = { current = it; error = null },
        label = stringResource(R.string.backup_current_passphrase),
        imeAction = if (needsNew) ImeAction.Next else ImeAction.Done,
        onDone = ::submit,
      )
    }
    if (needsNew) {
      PassphraseField(
        value = newPass,
        onValueChange = { newPass = it; error = null },
        label = stringResource(if (mode == BackupEncryptionMode.SETUP) R.string.backup_passphrase else R.string.backup_new_passphrase),
        isError = newTooShort,
        supportingText = if (newTooShort) pluralStringResource(R.plurals.backup_passphrase_too_short, MIN_PASSPHRASE_LENGTH, MIN_PASSPHRASE_LENGTH) else null,
        imeAction = ImeAction.Next,
        onDone = ::submit,
      )
      PassphraseField(
        value = confirm,
        onValueChange = { confirm = it; error = null },
        label = stringResource(R.string.backup_confirm_passphrase),
        isError = mismatch,
        supportingText = if (mismatch) stringResource(R.string.backup_passphrase_mismatch) else null,
        imeAction = ImeAction.Done,
        onDone = ::submit,
      )
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (busy) BusyRow(stringResource(R.string.backup_securing))
    if (mode == BackupEncryptionMode.CHANGE || mode == BackupEncryptionMode.SHOW_CODE) {
      TextButton(onClick = onReset, enabled = !busy) { Text(stringResource(R.string.backup_forgot)) }
    }
  }
}

@Composable
private fun RecoveryCodeCard(code: String) {
  val context = LocalContext.current
  val clipLabel = stringResource(R.string.backup_clip_label)
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      // Four groups per line keeps the code readable and easy to copy by hand.
      Text(
        code.split("-").chunked(4).joinToString("\n") { it.joinToString("-") },
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
      )
      OutlinedButton(onClick = { copySensitive(context, clipLabel, code) }) {
        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.action_copy), modifier = Modifier.padding(start = 8.dp))
      }
    }
  }
}

/** Copies text flagged as sensitive, so Android hides it from the clipboard preview. */
private fun copySensitive(context: Context, label: String, text: String) {
  val clip = ClipData.newPlainText(label, text)
  clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
  (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}

@Composable
private fun PassphraseField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  imeAction: ImeAction,
  onDone: () -> Unit,
  isError: Boolean = false,
  supportingText: String? = null,
  isRecoveryCode: Boolean = false,
) {
  var visible by rememberSaveable { mutableStateOf(false) }
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    singleLine = !isRecoveryCode,
    minLines = if (isRecoveryCode) 2 else 1,
    isError = isError,
    supportingText = supportingText?.let { { Text(it) } },
    visualTransformation = if (visible || isRecoveryCode) VisualTransformation.None else PasswordVisualTransformation(),
    textStyle = if (isRecoveryCode) {
      MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
    } else {
      MaterialTheme.typography.bodyLarge
    },
    trailingIcon = if (isRecoveryCode) null else {
      {
        IconButton(onClick = { visible = !visible }) {
          Icon(
            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = stringResource(if (visible) R.string.backup_hide_passphrase else R.string.backup_show_passphrase),
          )
        }
      }
    },
    keyboardOptions = KeyboardOptions(
      keyboardType = if (isRecoveryCode) KeyboardType.Ascii else KeyboardType.Password,
      capitalization = if (isRecoveryCode) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
      autoCorrectEnabled = false,
      imeAction = imeAction,
    ),
    keyboardActions = KeyboardActions(onDone = { onDone() }),
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun BusyRow(text: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    Text(text, style = MaterialTheme.typography.bodyMedium)
  }
}

/** Unlock (if needed), preview, then merge or replace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
  state: RestoreUiState,
  onUnlock: (secret: String, isRecoveryCode: Boolean) -> Unit,
  onApply: (replace: Boolean) -> Unit,
  onClose: () -> Unit,
) {
  LaunchedEffect(state) {
    if (state is RestoreUiState.Finished || state is RestoreUiState.Idle) onClose()
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(if (state is RestoreUiState.Locked) R.string.backup_unlock_title else R.string.backup_restore_title)) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) } },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      when (state) {
        is RestoreUiState.Locked -> UnlockContent(state, onUnlock)
        is RestoreUiState.Preview -> PreviewContent(state, onApply)
        else -> BusyRow(stringResource(R.string.backup_opening))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.UnlockContent(state: RestoreUiState.Locked, onUnlock: (String, Boolean) -> Unit) {
  var useRecoveryCode by rememberSaveable { mutableStateOf(false) }
  var secret by remember { mutableStateOf("") }
  val canSubmit = secret.isNotBlank() && !state.busy
  val submit = { if (canSubmit) onUnlock(secret, useRecoveryCode) }

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Text(
      stringResource(R.string.backup_locked_help),
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  state.locked.header.createdAtEpochMillis.takeIf { it > 0 }?.let {
    Text(stringResource(R.string.backup_backed_up, UiFormat.dateTime(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    listOf(stringResource(R.string.backup_passphrase), stringResource(R.string.backup_recovery_code)).forEachIndexed { i, label ->
      SegmentedButton(
        selected = useRecoveryCode == (i == 1),
        onClick = { useRecoveryCode = i == 1; secret = "" },
        shape = SegmentedButtonDefaults.itemShape(i, 2),
      ) { Text(label) }
    }
  }
  PassphraseField(
    value = secret,
    onValueChange = { secret = it },
    label = stringResource(if (useRecoveryCode) R.string.backup_recovery_code else R.string.backup_passphrase),
    imeAction = ImeAction.Done,
    onDone = submit,
    isError = state.error != null,
    supportingText = state.error,
    isRecoveryCode = useRecoveryCode,
  )
  if (state.busy) {
    BusyRow(stringResource(R.string.backup_unlocking))
  } else {
    Button(onClick = submit, enabled = canSubmit, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.backup_unlock)) }
  }
}

@Composable
private fun PreviewContent(state: RestoreUiState.Preview, onApply: (Boolean) -> Unit) {
  val preview = state.ready.preview
  var confirmReplace by remember { mutableStateOf(false) }
  val removedByReplace = preview.kinds.sumOf { it.onlyOnDevice }

  val resources = LocalResources.current
  val source = listOfNotNull(
    stringResource(R.string.backup_backed_up, UiFormat.dateTime(preview.exportedAtEpochMillis)),
    preview.sourceDeviceName?.let { stringResource(R.string.backup_from_device, it) },
  ).joinToString(" ")
  Text(source, style = MaterialTheme.typography.bodyLarge)
  if (!state.ready.wasEncrypted) {
    Text(
      stringResource(R.string.backup_not_encrypted),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  val first = UiFormat.dateIso(preview.firstDoseDateIso)
  val last = UiFormat.dateIso(preview.lastDoseDateIso)
  if (first != null && last != null) {
    Text(
      if (first == last) stringResource(R.string.backup_dose_history_one, first) else stringResource(R.string.backup_dose_history_range, first, last),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  SectionHeader(stringResource(R.string.backup_compared))
  val rows = preview.kinds.filter { it.inFile > 0 || it.onlyOnDevice > 0 }
  GroupedColumn {
    rows.forEachIndexed { i, kind ->
      GroupItem(i, rows.size) {
        ListItem(
          supportingContent = { Text(diffSummary(resources, kind)) },
          trailingContent = { Text("${kind.inFile}", style = MaterialTheme.typography.titleMedium) },
          colors = groupListItemColors(),
        ) { Text(kind.label) }
      }
    }
  }
  if (preview.identicalToDevice) {
    Text(stringResource(R.string.backup_matches), style = MaterialTheme.typography.bodyMedium)
  }

  if (state.busy) {
    BusyRow(stringResource(R.string.backup_restoring))
    return
  }

  SectionHeader(stringResource(R.string.backup_restore_section))
  Button(onClick = { onApply(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_merge)) }
  Text(
    stringResource(R.string.backup_merge_help),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  OutlinedButton(
    onClick = { confirmReplace = true },
    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    modifier = Modifier.fillMaxWidth(),
  ) { Text(stringResource(R.string.backup_replace)) }
  Text(
    if (removedByReplace > 0) {
      stringResource(R.string.backup_replace_help_removing, itemCount(resources, removedByReplace))
    } else {
      stringResource(R.string.backup_replace_help)
    },
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  if (confirmReplace) {
    AlertDialog(
      onDismissRequest = { confirmReplace = false },
      title = { Text(stringResource(R.string.backup_replace_title)) },
      text = {
        Text(
          if (removedByReplace > 0) {
            stringResource(R.string.backup_replace_text_removing, itemCount(resources, removedByReplace))
          } else {
            stringResource(R.string.backup_replace_text)
          },
        )
      },
      confirmButton = {
        TextButton(onClick = { confirmReplace = false; onApply(true) }) {
          Text(stringResource(R.string.action_replace), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
  }
}

private fun diffSummary(res: Resources, kind: BackupFiles.KindDiff): String {
  val parts = buildList {
    if (kind.new > 0) add(res.getQuantityString(R.plurals.backup_diff_new, kind.new, kind.new))
    if (kind.changed > 0) add(res.getQuantityString(R.plurals.backup_diff_changed, kind.changed, kind.changed))
    if (kind.onlyOnDevice > 0) add(res.getQuantityString(R.plurals.backup_diff_only_here, kind.onlyOnDevice, kind.onlyOnDevice))
  }
  return parts.joinToString(" · ").ifEmpty { res.getString(R.string.backup_diff_same) }
}

private fun itemCount(res: Resources, n: Int): String = res.getQuantityString(R.plurals.item_count, n, n)
