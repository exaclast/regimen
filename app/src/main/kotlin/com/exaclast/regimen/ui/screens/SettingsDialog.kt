package com.exaclast.regimen.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import java.util.UUID
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exaclast.regimen.data.DrugBarcodeScanner
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.ThemeMode
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.launch

enum class SettingsTab(val label: String, val icon: ImageVector) {
  WINDOWS("Windows", Icons.Default.Schedule),
  ALARMS("Alarms", Icons.Default.NotificationsActive),
  GENERAL("Preferences", Icons.Default.Tune),
  DATA("Backup", Icons.Default.Storage),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
  settings: AppSettings,
  buckets: List<TimeOfDayBucket>,
  onSaveSettings: (AppSettings) -> Unit,
  onSaveBucket: (TimeOfDayBucket) -> Unit,
  onDeleteBucket: (String) -> Unit = {},
  onReorderBuckets: (List<TimeOfDayBucket>) -> Unit = {},
  onResetBucketsToDefault: () -> Unit = {},
  onExportJson: () -> String,
  onImportJson: (String) -> Result<Int>,
  onClearAllData: () -> Unit,
  onResetSampleData: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.WINDOWS) }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var importJsonInput by remember { mutableStateOf("") }
  var showImportField by remember { mutableStateOf(false) }
  var showConfirmClearDialog by remember { mutableStateOf(false) }
  var showConfirmResetSampleDialog by remember { mutableStateOf(false) }
  var editingBucket by remember { mutableStateOf<TimeOfDayBucket?>(null) }
  var isAddingBucket by remember { mutableStateOf(false) }
  var bucketToDelete by remember { mutableStateOf<TimeOfDayBucket?>(null) }
  var showResetBucketsConfirm by remember { mutableStateOf(false) }

  if (editingBucket != null) {
    BackHandler { editingBucket = null }
  } else if (isAddingBucket) {
    BackHandler { isAddingBucket = false }
  } else if (showImportField) {
    BackHandler { showImportField = false }
  }

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

        PrimaryTabRow(
          selectedTabIndex = selectedTab.ordinal,
          modifier = Modifier.fillMaxWidth(),
        ) {
          SettingsTab.entries.forEach { tab ->
            Tab(
              selected = selectedTab == tab,
              onClick = { selectedTab = tab },
              text = { Text(tab.label) },
              icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
          }
        }

        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          when (selectedTab) {
            SettingsTab.WINDOWS -> {
            // 4. Time-of-Day Dose Windows Section
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
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                  ) {
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
                  FilledTonalButton(
                    onClick = { isAddingBucket = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                  ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Window")
                  }
                }

                Text(
                  text = "Group medications into flexible morning, midday, evening, or custom windows. Tap edit to customize hours, or use arrows to reorder:",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val sortedBuckets = buckets.sortedBy { it.sortOrder }
                sortedBuckets.forEachIndexed { index, bucket ->
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
                          Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                          ) {
                            Text(
                              text = bucket.name,
                              style = MaterialTheme.typography.bodyLarge,
                              fontWeight = FontWeight.Bold,
                            )
                            if (bucket.crossesMidnight) {
                              Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.extraSmall,
                              ) {
                                Text(
                                  text = "🌙 Overnight",
                                  style = MaterialTheme.typography.labelSmall,
                                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                              }
                            }
                          }
                          Text(
                            text = bucket.formattedTime(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                          )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                          IconButton(
                            onClick = {
                              if (index > 0) {
                                val reordered = sortedBuckets.toMutableList()
                                val prev = reordered[index - 1]
                                reordered[index - 1] = bucket
                                reordered[index] = prev
                                onReorderBuckets(reordered)
                              }
                            },
                            enabled = index > 0,
                          ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(18.dp))
                          }
                          IconButton(
                            onClick = {
                              if (index < sortedBuckets.size - 1) {
                                val reordered = sortedBuckets.toMutableList()
                                val next = reordered[index + 1]
                                reordered[index + 1] = bucket
                                reordered[index] = next
                                onReorderBuckets(reordered)
                              }
                            },
                            enabled = index < sortedBuckets.size - 1,
                          ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(18.dp))
                          }
                          IconButton(onClick = { editingBucket = bucket }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Window", modifier = Modifier.size(18.dp))
                          }
                          IconButton(
                            onClick = { bucketToDelete = bucket },
                            enabled = sortedBuckets.size > 1,
                          ) {
                            Icon(
                              Icons.Default.Delete,
                              contentDescription = "Delete Window",
                              tint = if (sortedBuckets.size > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                              modifier = Modifier.size(18.dp),
                            )
                          }
                        }
                      }

                      HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                      ) {
                        Text(
                          text = "Window start notification",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                          checked = bucket.notificationsEnabled,
                          enabled = settings.notificationsEnabled,
                          onCheckedChange = {
                            onSaveBucket(bucket.copy(notificationsEnabled = it))
                          },
                        )
                      }

                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                      ) {
                        Text(
                          text = "Urgent alarm at ${bucket.formattedEndTime()} if unlogged",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                          checked = bucket.alarmAtWindowEnd,
                          enabled = settings.notificationsEnabled && settings.alarmsEnabled && bucket.notificationsEnabled,
                          onCheckedChange = {
                            onSaveBucket(bucket.copy(alarmAtWindowEnd = it))
                          },
                        )
                      }
                    }
                  }
                }

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.End,
                ) {
                  TextButton(onClick = { showResetBucketsConfirm = true }) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset Windows to Clinical Presets")
                  }
                }
              }
            }

            }
            SettingsTab.ALARMS -> {
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

                // System Permissions Checklist (Notifications, Exact Alarms & Battery)
                PermissionsChecklistSection()

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

            }
            SettingsTab.GENERAL -> {
            // 2. Patient Profile & Label Scanning Section
            Card(
              modifier = Modifier.fillMaxWidth(),
              colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              ),
            ) {
              Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "Patient Profile & Label Scanning",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }

                Text(
                  text = "Enter your name as printed on prescription bottles. The on-device label scanner uses this to ignore your name so it never gets parsed as the drug or brand name.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                var patientNameInput by remember(settings.patientName) { mutableStateOf(settings.patientName) }
                OutlinedTextField(
                  value = patientNameInput,
                  onValueChange = {
                    patientNameInput = it
                    onSaveSettings(settings.copy(patientName = it.trim()))
                  },
                  label = { Text("Your Name on Prescriptions") },
                  placeholder = { Text("e.g. John Doe") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }

            // 3. Workflow & Appearance Section
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

            }
            SettingsTab.DATA -> {
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

  // Add/Edit Dose Window Dialog
  if (isAddingBucket || editingBucket != null) {
    val target = editingBucket
    DoseWindowEditorDialog(
      initialBucket = target,
      existingBucketCount = buckets.size,
      onDismiss = {
        isAddingBucket = false
        editingBucket = null
      },
      onSave = { savedBucket ->
        onSaveBucket(savedBucket)
        isAddingBucket = false
        editingBucket = null
      },
    )
  }

  // Delete Window Confirmation
  bucketToDelete?.let { bucket ->
    AlertDialog(
      onDismissRequest = { bucketToDelete = null },
      icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
      title = { Text("Delete Window: ${bucket.name}?") },
      text = {
        Text(
          "Medications scheduled for this window will be safely reassigned to your remaining windows. Any active notifications or alarms for this window will be cancelled."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onDeleteBucket(bucket.id)
            bucketToDelete = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
          Text("Delete Window")
        }
      },
      dismissButton = {
        TextButton(onClick = { bucketToDelete = null }) {
          Text("Cancel")
        }
      },
    )
  }

  // Reset Windows to Presets Confirmation
  if (showResetBucketsConfirm) {
    AlertDialog(
      onDismissRequest = { showResetBucketsConfirm = false },
      title = { Text("Reset Windows to Clinical Presets?") },
      text = {
        Text("This will restore the 4 default windows (Morning, Afternoon, Evening, Bedtime) and reassign scheduled doses to them.")
      },
      confirmButton = {
        Button(
          onClick = {
            showResetBucketsConfirm = false
            onResetBucketsToDefault()
          },
        ) {
          Text("Reset Windows")
        }
      },
      dismissButton = {
        TextButton(onClick = { showResetBucketsConfirm = false }) {
          Text("Cancel")
        }
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoseWindowEditorDialog(
  initialBucket: TimeOfDayBucket?,
  existingBucketCount: Int,
  onDismiss: () -> Unit,
  onSave: (TimeOfDayBucket) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf(initialBucket?.name.orEmpty()) }
  val initStartHour = initialBucket?.hour ?: 8
  val initStartMinute = initialBucket?.minute ?: 0
  val initEndHour = initialBucket?.endHour ?: 10
  val initEndMinute = initialBucket?.endMinute ?: 0

  // 12-hour clock breakdown
  var startHour12 by rememberSaveable {
    mutableIntStateOf(
      when {
        initStartHour == 0 -> 12
        initStartHour > 12 -> initStartHour - 12
        else -> initStartHour
      }
    )
  }
  var startMinute by rememberSaveable { mutableIntStateOf(initStartMinute) }
  var startIsPm by rememberSaveable { mutableStateOf(initStartHour >= 12) }

  var endHour12 by rememberSaveable {
    mutableIntStateOf(
      when {
        initEndHour == 0 -> 12
        initEndHour > 12 -> initEndHour - 12
        else -> initEndHour
      }
    )
  }
  var endMinute by rememberSaveable { mutableIntStateOf(initEndMinute) }
  var endIsPm by rememberSaveable { mutableStateOf(initEndHour >= 12) }

  var notifEnabled by rememberSaveable { mutableStateOf(initialBucket?.notificationsEnabled ?: true) }
  var alarmAtEnd by rememberSaveable { mutableStateOf(initialBucket?.alarmAtWindowEnd ?: true) }

  fun to24(h12: Int, isPm: Boolean): Int {
    return when {
      h12 == 12 && !isPm -> 0
      h12 == 12 && isPm -> 12
      isPm -> h12 + 12
      else -> h12
    }
  }

  val computedStart24 = to24(startHour12, startIsPm)
  val computedEnd24 = to24(endHour12, endIsPm)
  val formattedStart = TimeOfDayBucket.formatClockTime(computedStart24, startMinute)
  val formattedEnd = TimeOfDayBucket.formatClockTime(computedEnd24, endMinute)
  val crossesMidnight = (computedEnd24 * 60 + endMinute) < (computedStart24 * 60 + startMinute)

  val durationMinutes = if (crossesMidnight) {
    (24 * 60 - (computedStart24 * 60 + startMinute)) + (computedEnd24 * 60 + endMinute)
  } else {
    (computedEnd24 * 60 + endMinute) - (computedStart24 * 60 + startMinute)
  }
  val durationHours = durationMinutes / 60
  val durationRemainingMins = durationMinutes % 60
  val durationStr = buildString {
    if (durationHours > 0) append("${durationHours}h ")
    if (durationRemainingMins > 0 || durationHours == 0) append("${durationRemainingMins}m")
  }.trim()

  val isValid = name.isNotBlank() && durationMinutes > 0

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(if (initialBucket == null) "Add Dose Window" else "Edit Dose Window")
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Window Name") },
          placeholder = { Text("e.g. Morning, Breakfast, Evening") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Quick Presets:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            listOf(
              "Morning", "Breakfast", "Midday", "Lunch",
              "Afternoon", "Dinner", "Evening", "Bedtime", "Late Night",
            ).forEach { presetName ->
              FilterChip(
                selected = name.equals(presetName, ignoreCase = true),
                onClick = { name = presetName },
                label = { Text(presetName, style = MaterialTheme.typography.labelSmall) },
              )
            }
          }
        }

        HorizontalDivider()

        // Start Time Picker Row
        Text(
          text = "Window Start Time:",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = startHour12.toString(),
            onValueChange = { text ->
              text.toIntOrNull()?.let { startHour12 = it.coerceIn(1, 12) }
            },
            label = { Text("Hour (1–12)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = startMinute.toString().padStart(2, '0'),
            onValueChange = { text ->
              text.toIntOrNull()?.let { startMinute = it.coerceIn(0, 59) }
            },
            label = { Text("Min (0–59)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            FilterChip(
              selected = !startIsPm,
              onClick = { startIsPm = false },
              label = { Text("AM") },
              modifier = Modifier.weight(1f),
            )
            FilterChip(
              selected = startIsPm,
              onClick = { startIsPm = true },
              label = { Text("PM") },
              modifier = Modifier.weight(1f),
            )
          }
        }

        // End Time Picker Row
        Text(
          text = "Window End Time:",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = endHour12.toString(),
            onValueChange = { text ->
              text.toIntOrNull()?.let { endHour12 = it.coerceIn(1, 12) }
            },
            label = { Text("Hour (1–12)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = endMinute.toString().padStart(2, '0'),
            onValueChange = { text ->
              text.toIntOrNull()?.let { endMinute = it.coerceIn(0, 59) }
            },
            label = { Text("Min (0–59)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            FilterChip(
              selected = !endIsPm,
              onClick = { endIsPm = false },
              label = { Text("AM") },
              modifier = Modifier.weight(1f),
            )
            FilterChip(
              selected = endIsPm,
              onClick = { endIsPm = true },
              label = { Text("PM") },
              modifier = Modifier.weight(1f),
            )
          }
        }

        // Window Summary Preview
        Surface(
          color = if (crossesMidnight) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text(
              text = "$formattedStart – $formattedEnd ($durationStr)",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = if (crossesMidnight) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (crossesMidnight) {
              Text(
                text = "🌙 Overnight window — crosses midnight into the next morning.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
          }
        }

        HorizontalDivider()

        // Notification & Alarm Preferences
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "Pin notification at $formattedStart",
            style = MaterialTheme.typography.bodySmall,
          )
          Switch(
            checked = notifEnabled,
            onCheckedChange = { notifEnabled = it },
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "Sound urgent alarm at $formattedEnd if unlogged",
            style = MaterialTheme.typography.bodySmall,
          )
          Switch(
            checked = alarmAtEnd,
            enabled = notifEnabled,
            onCheckedChange = { alarmAtEnd = it },
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val bucket = TimeOfDayBucket(
            id = initialBucket?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            hour = computedStart24,
            minute = startMinute,
            endHour = computedEnd24,
            endMinute = endMinute,
            sortOrder = initialBucket?.sortOrder ?: existingBucketCount,
            notificationsEnabled = notifEnabled,
            alarmAtWindowEnd = alarmAtEnd,
          )
          onSave(bucket)
        },
        enabled = isValid,
      ) {
        Text("Save Window")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
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

@Composable
fun StartupPermissionsDialog(
  onDismiss: () -> Unit,
  onPermissionsChanged: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var refreshTick by remember { mutableIntStateOf(0) }

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

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Permissions & System Setup") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = "Configure notification reliability and on-device AI features. If you skip any optional permissions now, you will not be nagged again on future launches (accessible anytime in Settings):",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionsChecklistSection(
          onPermissionStateChanged = {
            refreshTick++
            onPermissionsChanged()
          },
        )
      }
    },
    confirmButton = {
      Button(onClick = onDismiss) {
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
  var nanoStatus by remember { mutableStateOf<Int?>(null) }
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

  LaunchedEffect(refreshTick) {
    nanoStatus = DrugBarcodeScanner.checkNanoStatus()
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

    val isNanoReady = nanoStatus == FeatureStatus.AVAILABLE
    val nanoSubtitle = when (nanoStatus) {
      FeatureStatus.AVAILABLE ->
        "Active — Gemini Nano is downloaded and ready for on-device label parsing"
      FeatureStatus.DOWNLOADING ->
        nanoProgressMessage ?: "Downloading via AICore... (Connect to unmetered Wi-Fi and charger if paused)"
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
      title = "4. On-Device AI (Gemini Nano / AICore)",
      subtitle = nanoSubtitle,
      isGranted = isNanoReady,
      actionLabel = nanoActionLabel,
      onActionClick = if (nanoActionLabel != null) {
        {
          if (nanoStatus == FeatureStatus.DOWNLOADING) {
            DrugBarcodeScanner.openAiCoreSettings(context)
          } else if (nanoStatus == FeatureStatus.DOWNLOADABLE) {
            coroutineScope.launch {
              nanoProgressMessage = "Initiating download via AICore..."
              DrugBarcodeScanner.startNanoDownload(
                onProgress = { msg -> nanoProgressMessage = msg }
              ).fold(
                onSuccess = {
                  nanoStatus = DrugBarcodeScanner.checkNanoStatus()
                  onPermissionStateChanged()
                },
                onFailure = { err ->
                  nanoProgressMessage = err.localizedMessage ?: "Download failed"
                  DrugBarcodeScanner.openAiCoreSettings(context)
                }
              )
            }
          }
        }
      } else null,
    )
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
