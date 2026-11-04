package com.exaclast.regimen.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.exaclast.regimen.model.Prescriber

@Composable
fun PrescribersScreen(
  snapshot: RegimenSnapshot,
  selectedPrescriberId: String?,
  onSelectPrescriber: (String?) -> Unit,
  onSavePrescriber: (Prescriber) -> Unit,
  onDeletePrescriber: (String) -> Unit,
  onOpenMedication: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var editingPrescriber by remember { mutableStateOf<Prescriber?>(null) }
  var isCreatingNew by remember { mutableStateOf(false) }

  val activePrescriber = remember(snapshot.prescribers, selectedPrescriberId) {
    snapshot.prescribers.find { it.id == selectedPrescriberId }
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isTwoPane = maxWidth >= 700.dp

    if (isTwoPane) {
      val effectivePrescriber = activePrescriber ?: snapshot.prescribers.firstOrNull()
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
              text = "Prescribers (${snapshot.prescribers.size})",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Button(onClick = { isCreatingNew = true }) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Add Prescriber")
            }
          }
          Spacer(modifier = Modifier.height(12.dp))
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
          ) {
            items(snapshot.prescribers, key = { it.id }) { doc ->
              PrescriberSummaryCard(
                prescriber = doc,
                linkedMedCount = snapshot.medications.count { it.prescriberId == doc.id },
                isSelected = doc.id == effectivePrescriber?.id,
                onClick = { onSelectPrescriber(doc.id) },
              )
            }
          }
        }

        Box(
          modifier = Modifier
            .weight(1.1f)
            .fillMaxHeight(),
        ) {
          if (effectivePrescriber != null) {
            PrescriberDetailPane(
              prescriber = effectivePrescriber,
              snapshot = snapshot,
              showBackButton = false,
              onBack = {},
              onEdit = { editingPrescriber = effectivePrescriber },
              onDelete = { onDeletePrescriber(effectivePrescriber.id) },
              onOpenMedication = onOpenMedication,
            )
          } else {
            Card(modifier = Modifier.fillMaxSize()) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select or add a prescriber.")
              }
            }
          }
        }
      }
    } else {
      if (activePrescriber != null) {
        BackHandler { onSelectPrescriber(null) }
        PrescriberDetailPane(
          prescriber = activePrescriber,
          snapshot = snapshot,
          showBackButton = true,
          onBack = { onSelectPrescriber(null) },
          onEdit = { editingPrescriber = activePrescriber },
          onDelete = { onDeletePrescriber(activePrescriber.id) },
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
              text = "Prescribers (${snapshot.prescribers.size})",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(10.dp),
              contentPadding = PaddingValues(bottom = 88.dp),
            ) {
              items(snapshot.prescribers, key = { it.id }) { doc ->
                PrescriberSummaryCard(
                  prescriber = doc,
                  linkedMedCount = snapshot.medications.count { it.prescriberId == doc.id },
                  isSelected = false,
                  onClick = { onSelectPrescriber(doc.id) },
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
            Icon(Icons.Default.Add, contentDescription = "Add Prescriber")
          }
        }
      }
    }
  }

  if (isCreatingNew || editingPrescriber != null) {
    PrescriberEditorDialog(
      initialPrescriber = editingPrescriber,
      onDismiss = {
        isCreatingNew = false
        editingPrescriber = null
      },
      onSave = { saved ->
        onSavePrescriber(saved)
        isCreatingNew = false
        editingPrescriber = null
      },
    )
  }
}

