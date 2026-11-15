@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.exaclast.regimen.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.domain.WindowDeletion
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.ui.ScheduleText
import com.exaclast.regimen.ui.components.AdaptiveFieldPair
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SwitchRow
import com.exaclast.regimen.ui.components.TimePickerField
import java.util.UUID

/** Default `(startHour, startMinute, endHour, endMinute)` matching `R.array.window_name_presets`. */
internal data class WindowPresetTimes(val startHour: Int, val startMinute: Int, val endHour: Int, val endMinute: Int)

internal fun presetWindowTimes(presetIndex: Int): WindowPresetTimes? = when (presetIndex) {
  0 -> WindowPresetTimes(7, 0, 10, 0)    // Morning
  1 -> WindowPresetTimes(7, 30, 9, 30)   // Breakfast
  2 -> WindowPresetTimes(11, 30, 13, 30) // Midday
  3 -> WindowPresetTimes(12, 0, 14, 0)   // Lunch
  4 -> WindowPresetTimes(12, 0, 15, 0)   // Afternoon
  5 -> WindowPresetTimes(18, 0, 20, 0)   // Dinner
  6 -> WindowPresetTimes(17, 0, 20, 0)   // Evening
  7 -> WindowPresetTimes(21, 0, 23, 30)  // Bedtime
  8 -> WindowPresetTimes(23, 0, 1, 0)    // Late night
  else -> null
}

fun windowSummary(res: Resources, bucket: TimeOfDayBucket, settings: AppSettings): String {
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
  var timesEdited by rememberSaveable { mutableStateOf(initialBucket != null) }
  var notifEnabled by rememberSaveable { mutableStateOf(initialBucket?.notificationsEnabled ?: true) }
  var alarmAtEnd by rememberSaveable { mutableStateOf(initialBucket?.alarmAtWindowEnd ?: true) }
  var confirmDelete by remember { mutableStateOf(false) }

  val hasUnsavedChanges = name != initialBucket?.name.orEmpty() ||
    startHour != (initialBucket?.hour ?: 8) ||
    startMinute != (initialBucket?.minute ?: 0) ||
    endHour != (initialBucket?.endHour ?: 10) ||
    endMinute != (initialBucket?.endMinute ?: 0) ||
    notifEnabled != (initialBucket?.notificationsEnabled ?: true) ||
    alarmAtEnd != (initialBucket?.alarmAtWindowEnd ?: true)

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
    hasUnsavedChanges = hasUnsavedChanges,
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
        stringArrayResource(R.array.window_name_presets).forEachIndexed { index, preset ->
          FilterChip(
            selected = name.equals(preset, ignoreCase = true),
            onClick = {
              name = preset
              if (!timesEdited) {
                presetWindowTimes(index)?.let { (sh, sm, eh, em) ->
                  startHour = sh
                  startMinute = sm
                  endHour = eh
                  endMinute = em
                }
              }
            },
            label = { Text(preset) },
          )
        }
      }
    }
    AdaptiveFieldPair(
      first = { fieldModifier ->
        TimePickerField(
          label = stringResource(R.string.window_starts),
          hour = startHour,
          minute = startMinute,
          onTimeChange = { h, m -> startHour = h; startMinute = m; timesEdited = true },
          modifier = fieldModifier,
        )
      },
      second = { fieldModifier ->
        TimePickerField(
          label = stringResource(R.string.field_ends),
          hour = endHour,
          minute = endMinute,
          onTimeChange = { h, m -> endHour = h; endMinute = m; timesEdited = true },
          modifier = fieldModifier,
        )
      },
    )
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
