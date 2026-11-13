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
import androidx.compose.ui.unit.dp
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
  onDismiss: () -> Unit,
  onConfirm: (EditMode) -> Unit,
) {
  var isChange by rememberSaveable { mutableStateOf(true) }
  var dateIso by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
  var note by rememberSaveable { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Change or correction?") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("You changed $what.", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          listOf("From a date", "Correction").forEachIndexed { index, label ->
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
            "Doses before the date keep the old value. Pick a future date to plan a step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          DatePickerField(label = "Starting", valueIso = dateIso, onValueChange = { dateIso = it }, allowClear = false)
          OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            placeholder = { Text("e.g. increased at follow-up") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          Text(
            "It was always this: the value in effect today is replaced, with no history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      Button(onClick = { onConfirm(if (isChange) EditMode.Change(dateIso, note) else EditMode.Fix) }) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
