@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.domain.DoseLogEdits
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.formatExact
import com.exaclast.regimen.ui.checkRequiredPositiveDecimal
import com.exaclast.regimen.ui.components.DatePickerField
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.TimePickerField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Corrects a logged dose: when it was taken, how much, the site and a note. A skipped dose only has a
 * note to edit. Pacing isn't re-checked, since this fixes what already happened.
 */
@Composable
fun DoseLogEditorScreen(
  log: DoseLog,
  medication: Medication,
  onClose: () -> Unit,
  onSave: (DoseLog) -> Unit,
) {
  val zone = ZoneId.systemDefault()
  val original = remember(log.id) { Instant.ofEpochMilli(log.actionTimestampMillis).atZone(zone).toLocalDateTime() }
  val taken = log.status == DoseStatus.TAKEN
  var dateIso by rememberSaveable { mutableStateOf(original.toLocalDate().toString()) }
  var hour by rememberSaveable { mutableIntStateOf(original.hour) }
  var minute by rememberSaveable { mutableIntStateOf(original.minute) }
  var amountText by rememberSaveable { mutableStateOf(formatExact(log.amountTaken)) }
  var site by rememberSaveable { mutableStateOf(log.siteUsed.orEmpty()) }
  var notes by rememberSaveable { mutableStateOf(log.notes.orEmpty()) }

  val amountCheck = checkRequiredPositiveDecimal(amountText)
  // Leave the stored time alone unless it was changed, so a note edit doesn't round it to the minute.
  val timeChanged = dateIso != original.toLocalDate().toString() || hour != original.hour || minute != original.minute
  val newTime = runCatching {
    LocalDate.parse(dateIso).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
  }.getOrNull()
  val takenAt = if (timeChanged) newTime else log.actionTimestampMillis
  val inFuture = taken && timeChanged && newTime != null && newTime > System.currentTimeMillis()
  val showSite = taken && (medication.tracksSites || log.siteUsed != null)

  EditorScaffold(
    title = stringResource(R.string.dose_log_edit_title, medication.primaryName),
    onClose = onClose,
    saveEnabled = !taken || (amountCheck.validOrNull() != null && takenAt != null && !inFuture),
    onSave = {
      onSave(
        DoseLogEdits.edit(
          log = log,
          newTakenAtMillis = if (timeChanged) newTime else null,
          amount = amountCheck.validOrNull(),
          site = site,
          notes = notes,
          zone = zone,
        )
      )
    },
  ) {
    Text(
      text = if (taken) stringResource(R.string.dose_status_taken) else log.status.displayName,
      style = MaterialTheme.typography.titleMedium,
    )
    if (taken) {
      DatePickerField(
        label = stringResource(R.string.dose_log_date_taken),
        valueIso = dateIso,
        onValueChange = { dateIso = it },
        allowClear = false,
      )
      TimePickerField(
        label = stringResource(R.string.dose_log_time_taken),
        hour = hour,
        minute = minute,
        onTimeChange = { h, m ->
          hour = h
          minute = m
        },
      )
      if (inFuture) {
        Text(
          stringResource(R.string.log_time_future),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      ValidatedNumberField(
        value = amountText,
        onValueChange = { amountText = it },
        check = amountCheck,
        label = { Text(stringResource(R.string.dose_log_amount, log.amountUnit.symbol)) },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (showSite) {
      OutlinedTextField(
        value = site,
        onValueChange = { site = it },
        label = { Text(stringResource(R.string.field_site)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      if (medication.activeSites.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          medication.activeSites.forEach { option ->
            val selected = option.equals(site.trim(), ignoreCase = true)
            FilterChip(
              selected = selected,
              onClick = { site = if (selected) "" else option },
              label = { Text(option) },
              leadingIcon = if (selected) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
              } else {
                null
              },
            )
          }
        }
      }
    }
    OutlinedTextField(
      value = notes,
      onValueChange = { notes = it },
      label = { Text(stringResource(R.string.field_note)) },
      placeholder = { Text(stringResource(R.string.log_time_note_hint)) },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
