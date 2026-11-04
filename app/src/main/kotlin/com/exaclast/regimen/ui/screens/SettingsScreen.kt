@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.exaclast.regimen.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exaclast.regimen.data.DrugBarcodeScanner
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.ThemeMode
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.notifications.DoseNotificationStage
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.SwitchRow
import com.exaclast.regimen.ui.components.TimePickerField
import com.exaclast.regimen.ui.components.groupListItemColors
import com.google.mlkit.genai.common.FeatureStatus
import java.util.UUID
import kotlinx.coroutines.launch

/** Item shown in Settings → Diagnostics for sending test notifications. */
data class DiagnosticsTarget(val label: String)

@Composable
fun SettingsScreen(
  settings: AppSettings,
  buckets: List<TimeOfDayBucket>,
  diagnosticsTarget: DiagnosticsTarget?,
  onBack: () -> Unit,
  onSaveSettings: (AppSettings) -> Unit,
  onOpenWindow: (String?) -> Unit,
  onResetWindows: () -> Unit,
  onTestNotification: (DoseNotificationStage) -> Unit,
  onExportJson: () -> String,
  onImportJson: (String) -> Result<Int>,
  onClearAllData: () -> Unit,
  onResetSampleData: () -> Unit,
  onPermissionsChanged: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var showImport by rememberSaveable { mutableStateOf(false) }
  var importText by rememberSaveable { mutableStateOf("") }
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
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
        SectionHeader("Reminders")
        GroupedColumn {
          GroupItem(0, 5) {
            SwitchRow(
              title = "Dose reminders",
              subtitle = "Notify when a dose window opens",
              checked = settings.notificationsEnabled,
              onCheckedChange = { onSaveSettings(settings.copy(notificationsEnabled = it)) },
            )
          }
          GroupItem(1, 5) {
            SwitchRow(
              title = "End-of-window alarm",
              subtitle = "Ring if a dose is still unlogged when its window ends",
              checked = settings.alarmsEnabled,
              enabled = settings.notificationsEnabled,
              onCheckedChange = { onSaveSettings(settings.copy(alarmsEnabled = it)) },
            )
          }
          GroupItem(2, 5) {
            SwitchRow(
              title = "Discreet notifications",
              subtitle = "Hide medication names and sites on the lock screen",
              checked = settings.discreetNotifications,
              enabled = settings.notificationsEnabled,
              onCheckedChange = { onSaveSettings(settings.copy(discreetNotifications = it)) },
            )
          }
          GroupItem(3, 5) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text("Snooze length", style = MaterialTheme.typography.bodyLarge)
              Spacer(Modifier.height(8.dp))
              val presets = AppSettings.SNOOZE_PRESETS
              SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                presets.forEachIndexed { i, mins ->
                  SegmentedButton(
                    selected = settings.snoozeMinutes == mins,
                    onClick = { onSaveSettings(settings.copy(snoozeMinutes = mins)) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = presets.size),
                    icon = {},
                    label = { Text("$mins min", maxLines = 1) },
                  )
                }
              }
            }
          }
          GroupItem(4, 5) {
            SwitchRow(
              title = "Confirm before logging",
              subtitle = "Ask before logging a scheduled dose",
              checked = settings.confirmBeforeTake,
              onCheckedChange = { onSaveSettings(settings.copy(confirmBeforeTake = it)) },
            )
          }
        }
      }

      item(key = "windows") {
        SectionHeader("Dose windows") {
          TextButton(onClick = { onOpenWindow(null) }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add")
          }
        }
        val ordered = buckets.chronological()
        val count = ordered.size + 1
        GroupedColumn {
          ordered.forEachIndexed { i, bucket ->
            GroupItem(index = i, count = count, onClick = { onOpenWindow(bucket.id) }) {
              ListItem(
                supportingContent = { Text(windowSummary(bucket, settings)) },
                trailingContent = {
                  Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                colors = groupListItemColors(),
              ) { Text(bucket.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
          }
          GroupItem(index = ordered.size, count = count, onClick = { confirmResetWindows = true }) {
            ListItem(
              supportingContent = { Text("Morning, Afternoon, Evening, Bedtime") },
              colors = groupListItemColors(),
            ) { Text("Restore default windows") }
          }
        }
      }

      item(key = "permissions") {
        SectionHeader("Permissions")
        PermissionsChecklistSection(
          onPermissionStateChanged = onPermissionsChanged,
        )
      }

      item(key = "appearance") {
        SectionHeader("Appearance")
        GroupedColumn {
          GroupItem(0, 2) {
            Column(modifier = Modifier.padding(16.dp)) {
              Text("Theme", style = MaterialTheme.typography.bodyLarge)
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
                          ThemeMode.SYSTEM -> "System"
                          ThemeMode.LIGHT -> "Light"
                          ThemeMode.DARK -> "Dark"
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
              title = "Wallpaper colors",
              subtitle = "Match your device's color palette (Android 12+)",
              checked = settings.dynamicColor,
              onCheckedChange = { onSaveSettings(settings.copy(dynamicColor = it)) },
            )
          }
        }
      }

      item(key = "scanning") {
        SectionHeader("Label scanning")
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
                text = "This device does not support on-device Gemini Nano label scanning capabilities.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NanoAiCard(
              nanoStatus = nanoStatus,
              nanoProgressMessage = nanoProgressMessage,
              onDownloadClick = {
                coroutineScope.launch {
                  nanoProgressMessage = "Initiating download via AICore..."
                  DrugBarcodeScanner.startNanoDownload(
                    onProgress = { msg -> nanoProgressMessage = msg }
                  ).fold(
                    onSuccess = {
                      nanoStatus = DrugBarcodeScanner.checkNanoStatus()
                      onPermissionsChanged()
                    },
                    onFailure = { err ->
                      nanoProgressMessage = err.localizedMessage ?: "Download failed"
                      DrugBarcodeScanner.openAiCoreSettings(context)
                    }
                  )
                }
              },
              onSettingsClick = {
                DrugBarcodeScanner.openAiCoreSettings(context)
              },
            )

            FdaLookupCard(
              allowFdaLookup = settings.allowFdaLookup,
              onAllowFdaLookupChange = { onSaveSettings(settings.copy(allowFdaLookup = it)) },
            )

            PatientNameField(
              savedName = settings.patientName,
              onSave = { onSaveSettings(settings.copy(patientName = it)) },
            )
          }
        }
      }

      item(key = "diagnostics") {
        SectionHeader("Diagnostics")
        val enabled = diagnosticsTarget != null && settings.notificationsEnabled
        val supporting = when {
          !settings.notificationsEnabled -> "Turn on dose reminders first"
          diagnosticsTarget == null -> "No scheduled dose today to test with"
          else -> "Uses ${diagnosticsTarget.label}"
        }
        GroupedColumn {
          GroupItem(0, 2, onClick = if (enabled) ({ onTestNotification(DoseNotificationStage.WINDOW_START_PERSISTENT) }) else null) {
            ListItem(
              supportingContent = { Text(supporting) },
              colors = groupListItemColors(),
            ) { Text("Send a test reminder") }
          }
          GroupItem(1, 2, onClick = if (enabled && settings.alarmsEnabled) ({ onTestNotification(DoseNotificationStage.WINDOW_END_ALARM) }) else null) {
            ListItem(
              supportingContent = { Text(if (!settings.alarmsEnabled) "End-of-window alarm is off" else supporting) },
              colors = groupListItemColors(),
            ) { Text("Test the end-of-window alarm") }
          }
        }
      }

      item(key = "data") {
        SectionHeader("Backup")
        GroupedColumn {
          GroupItem(0, 2, onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Regimen backup", onExportJson()))
            statusMessage = "Backup copied to the clipboard."
          }) {
            ListItem(
              supportingContent = { Text("Medications, schedules, history and settings as JSON") },
              colors = groupListItemColors(),
            ) { Text("Copy backup") }
          }
          GroupItem(1, 2, onClick = { showImport = !showImport }) {
            ListItem(
              supportingContent = { Text("Paste a backup to merge it into this device") },
              colors = groupListItemColors(),
            ) { Text("Restore from backup") }
          }
        }
        if (showImport) {
          Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = importText,
              onValueChange = { importText = it },
              label = { Text("Backup JSON") },
              minLines = 3,
              maxLines = 6,
              modifier = Modifier.fillMaxWidth(),
            )
            Button(
              onClick = {
                statusMessage = onImportJson(importText).fold(
                  onSuccess = { count ->
                    showImport = false
                    importText = ""
                    "Restored ${medicationCount(count)} and settings."
                  },
                  onFailure = { "Couldn't restore: ${it.message}" },
                )
              },
              enabled = importText.isNotBlank(),
              modifier = Modifier.align(Alignment.End),
            ) { Text("Restore") }
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
        SectionHeader("Danger zone")
        GroupedColumn {
          GroupItem(0, 2, onClick = { confirmDemo = true }) {
            ListItem(
              supportingContent = { Text("Replace everything with sample medications") },
              colors = groupListItemColors(),
            ) { Text("Load demo data") }
          }
          GroupItem(1, 2, onClick = { confirmClear = true }) {
            ListItem(
              supportingContent = { Text("Medications, prescribers, conditions and dose history") },
              colors = groupListItemColors(),
            ) { Text("Delete all data", color = MaterialTheme.colorScheme.error) }
          }
        }
      }
    }
  }

  if (confirmResetWindows) {
    ConfirmDialog(
      title = "Restore default windows?",
      text = "Replaces your windows with Morning, Afternoon, Evening and Bedtime. Schedules move to the defaults.",
      confirmLabel = "Restore",
      onConfirm = {
        confirmResetWindows = false
        onResetWindows()
      },
      onDismiss = { confirmResetWindows = false },
    )
  }
  if (confirmDemo) {
    ConfirmDialog(
      title = "Load demo data?",
      text = "This replaces your medications, schedules and dose history with sample data.",
      confirmLabel = "Load demo data",
      destructive = true,
      onConfirm = {
        confirmDemo = false
        onResetSampleData()
        statusMessage = "Demo data loaded."
      },
      onDismiss = { confirmDemo = false },
    )
  }
  if (confirmClear) {
    ConfirmDialog(
      title = "Delete all data?",
      text = "This permanently removes every medication, prescriber, condition and dose log. Copy a backup first if you might need it.",
      confirmLabel = "Delete everything",
      destructive = true,
      onConfirm = {
        confirmClear = false
        onClearAllData()
        statusMessage = "All data deleted."
      },
      onDismiss = { confirmClear = false },
    )
  }
}

