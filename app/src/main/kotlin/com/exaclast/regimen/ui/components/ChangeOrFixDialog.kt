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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.domain.Adjustments.EditMode
import java.time.LocalDate

/**
 * Asked when an edit changes a dose, strength or injection details that doses were already logged with:
 * is it a change from a date (history keeps the old value) or a correction (it was always this)?
 * [what] names what changed, e.g. "the dose" or "strength and injection details".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeOrFixDialog(
  what: String,
  /** The change would clear the NDC (new strength or package), so say so and where to get the new one. */
  clearsNdc: Boolean = false,
  onDismiss: () -> Unit,
  onConfirm: (EditMode) -> Unit,
) {
  var isChange by rememberSaveable { mutableStateOf(true) }
  var dateIso by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
  var note by rememberSaveable { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.change_or_fix_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(stringResource(R.string.change_or_fix_you_changed, what), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          listOf(stringResource(R.string.change_or_fix_from_date), stringResource(R.string.change_or_fix_correction)).forEachIndexed { index, label ->
            SegmentedButton(
              selected = isChange == (index == 0),
              onClick = { isChange = index == 0 },
              shape = SegmentedButtonDefaults.itemShape(index, 2),
              // No check mark: it squeezes the label in a dialog this narrow.
              icon = {},
            ) { Text(label, maxLines = 1) }
          }
        }
        if (isChange) {
          Text(
            stringResource(R.string.change_or_fix_from_date_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          DatePickerField(label = stringResource(R.string.change_or_fix_starting), valueIso = dateIso, onValueChange = { dateIso = it }, allowClear = false)
          if (clearsNdc) {
            Text(
              stringResource(R.string.change_or_fix_ndc_cleared),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.field_note_optional)) },
            placeholder = { Text(stringResource(R.string.change_or_fix_note_hint)) },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          Text(
            stringResource(R.string.change_or_fix_correction_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = { onConfirm(if (isChange) EditMode.Change(dateIso, note) else EditMode.Fix) }) { Text(stringResource(R.string.action_save)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}
