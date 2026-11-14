package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.exaclast.regimen.R
import com.exaclast.regimen.domain.Adjustments
import com.exaclast.regimen.model.DoseAmount
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationAdjustment
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleDose
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.formatExact
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.ui.NumberInput
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
  // Blank here is not "keep": a new strength clears the NDC (see below), so only a typed one is shown.
  var ndcText by rememberSaveable { mutableStateOf(initial?.ndc11.orEmpty()) }
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

  // A different strength has a different NDC, so it's blank from this date unless one is entered.
  val clearsNdc = strength.isNotBlank() && !before.ndc11.isNullOrBlank() && ndcText.isBlank() &&
    strength.trim() != before.strength.trim()

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
      ndc11 = when {
        ndcText.isNotBlank() -> Medication.normalizeNdc11(ndcText)
        clearsNdc -> ""
        // Kept from before: an injection change that cleared it stays cleared while that change is kept.
        initial?.ndc11 == "" && keepInjection && initial.injectableDetails != null -> ""
        else -> null
      },
      defaultDose = defaultDoseCheck.validOrNull()?.let { DoseAmount(it, before.defaultDoseUnit) },
      injectableDetails = initial?.injectableDetails?.takeIf { keepInjection },
      scheduleDoses = doses,
    )
  }

  val allValid = date != null && defaultDoseCheck.isValid && doseChecks.all { it.isValid }
  val candidate = build()
  EditorScaffold(
    title = stringResource(if (initial == null) R.string.adjust_plan_title else R.string.adjust_edit_title),
    onClose = onClose,
    onSave = { onSave(candidate) },
    saveEnabled = allValid && !candidate.isEmpty,
  ) {
    Text(medication.primaryName, style = MaterialTheme.typography.titleMedium)
    DatePickerField(label = stringResource(R.string.change_or_fix_starting), valueIso = dateIso, onValueChange = { dateIso = it }, allowClear = false)
    Text(
      stringResource(R.string.adjust_help),
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
        label = { Text(stringResource(R.string.adjust_dose_now, ScheduleText.describe(schedule, buckets), formatNum(prior.amount), prior.unit.symbol)) },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    ValidatedNumberField(
      value = defaultDoseText,
      onValueChange = { defaultDoseText = it },
      check = defaultDoseCheck,
      label = { Text(stringResource(R.string.adjust_default_dose_now, formatNum(before.defaultDoseAmount), before.defaultDoseUnit.symbol)) },
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = strength,
      onValueChange = { strength = it },
      label = { Text(stringResource(R.string.adjust_strength_now, before.strength)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = ndcText,
      onValueChange = { ndcText = NumberInput.filterNdc(it) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      label = { Text(stringResource(R.string.adjust_ndc_label)) },
      placeholder = {
        Text(if (clearsNdc) stringResource(R.string.adjust_ndc_blank) else before.ndc11.orEmpty().ifEmpty { stringResource(R.string.value_none) })
      },
      supportingText = {
        Text(
          if (clearsNdc) {
            stringResource(R.string.adjust_ndc_help_cleared)
          } else {
            stringResource(R.string.adjust_ndc_help)
          },
        )
      },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    initial?.injectableDetails?.let { details ->
      SwitchRow(
        title = stringResource(R.string.adjust_keep_injection),
        subtitle = Adjustments.describe(initial.copy(scheduleDoses = emptyList(), strength = null, ndc11 = null, defaultDose = null)) { null }
          .joinToString().ifEmpty { details.route.displayName },
        checked = keepInjection,
        onCheckedChange = { keepInjection = it },
      )
    }
    if (medication.form.isInjectable) {
      Text(
        stringResource(R.string.adjust_injection_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    OutlinedTextField(
      value = note,
      onValueChange = { note = it },
      label = { Text(stringResource(R.string.field_note)) },
      placeholder = { Text(stringResource(R.string.adjust_note_hint)) },
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

