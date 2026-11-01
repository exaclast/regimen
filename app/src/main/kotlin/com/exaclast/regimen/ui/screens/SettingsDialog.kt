package com.exaclast.regimen.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import com.exaclast.regimen.model.TimeOfDayBucket

@Composable
fun SettingsDialog(
  buckets: List<TimeOfDayBucket>,
  onSaveBucket: (TimeOfDayBucket) -> Unit,
  onExportJson: () -> String,
  onImportJson: (String) -> Result<Int>,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var importJsonInput by remember { mutableStateOf("") }
  var showImportField by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Dose Windows, Escalating Alarms & Backup") },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = "Customize Time-of-Day Dose Windows",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "At Window Start, a persistent notification is pinned with 1-tap Take / Snooze / Skip actions. If no action is taken by Window End, an alarm rings.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        buckets.sortedBy { it.sortOrder }.forEach { bucket ->
          var startHourText by remember(bucket) { mutableStateOf(bucket.hour.toString()) }
          var startMinText by remember(bucket) { mutableStateOf(bucket.minute.toString().padStart(2, '0')) }
          var endHourText by remember(bucket) { mutableStateOf(bucket.endHour.toString()) }
          var endMinText by remember(bucket) { mutableStateOf(bucket.endMinute.toString().padStart(2, '0')) }
          var notifEnabled by remember(bucket) { mutableStateOf(bucket.notificationsEnabled) }
          var alarmAtEnd by remember(bucket) { mutableStateOf(bucket.alarmAtWindowEnd) }

          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
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
                  style = MaterialTheme.typography.labelMedium,
                )
                Switch(
                  checked = alarmAtEnd,
                  onCheckedChange = {
                    alarmAtEnd = it
                    onSaveBucket(bucket.copy(alarmAtWindowEnd = it))
                  },
                  enabled = notifEnabled,
                )
              }
            }
          }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(
          text = "Offline Backup & Sync Readiness",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "All records use UUIDs and sync-ready timestamps. Export to JSON or restore from JSON anytime.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              val exported = onExportJson()
              val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("Regimen Backup", exported))
              statusMessage = "Copied full JSON backup (${exported.length} bytes) to clipboard!"
            }
          ) {
            Text("Copy Backup JSON")
          }
          OutlinedButton(onClick = { showImportField = !showImportField }) {
            Text(if (showImportField) "Hide Restore" else "Restore JSON")
          }
        }

        if (showImportField) {
          OutlinedTextField(
            value = importJsonInput,
            onValueChange = { importJsonInput = it },
            label = { Text("Paste Backup JSON") },
            modifier = Modifier
              .fillMaxWidth()
              .height(120.dp),
          )
          Button(
            onClick = {
              val res = onImportJson(importJsonInput)
              statusMessage = res.fold(
                onSuccess = { count -> "Restored backup ($count medications)!" },
                onFailure = { err -> "Failed to restore: ${err.message}" },
              )
            },
            enabled = importJsonInput.isNotBlank(),
          ) {
            Text("Apply Restore")
          }
        }

        statusMessage?.let { msg ->
          Text(
            text = msg,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Done")
      }
    },
  )
}