@Composable
private fun PrescriberSummaryCard(
  prescriber: Prescriber,
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
          text = prescriber.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
        )
        if (prescriber.isPcp) {
          AssistChip(
            onClick = onClick,
            label = { Text("PCP") },
            leadingIcon = {
              Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp))
            },
          )
        }
      }
      prescriber.specialty?.takeIf { it.isNotBlank() }?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Medium,
        )
      }
      prescriber.practice?.takeIf { it.isNotBlank() }?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "$linkedMedCount linked medication(s)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun PrescriberDetailPane(
  prescriber: Prescriber,
  snapshot: RegimenSnapshot,
  showBackButton: Boolean,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onOpenMedication: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val linkedMeds = snapshot.medications.filter { it.prescriberId == prescriber.id }

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
                text = prescriber.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
              )
              if (prescriber.isPcp) {
                Text(
                  text = "Primary Care Provider (PCP)",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }

          Row {
            IconButton(onClick = onEdit) {
              Icon(Icons.Default.Edit, contentDescription = "Edit prescriber")
            }
            IconButton(onClick = onDelete) {
              Icon(
                Icons.Default.Delete,
                contentDescription = "Delete prescriber",
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }

      item {
        SectionDetailCard(title = "Provider & Contact Information") {
          DetailRow("Specialty", prescriber.specialty ?: "Not specified")
          DetailRow("Practice / Clinic", prescriber.practice ?: "Not specified")
          DetailRow("Phone", prescriber.phone ?: "Not specified")
          DetailRow("Email", prescriber.email ?: "Not specified")
          DetailRow("Primary Care (PCP)", if (prescriber.isPcp) "Yes" else "No")
          DetailRow("Start of Care", prescriber.careStartDateIso ?: "Not specified")
          DetailRow("End of Care", prescriber.careEndDateIso ?: "Ongoing")
          prescriber.notes?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Notes", it)
          }
        }
      }

      item {
        SectionDetailCard(title = "Prescribed Medications (${linkedMeds.size})") {
          if (linkedMeds.isEmpty()) {
            Text(
              text = "No medications currently linked to this prescriber.",
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

@Composable
private fun PrescriberEditorDialog(
  initialPrescriber: Prescriber?,
  onDismiss: () -> Unit,
  onSave: (Prescriber) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf(initialPrescriber?.name.orEmpty()) }
  var specialty by rememberSaveable { mutableStateOf(initialPrescriber?.specialty.orEmpty()) }
  var practice by rememberSaveable { mutableStateOf(initialPrescriber?.practice.orEmpty()) }
  var phone by rememberSaveable { mutableStateOf(initialPrescriber?.phone.orEmpty()) }
  var email by rememberSaveable { mutableStateOf(initialPrescriber?.email.orEmpty()) }
  var isPcp by rememberSaveable { mutableStateOf(initialPrescriber?.isPcp ?: false) }
  var startDate by rememberSaveable { mutableStateOf(initialPrescriber?.careStartDateIso.orEmpty()) }
  var endDate by rememberSaveable { mutableStateOf(initialPrescriber?.careEndDateIso.orEmpty()) }
  var notes by rememberSaveable { mutableStateOf(initialPrescriber?.notes.orEmpty()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (initialPrescriber == null) "Add Prescriber" else "Edit Prescriber") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Doctor / Provider Name *") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = specialty,
          onValueChange = { specialty = it },
          label = { Text("Specialty") },
          placeholder = { Text("e.g. Psychiatry, Endocrinology") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = practice,
          onValueChange = { practice = it },
          label = { Text("Practice / Hospital") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = phone,
          onValueChange = { phone = it },
          label = { Text("Phone Number") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = email,
          onValueChange = { email = it },
          label = { Text("Email") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Primary Care Provider (PCP)", style = MaterialTheme.typography.bodyMedium)
          Switch(checked = isPcp, onCheckedChange = { isPcp = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = startDate,
            onValueChange = { startDate = it },
            label = { Text("Start of Care") },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          OutlinedTextField(
            value = endDate,
            onValueChange = { endDate = it },
            label = { Text("End of Care") },
            placeholder = { Text("Optional") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
        }
        OutlinedTextField(
          value = notes,
          onValueChange = { notes = it },
          label = { Text("Notes") },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            Prescriber(
              id = initialPrescriber?.id ?: java.util.UUID.randomUUID().toString(),
              name = name.trim(),
              specialty = specialty.trim().takeIf { it.isNotBlank() },
              practice = practice.trim().takeIf { it.isNotBlank() },
              phone = phone.trim().takeIf { it.isNotBlank() },
              email = email.trim().takeIf { it.isNotBlank() },
              isPcp = isPcp,
              careStartDateIso = startDate.trim().takeIf { it.isNotBlank() },
              careEndDateIso = endDate.trim().takeIf { it.isNotBlank() },
              notes = notes.trim().takeIf { it.isNotBlank() },
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

