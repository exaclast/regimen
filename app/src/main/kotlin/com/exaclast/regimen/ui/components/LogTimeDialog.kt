package com.exaclast.regimen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Asks whether a dose was taken now or at another date and time, and takes an optional note. Confirms
 * with null for "now", or the chosen moment; a time in the future can't be logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTimeDialog(
  title: String,
  doseLabel: String,
  defaultDateIso: String,
  startWithOtherTime: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (takenAtMillis: Long?, note: String?) -> Unit,
) {
  val initial = remember { LocalDateTime.now() }
  var otherTime by rememberSaveable { mutableStateOf(startWithOtherTime) }
  var dateIso by rememberSaveable { mutableStateOf(defaultDateIso) }
  var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
  var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
  var note by rememberSaveable { mutableStateOf("") }
  val takenAt = runCatching {
    LocalDate.parse(dateIso).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  }.getOrNull()
  val inFuture = takenAt != null && takenAt > System.currentTimeMillis()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(doseLabel, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          listOf("Now", "Other time").forEachIndexed { index, label ->
            SegmentedButton(
              selected = otherTime == (index == 1),
              onClick = { otherTime = index == 1 },
              shape = SegmentedButtonDefaults.itemShape(index, 2),
            ) { Text(label) }
          }
        }
        if (otherTime) {
          DatePickerField(label = "Date", valueIso = dateIso, onValueChange = { dateIso = it }, allowClear = false)
          TimePickerField(label = "Time", hour = hour, minute = minute, onTimeChange = { h, m -> hour = h; minute = m })
          if (inFuture) {
            Text(
              "That time hasn't happened yet.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          label = { Text("Note (optional)") },
          placeholder = { Text("e.g. headache, 6/10") },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(if (otherTime) takenAt else null, note.trim().takeIf { it.isNotEmpty() }) },
        enabled = !otherTime || (takenAt != null && !inFuture),
      ) { Text("Log dose") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
