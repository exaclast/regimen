package com.exaclast.regimen.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.ThemeMode
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.notifications.DoseNotificationManager

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
  settings: AppSettings,
  buckets: List<TimeOfDayBucket>,
  onSaveSettings: (AppSettings) -> Unit,
  onSaveBucket: (TimeOfDayBucket) -> Unit,
  onExportJson: () -> String,
  onImportJson: (String) -> Result<Int>,
  onClearAllData: () -> Unit,
  onResetSampleData: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var importJsonInput by remember { mutableStateOf("") }
  var showImportField by remember { mutableStateOf(false) }
  var showConfirmClearDialog by remember { mutableStateOf(false) }
  var showConfirmResetSampleDialog by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Header Bar
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column {
            Text(
              text = "Settings & Dose Windows",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = "Notifications, alarms, appearance, time windows & data backup",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close Settings")
          }
        }

        HorizontalDivider()

        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // 1. Global Notifications & Alarms Section
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = if (settings.notificationsEnabled) {
                    Icons.Default.NotificationsActive
                  } else {
                    Icons.Default.NotificationsOff
                  },
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Notifications & Escalating Alarms",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
              }

              // Exact Alarm status banner
              val exactAllowed = DoseNotificationManager.canScheduleExactAlarms(context)
              Surface(
                shape = MaterialTheme.shapes.small,
                color = if (exactAllowed) {
                  MaterialTheme.colorScheme.secondaryContainer
                } else {
                  MaterialTheme.colorScheme.errorContainer
                },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = if (exactAllowed) {
                        "Exact Doze-Piercing Timing Active"
                      } else {
                        "Exact Alarm Permission Needed"
                      },
                      style = MaterialTheme.typography.labelLarge,
                      fontWeight = FontWeight.Bold,
                      color = if (exactAllowed) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                      } else {
                        MaterialTheme.colorScheme.onErrorContainer
                      },
                    )
                    Text(
                      text = if (exactAllowed) {
                        "Window start persistent notifications & end-of-window alarms wake the device on the exact minute."
                      } else {
                        "Grant Exact Alarms so Android Doze mode does not delay window notifications."
                      },
                      style = MaterialTheme.typography.bodySmall,
                      color = if (exactAllowed) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                      } else {
                        MaterialTheme.colorScheme.onErrorContainer
                      },
                    )
                  }
                  if (!exactAllowed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                      onClick = { DoseNotificationManager.openExactAlarmSettings(context) }
                    ) {
                      Text("Grant")
                    }
                  }
                }
              }

              // Master Notifications Toggle
              SettingToggleRow(
                title = "Enable Dose Window Notifications",
                subtitle = "Master switch for all persistent window-start notifications and alarms",
                checked = settings.notificationsEnabled,
                onCheckedChange = {
                  onSaveSettings(settings.copy(notificationsEnabled = it))
                },
              )

              // Master End-of-Window Alarm Toggle
              SettingToggleRow(
                title = "Enable End-of-Window Ringing Alarms",
                subtitle = "Ring an insistent alarm at the end of a window if no action was taken",
                checked = settings.alarmsEnabled,
                enabled = settings.notificationsEnabled,
                onCheckedChange = {
                  onSaveSettings(settings.copy(alarmsEnabled = it))
                },
              )

              // Discreet Notifications Toggle
              SettingToggleRow(
                title = "Discreet Notifications (Hide Drug Names)",
                subtitle = "Hide medication names and injection sites on the lock screen and notification shade",
                checked = settings.discreetNotifications,
                enabled = settings.notificationsEnabled,
                onCheckedChange = {
                  onSaveSettings(settings.copy(discreetNotifications = it))
                },
              )

              HorizontalDivider()

              // Snooze Duration Selector
              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = "Default Snooze Duration",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  text = "Used by the 1-tap Snooze button on notifications and Today dose cards",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  AppSettings.SNOOZE_PRESETS.forEach { mins ->
                    FilterChip(
                      selected = settings.snoozeMinutes == mins,
                      onClick = { onSaveSettings(settings.copy(snoozeMinutes = mins)) },
                      label = { Text("$mins min") },
                    )
                  }
                }
              }
            }
          }

          // 2. Dose Logging & Appearance Section
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Default.Palette,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Workflow & Appearance",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
              }

              SettingToggleRow(
                title = "Confirm Before Taking Scheduled Doses",
                subtitle = "Ask for confirmation (or site selection) instead of logging immediately on 1 tap",
                checked = settings.confirmBeforeTake,
                onCheckedChange = {
                  onSaveSettings(settings.copy(confirmBeforeTake = it))
                },
              )

              HorizontalDivider()

              Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = "Theme Mode",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  ThemeMode.entries.forEach { mode ->
                    FilterChip(
                      selected = settings.themeMode == mode,
                      onClick = { onSaveSettings(settings.copy(themeMode = mode)) },
                      label = { Text(mode.displayName) },
                    )
                  }
                }
              }

              SettingToggleRow(
                title = "Material You Dynamic Color",
                subtitle = "Adapt app palette to your Android system wallpaper (Android 12+)",
                checked = settings.dynamicColor,
                onCheckedChange = {
                  onSaveSettings(settings.copy(dynamicColor = it))
                },
              )
            }
          }

          // 3. Time-of-Day Dose Windows Section
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Default.Schedule,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Time-of-Day Dose Windows",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
              }

              Text(
                text = "Configure start and end times (24-hour 0–23) for each daily dose window:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              buckets.sortedBy { it.sortOrder }.forEach { bucket ->
                var startHourText by remember(bucket) { mutableStateOf(bucket.hour.toString()) }
                var startMinText by remember(bucket) {
                  mutableStateOf(bucket.minute.toString().padStart(2, '0'))
                }
                var endHourText by remember(bucket) { mutableStateOf(bucket.endHour.toString()) }
                var endMinText by remember(bucket) {
                  mutableStateOf(bucket.endMinute.toString().padStart(2, '0'))
                }
                var notifEnabled by remember(bucket) { mutableStateOf(bucket.notificationsEnabled) }
                var alarmAtEnd by remember(bucket) { mutableStateOf(bucket.alarmAtWindowEnd) }

                Surface(
                  shape = MaterialTheme.shapes.small,
                  color = MaterialTheme.colorScheme.surface,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(
                          text = bucket.name,
                          style = MaterialTheme.typography.bodyLarge,
                          fontWeight = FontWeight.Bold,
                        )
                        Text(
                          text = bucket.formattedTime(),
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.primary,
                        )
                      }
                      Switch(
                        checked = notifEnabled,
                        enabled = settings.notificationsEnabled,
                        onCheckedChange = {
                          notifEnabled = it
                          onSaveBucket(bucket.copy(notificationsEnabled = it))
                        },
                      )
                    }

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                      OutlinedTextField(
                        value = startHourText,
                        onValueChange = {
                          startHourText = it
                          val h = it.toIntOrNull()?.coerceIn(0, 23)
                          if (h != null) onSaveBucket(bucket.copy(hour = h))
                        },
                        label = { Text("Start Hr") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                      )
                      OutlinedTextField(
                        value = startMinText,
                        onValueChange = {
                          startMinText = it
                          val m = it.toIntOrNull()?.coerceIn(0, 59)
                          if (m != null) onSaveBucket(bucket.copy(minute = m))
                        },
                        label = { Text("Min") },
                        singleLine = true,
                        modifier = Modifier.weight(0.85f),
                      )
                      OutlinedTextField(
                        value = endHourText,
                        onValueChange = {
                          endHourText = it
                          val h = it.toIntOrNull()?.coerceIn(0, 23)
                          if (h != null) onSaveBucket(bucket.copy(endHour = h))
                        },
                        label = { Text("End Hr") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                      )
                      OutlinedTextField(
                        value = endMinText,
                        onValueChange = {
                          endMinText = it
                          val m = it.toIntOrNull()?.coerceIn(0, 59)
                          if (m != null) onSaveBucket(bucket.copy(endMinute = m))
                        },
                        label = { Text("Min") },
                        singleLine = true,
                        modifier = Modifier.weight(0.85f),
                      )
                    }

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Text(
                        text = "Ring alarm at ${bucket.formattedEndTime()} if unlogged",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                      Switch(
                        checked = alarmAtEnd,
                        enabled = settings.notificationsEnabled && settings.alarmsEnabled && notifEnabled,
                        onCheckedChange = {
                          alarmAtEnd = it
                          onSaveBucket(bucket.copy(alarmAtWindowEnd = it))
                        },
                      )
                    }
                  }
                }
              }
            }
          }

          // 4. Data Backup & Reset Section
          Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Default.Storage,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Backup, Restore & Database Reset",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
              }

              Text(
                text = "Export all medications, schedules, dose history, and settings as JSON, or clear sample data to start fresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = {
                    val jsonStr = onExportJson()
                    val clipboard =
                      context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Regimen Backup JSON", jsonStr))
                    statusMessage = "Copied full JSON backup to clipboard (${jsonStr.length} chars)."
                  },
                ) {
                  Text("Copy Backup JSON")
                }

                OutlinedButton(
                  onClick = { showImportField = !showImportField },
                ) {
                  Text(if (showImportField) "Hide Import" else "Import JSON")
                }
              }

              if (showImportField) {
                OutlinedTextField(
                  value = importJsonInput,
                  onValueChange = { importJsonInput = it },
                  label = { Text("Paste Regimen Backup JSON") },
                  modifier = Modifier.fillMaxWidth(),
                  maxLines = 5,
                )
                Button(
                  onClick = {
                    val res = onImportJson(importJsonInput)
                    statusMessage = res.fold(
                      onSuccess = { count ->
                        showImportField = false
                        importJsonInput = ""
                        "Successfully imported $count medications & settings."
                      },
                      onFailure = { err -> "Import failed: ${err.message}" },
                    )
                  },
                  enabled = importJsonInput.isNotBlank(),
                ) {
                  Text("Restore from JSON")
                }
              }

              HorizontalDivider()

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                OutlinedButton(
                  onClick = { showConfirmClearDialog = true },
                  colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                  ),
                  modifier = Modifier.weight(1f),
                ) {
                  Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Clear All Meds & Logs")
                }

                OutlinedButton(
                  onClick = { showConfirmResetSampleDialog = true },
                  modifier = Modifier.weight(1f),
                ) {
                  Icon(
                    Icons.Default.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Reset Demo Data")
                }
              }

              statusMessage?.let { msg ->
                Text(
                  text = msg,
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.SemiBold,
                )
              }
            }
          }
        }
      }
    }
  }

  if (showConfirmClearDialog) {
    AlertDialog(
      onDismissRequest = { showConfirmClearDialog = false },
      title = { Text("Clear All Medications & Dose History?") },
      text = {
        Text(
          "This will remove all medications, schedules, prescribers, conditions, and dose logs (including pre-seeded sample data) so you can enter your personal regimen from a clean slate."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showConfirmClearDialog = false
            onClearAllData()
            statusMessage = "Cleared all medications, schedules, and dose history."
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
          ),
        ) {
          Text("Clear All Data")
        }
      },
      dismissButton = {
        TextButton(onClick = { showConfirmClearDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }

  if (showConfirmResetSampleDialog) {
    AlertDialog(
      onDismissRequest = { showConfirmResetSampleDialog = false },
      title = { Text("Reset to Demo Data?") },
      text = {
        Text(
          "This will replace current medications, schedules, and logs with the initial sample dataset."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showConfirmResetSampleDialog = false
            onResetSampleData()
            statusMessage = "Restored sample medications, schedules, and logs."
          },
        ) {
          Text("Restore Demo Data")
        }
      },
      dismissButton = {
        TextButton(onClick = { showConfirmResetSampleDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun SettingToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(end = 12.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) {
          MaterialTheme.colorScheme.onSurface
        } else {
          MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        },
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) {
          MaterialTheme.colorScheme.onSurfaceVariant
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        },
      )
    }
    Switch(
      checked = checked,
      enabled = enabled,
      onCheckedChange = onCheckedChange,
    )
  }
}
