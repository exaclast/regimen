package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.exaclast.regimen.domain.Adjustments
import com.exaclast.regimen.model.DoseAmount
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.formatExact
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.ui.ScheduleText
import com.exaclast.regimen.ui.checkOptionalPositiveDecimal
import com.exaclast.regimen.ui.components.DatePickerField
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.SwitchRow
import java.time.LocalDate

/**
 * Plans or edits one dated change: new doses per schedule, a new default dose or strength, and a note.
 * Blank fields carry the value before it over. Injection details are changed from the medication editor
 * ("Changes on a date"); here they can only be kept or dropped.
 */
@Composable
fun AdjustmentEditorScreen(
  medication: Medication,
  schedules: List<MedicationSchedule>,
  buckets: List<TimeOfDayBucket>,
  initial: MedicationAdjustment?,
  onClose: () -> Unit,
  onSave: (MedicationAdjustment) -> Unit,
  onDelete: (() -> Unit)?,
) {
  var dateIso by rememberSaveable {
    mutableStateOf(initial?.effectiveDateIso ?: LocalDate.now().plusDays(1).toString())
  }
  var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
  var strength by rememberSaveable { mutableStateOf(initial?.strength.orEmpty()) }
  var defaultDoseText by rememberSaveable { mutableStateOf(initial?.defaultDose?.amount?.let(::formatExact).orEmpty()) }
  var doseTexts by rememberSaveable {
    mutableStateOf(
      schedules.map { s -> initial?.scheduleDoses?.firstOrNull { it.scheduleId == s.id }?.amount?.let(::formatExact).orEmpty() },
    )
  }
  var keepInjection by rememberSaveable { mutableStateOf(initial?.injectableDetails != null) }

  // "Before" values: what applies the day before this change, so blanks are clear about what carries over.
  val date = runCatching { LocalDate.parse(dateIso) }.getOrNull()
  val withoutThis = initial?.let { Adjustments.remove(medication, it.id) } ?: medication
  val dayBefore = (date ?: LocalDate.now()).minusDays(1)
  val before = Adjustments.asOf(withoutThis, dayBefore)

  val doseChecks = doseTexts.map { checkOptionalPositiveDecimal(it) }
  val defaultDoseCheck = checkOptionalPositiveDecimal(defaultDoseText)

  fun build(): MedicationAdjustment {
    val doses = schedules.mapIndexedNotNull { i, s ->
      doseChecks[i].validOrNull()?.let { amount ->
        ScheduleDose(s.id, amount, Adjustments.doseOn(withoutThis, s, dayBefore).unit)
      }
    }
    return (initial ?: MedicationAdjustment(effectiveDateIso = dateIso)).copy(
      effectiveDateIso = dateIso,
      note = note.trim().ifEmpty { null },
      strength = strength.trim().ifEmpty { null },
      defaultDose = defaultDoseCheck.validOrNull()?.let { DoseAmount(it, before.defaultDoseUnit) },
      injectableDetails = initial?.injectableDetails?.takeIf { keepInjection },
      scheduleDoses = doses,
    )
  }

  val allValid = date != null && defaultDoseCheck.isValid && doseChecks.all { it.isValid }
  val candidate = build()
  EditorScaffold(
    title = if (initial == null) "Plan a change" else "Edit change",
    onClose = onClose,
    onSave = { onSave(candidate) },
    saveEnabled = allValid && !candidate.isEmpty,
  ) {
    Text(medication.primaryName, style = MaterialTheme.typography.titleMedium)
    DatePickerField(label = "Starting", valueIso = dateIso, onValueChange = { dateIso = it }, allowClear = false)
    Text(
      "Fill in what changes; blank fields keep the value before this date.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    schedules.forEachIndexed { i, schedule ->
      val prior = Adjustments.doseOn(withoutThis, schedule, dayBefore)
      ValidatedNumberField(
        value = doseTexts[i],
        onValueChange = { text -> doseTexts = doseTexts.toMutableList().also { it[i] = text } },
        check = doseChecks[i],
        // The current value is in the label: a placeholder only shows once the field is focused.
        label = { Text("${ScheduleText.describe(schedule, buckets)}: now ${formatNum(prior.amount)} ${prior.unit.symbol}") },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    ValidatedNumberField(
      value = defaultDoseText,
      onValueChange = { defaultDoseText = it },
      check = defaultDoseCheck,
      label = { Text("Default dose: now ${formatNum(before.defaultDoseAmount)} ${before.defaultDoseUnit.symbol}") },
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = strength,
      onValueChange = { strength = it },
      label = { Text("Strength: now ${before.strength}") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    initial?.injectableDetails?.let { details ->
      SwitchRow(
        title = "Keep injection change",
        subtitle = Adjustments.describe(initial.copy(scheduleDoses = emptyList(), strength = null, defaultDose = null)) { null }
          .joinToString().ifEmpty { details.route.displayName },
        checked = keepInjection,
        onCheckedChange = { keepInjection = it },
      )
    }
    if (medication.form.isInjectable) {
      Text(
        "To change injection details from a date, edit the medication and choose \"From a date\" when saving.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    OutlinedTextField(
      value = note,
      onValueChange = { note = it },
      label = { Text("Note") },
      placeholder = { Text("e.g. increased at 1-month visit") },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )

    if (onDelete != null) {
      OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Delete, contentDescription = null)
        Text("  Delete change")
      }
    }
  }
}