private fun windowSummary(bucket: TimeOfDayBucket, settings: AppSettings): String {
  val reminder = when {
    !settings.notificationsEnabled || !bucket.notificationsEnabled -> "Reminders off"
    settings.alarmsEnabled && bucket.alarmAtWindowEnd -> "Reminder and alarm"
    else -> "Reminder only"
  }
  val overnight = if (bucket.crossesMidnight) " (overnight)" else ""
  return "${bucket.formattedTime()}$overnight · $reminder"
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
      label = { Text("Your name as printed on labels") },
      supportingText = {
        Text("Used by on-device Gemini Nano as a filter so your printed name is never misread as a medication brand or generic name. Stays entirely on-device; only required if you plan to use label scanning.")
      },
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

/** Preset names offered when creating a window. */
private val WINDOW_NAME_PRESETS = listOf(
  "Morning", "Breakfast", "Midday", "Lunch", "Afternoon", "Dinner", "Evening", "Bedtime", "Late night",
)

@Composable
fun DoseWindowEditorScreen(
  initialBucket: TimeOfDayBucket?,
  canDelete: Boolean,
  globalNotificationsEnabled: Boolean,
  globalAlarmsEnabled: Boolean,
  onClose: () -> Unit,
  onSave: (TimeOfDayBucket) -> Unit,
  onDelete: () -> Unit,
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
  val durationText = buildString {
    val h = durationMinutes / 60
    val m = durationMinutes % 60
    if (h > 0) append("$h h")
    if (m > 0) append(if (h > 0) " $m min" else "$m min")
  }
  val timeError = if (durationMinutes <= 0) "Start and end can't be the same time" else null

  EditorScaffold(
    title = if (initialBucket == null) "Add window" else "Edit window",
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
      label = { Text("Name *") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    if (initialBucket == null) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WINDOW_NAME_PRESETS.forEach { preset ->
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
        label = "Starts",
        hour = startHour,
        minute = startMinute,
        onTimeChange = { h, m -> startHour = h; startMinute = m },
        modifier = Modifier.weight(1f),
      )
      TimePickerField(
        label = "Ends",
        hour = endHour,
        minute = endMinute,
        onTimeChange = { h, m -> endHour = h; endMinute = m },
        modifier = Modifier.weight(1f),
      )
    }
    Text(
      text = timeError ?: if (crossesMidnight) "$durationText · ends the next morning" else durationText,
      style = MaterialTheme.typography.bodyMedium,
      color = if (timeError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GroupedColumn {
      GroupItem(0, 2) {
        SwitchRow(
          title = "Reminder when the window opens",
          subtitle = if (!globalNotificationsEnabled) "Dose reminders are off in Settings" else null,
          checked = notifEnabled,
          enabled = globalNotificationsEnabled,
          onCheckedChange = { notifEnabled = it },
        )
      }
      GroupItem(1, 2) {
        SwitchRow(
          title = "Alarm if still unlogged at the end",
          subtitle = if (!globalAlarmsEnabled) "End-of-window alarms are off in Settings" else null,
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
      ) { Text("Delete window") }
    }
  }

  if (confirmDelete && initialBucket != null) {
    ConfirmDialog(
      title = "Delete ${initialBucket.name}?",
      text = "Medications scheduled in this window move to another window, and its reminders are cancelled.",
      confirmLabel = "Delete",
      destructive = true,
      onConfirm = {
        confirmDelete = false
        onDelete()
      },
      onDismiss = { confirmDelete = false },
    )
  }
}

@Composable
fun StartupPermissionsDialog(
  settings: AppSettings,
  onSaveSettings: (AppSettings) -> Unit,
  onDismiss: () -> Unit,
  onPermissionsChanged: () -> Unit,
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

  var patientNameText by rememberSaveable(settings.patientName) {
    mutableStateOf(settings.patientName)
  }

  AlertDialog(
    onDismissRequest = {
      onSaveSettings(settings.copy(patientName = patientNameText.trim()))
      onDismiss()
    },
    title = { Text("Permissions & System Setup") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          text = if (isNanoSupported) {
            "Configure notification reliability and optional label scanning features. You can change these anytime in Settings:"
          } else {
            "Configure notification reliability for your dose reminders and alarms. You can change these anytime in Settings:"
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Dose Reminders & Timing",
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

        if (isNanoSupported) {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
              text = "Label Scanning Setup (Optional)",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = "On-device AI extracts medication details from prescription bottle labels without sending images to the cloud.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            NanoAiCard(
              nanoStatus = nanoStatus,
              nanoProgressMessage = nanoProgressMessage,
              onDownloadClick = {
                coroutineScope.launch {
                  nanoProgressMessage = "Initiating download via AICore..."
                  DrugBarcodeScanner.startNanoDownload(
                    onProgress = { msg -> nanoProgressMessage = msg }
                  ).fold(
                    onSuccess = {
                      nanoStatus = DrugBarcodeScanner.checkNanoStatus()
                      refreshTick++
                      onPermissionsChanged()
                    },
                    onFailure = { err ->
                      nanoProgressMessage = err.localizedMessage ?: "Download failed"
                      DrugBarcodeScanner.openAiCoreSettings(context)
                    }
                  )
                }
              },
              onSettingsClick = {
                DrugBarcodeScanner.openAiCoreSettings(context)
              },
            )

            FdaLookupCard(
              allowFdaLookup = settings.allowFdaLookup,
              onAllowFdaLookupChange = { onSaveSettings(settings.copy(allowFdaLookup = it)) },
            )

            Surface(
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.surface,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text = "Prescription Label Name (Optional)",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text = "Used by on-device Gemini Nano as a filter so your printed name is never misread as a medication brand or generic name. This stays entirely on your device and is only required if you plan to use label scanning.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                  value = patientNameText,
                  onValueChange = {
                    patientNameText = it
                    onSaveSettings(settings.copy(patientName = it.trim()))
                  },
                  label = { Text("Your name as printed on labels") },
                  placeholder = { Text("e.g. Jane Doe") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = {
        onSaveSettings(settings.copy(patientName = patientNameText.trim()))
        onDismiss()
      }) {
        Text(
          if (allCoreGranted && batteryUnrestricted) {
            "All Set"
          } else if (allCoreGranted) {
            "Continue"
          } else {
            "Done for Now"
          }
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
      title = "1. Notifications",
      subtitle = if (notifGranted) {
        "Granted — persistent window notifications & alarms can be displayed"
      } else {
        "Required to display active dose window notifications and alarms"
      },
      isGranted = notifGranted,
      actionLabel = "Allow",
      onActionClick = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
          DoseNotificationManager.openAppNotificationSettings(context)
        }
      },
    )

    PermissionItemCard(
      title = "2. Alarms & Reminders (Exact Timing)",
      subtitle = if (exactAlarmsGranted) {
        "Granted — window start & end triggers fire on the exact minute"
      } else {
        "Required so Android Doze mode does not delay window notifications"
      },
      isGranted = exactAlarmsGranted,
      actionLabel = "Allow",
      onActionClick = {
        DoseNotificationManager.openExactAlarmSettings(context)
      },
    )

    PermissionItemCard(
      title = "3. Unrestricted Background Battery",
      subtitle = if (batteryUnrestricted) {
        "Granted — exempt from background battery throttling"
      } else {
        "Recommended so overnight/morning windows wake reliably"
      },
      isGranted = batteryUnrestricted,
      actionLabel = "Exempt",
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
    FeatureStatus.AVAILABLE ->
      "Gemini Nano is downloaded and ready for on-device label parsing"
    FeatureStatus.DOWNLOADING ->
      nanoProgressMessage ?: "Downloading model via AICore (~1.5 GB)..."
    FeatureStatus.DOWNLOADABLE ->
      nanoProgressMessage ?: "Ready for download (~1.5 GB) — requires Wi-Fi and power/charging"
    FeatureStatus.UNAVAILABLE ->
      "Not supported on this hardware (manual OpenFDA search works everywhere)"
    else -> "Checking on-device AI status..."
  }
  val nanoActionLabel = when (nanoStatus) {
    FeatureStatus.DOWNLOADING -> "AICore Settings"
    FeatureStatus.DOWNLOADABLE -> "Download"
    else -> null
  }

  PermissionItemCard(
    title = "On-Device AI (Gemini Nano / AICore)",
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
          text = "OpenFDA Medication Lookup",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = if (allowFdaLookup) {
            MaterialTheme.colorScheme.onSecondaryContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          text = "Allow medication lookup via FDA API. Medication data (such as drug name, strength, or NDC) will be sent to official OpenFDA servers for autofill and verification. No personally identifiable information (PII) is included.",
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
            contentDescription = "Granted",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "Active",
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
