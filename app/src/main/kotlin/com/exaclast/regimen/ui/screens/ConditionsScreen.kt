package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.ConditionStatus

@Composable
fun ConditionsScreen(
  snapshot: RegimenSnapshot,
  selectedConditionId: String?,
  onSelectCondition: (String?) -> Unit,
  onSaveCondition: (Condition) -> Unit,
  onDeleteCondition: (String) -> Unit,
  onOpenMedication: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var editingCondition by remember { mutableStateOf<Condition?>(null) }
  var isCreatingNew by remember { mutableStateOf(false) }

  val activeCondition = remember(snapshot.conditions, selectedConditionId) {
    snapshot.conditions.find { it.id == selectedConditionId }
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isTwoPane = maxWidth >= 700.dp

    if (isTwoPane) {
      val effectiveCondition = activeCondition ?: snapshot.conditions.firstOrNull()
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Column(
          modifier = Modifier
            .weight(0.9f)
            .fillMaxHeight(),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Conditions & Indications (${snapshot.conditions.size})",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Button(onClick = { isCreatingNew = true }) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Add Condition")
            }
          }
          Spacer(modifier = Modifier.height(12.dp))
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
          ) {
            items(snapshot.conditions, key = { it.id }) { cond ->
              ConditionSummaryCard(
                condition = cond,
                linkedMedCount = snapshot.medications.count { it.indicationConditionId == cond.id },
                isSelected = cond.id == effectiveCondition?.id,
                onClick = { onSelectCondition(cond.id) },
              )
            }
          }
        }

        Box(
          modifier = Modifier
            .weight(1.1f)
            .fillMaxHeight(),
        ) {
          if (effectiveCondition != null) {
            ConditionDetailPane(
              condition = effectiveCondition,
              snapshot = snapshot,
              showBackButton = false,
              onBack = {},
              onEdit = { editingCondition = effectiveCondition },
              onDelete = { onDeleteCondition(effectiveCondition.id) },
              onOpenMedication = onOpenMedication,
            )
          } else {
            Card(modifier = Modifier.fillMaxSize()) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select or add a condition / diagnosis.")
              }
            }
          }
        }
      }
    } else {
      if (activeCondition != null) {
        ConditionDetailPane(
          condition = activeCondition,
          snapshot = snapshot,
          showBackButton = true,
          onBack = { onSelectCondition(null) },
          onEdit = { editingCondition = activeCondition },
          onDelete = { onDeleteCondition(activeCondition.id) },
          onOpenMedication = onOpenMedication,
          modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        )
      } else {
        Box(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 16.dp, vertical = 12.dp),
          ) {
            Text(
              text = "Conditions & Indications (${snapshot.conditions.size})",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(10.dp),
              contentPadding = PaddingValues(bottom = 88.dp),
            ) {
              items(snapshot.conditions, key = { it.id }) { cond ->
                ConditionSummaryCard(
                  condition = cond,
                  linkedMedCount = snapshot.medications.count { it.indicationConditionId == cond.id },
                  isSelected = false,
                  onClick = { onSelectCondition(cond.id) },
                )
              }
            }
          }

          FloatingActionButton(
            onClick = { isCreatingNew = true },
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(16.dp),
          ) {
            Icon(Icons.Default.Add, contentDescription = "Add Condition")
          }
        }
      }
    }
  }

  if (isCreatingNew || editingCondition != null) {
    ConditionEditorDialog(
      initialCondition = editingCondition,
      onDismiss = {
        isCreatingNew = false
        editingCondition = null
      },
      onSave = { saved ->
        onSaveCondition(saved)
        isCreatingNew = false
        editingCondition = null
      },
    )
  }
}

@Composable
private fun ConditionSummaryCard(
  condition: Condition,
  linkedMedCount: Int,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainerLow
      },
    ),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = condition.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = condition.status.displayName,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
        )
      }
      condition.details?.takeIf { it.isNotBlank() }?.let {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "$linkedMedCount medication(s) linked for this indication",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ConditionDetailPane(
  condition: Condition,
  snapshot: RegimenSnapshot,
  showBackButton: Boolean,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onOpenMedication: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val linkedMeds = snapshot.medications.filter { it.indicationConditionId == condition.id }

  Card(
    modifier = modifier.fillMaxSize(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
          ) {
            if (showBackButton) {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
              }
            }
            Column {
              Text(
                text = condition.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = condition.status.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }

          Row {
            IconButton(onClick = onEdit) {
              Icon(Icons.Default.Edit, contentDescription = "Edit condition")
            }
            IconButton(onClick = onDelete) {
              Icon(
                Icons.Default.Delete,
                contentDescription = "Delete condition",
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }

      item {
        SectionDetailCard(title = "Diagnosis & Details") {
          DetailRow("Status", condition.status.displayName)
          DetailRow("Diagnosed Date", condition.diagnosedDateIso ?: "Not specified")
          DetailRow("Details / Notes", condition.details ?: "None")
        }
      }

      item {
        SectionDetailCard(title = "Medications Linked for this Indication (${linkedMeds.size})") {
          if (linkedMeds.isEmpty()) {
            Text(
              text = "No medications currently linked to this condition.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              linkedMeds.forEach { med ->
                Surface(
                  onClick = { onOpenMedication(med.id) },
                  shape = MaterialTheme.shapes.small,
                  color = MaterialTheme.colorScheme.surfaceContainer,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                      text = "${med.displayTitle} • ${med.strength}",
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      text = "${med.form.displayName} • Default: ${med.formattedDefaultDose()}",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionEditorDialog(
  initialCondition: Condition?,
  onDismiss: () -> Unit,
  onSave: (Condition) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf(initialCondition?.name.orEmpty()) }
  var details by rememberSaveable { mutableStateOf(initialCondition?.details.orEmpty()) }
  var status by rememberSaveable {
    mutableStateOf(initialCondition?.status ?: ConditionStatus.ACTIVE)
  }
  var diagnosedDateIso by rememberSaveable {
    mutableStateOf(initialCondition?.diagnosedDateIso.orEmpty())
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (initialCondition == null) "Add Condition" else "Edit Condition") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Condition / Diagnosis Name *") },
          placeholder = { Text("e.g. ADHD, Hypogonadism") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Text("Status", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ConditionStatus.entries.forEach { s ->
            FilterChip(
              selected = status == s,
              onClick = { status = s },
              label = { Text(s.displayName) },
            )
          }
        }
        OutlinedTextField(
          value = diagnosedDateIso,
          onValueChange = { diagnosedDateIso = it },
          label = { Text("Diagnosed Date (YYYY-MM-DD)") },
          placeholder = { Text("Optional") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = details,
          onValueChange = { details = it },
          label = { Text("Details / Notes") },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            Condition(
              id = initialCondition?.id ?: java.util.UUID.randomUUID().toString(),
              name = name.trim(),
              details = details.trim().takeIf { it.isNotBlank() },
              status = status,
              diagnosedDateIso = diagnosedDateIso.trim().takeIf { it.isNotBlank() },
            )
          )
        },
        enabled = name.isNotBlank(),
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

