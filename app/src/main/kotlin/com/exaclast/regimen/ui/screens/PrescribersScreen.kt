@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.exaclast.regimen.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.Prescriber
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.DatePickerField
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.InfoRow
import com.exaclast.regimen.ui.components.ListContentPadding
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.SwitchRow
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.components.groupListItemColors

@Composable
fun PrescriberListScreen(
  snapshot: RegimenSnapshot,
  onOpenPrescriber: (String) -> Unit,
  onAddPrescriber: () -> Unit,
) {
  val listState = rememberLazyListState()
  val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
  val prescribers = snapshot.prescribers
  Scaffold(
    topBar = { TopAppBar(title = { Text("Prescribers") }) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onAddPrescriber,
        expanded = fabExpanded,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text("Add prescriber") },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    if (prescribers.isEmpty()) {
      EmptyListMessage("No prescribers yet.", Modifier.padding(padding))
      return@Scaffold
    }
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
      contentPadding = ListContentPadding,
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      itemsIndexed(prescribers, key = { _, p -> p.id }) { index, doc ->
        val medCount = snapshot.medications.count { it.prescriberId == doc.id }
        GroupItem(index = index, count = prescribers.size, onClick = { onOpenPrescriber(doc.id) }) {
          ListItem(
            supportingContent = {
              Text(
                listOfNotNull(
                  doc.specialty?.takeIf { it.isNotBlank() },
                  doc.practice?.takeIf { it.isNotBlank() },
                  medicationCount(medCount),
                ).joinToString(" · "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            },
            trailingContent = if (doc.isPcp) ({ Tag("PCP") }) else null,
            colors = groupListItemColors(),
          ) { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
      }
    }
  }
}

internal fun medicationCount(n: Int): String = if (n == 1) "1 medication" else "$n medications"

@Composable
internal fun Tag(text: String) {
  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    shape = MaterialTheme.shapes.small,
  ) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
  }
}

@Composable
fun PrescriberDetailScreen(
  prescriberId: String,
  snapshot: RegimenSnapshot,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onOpenMedication: (String) -> Unit,
) {
  val prescriber = snapshot.prescribers.find { it.id == prescriberId }
  if (prescriber == null) {
    LaunchedEffect(prescriberId) { onBack() }
    return
  }
  val context = LocalContext.current
  val linkedMeds = snapshot.medications.filter { it.prescriberId == prescriber.id }
  var menuOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      MediumFlexibleTopAppBar(
        title = { Text(prescriber.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = {
          Text(
            listOfNotNull(
              if (prescriber.isPcp) "Primary care" else null,
              prescriber.specialty?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifEmpty { "Prescriber" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
          IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit prescriber") }
          OverflowDeleteMenu(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            label = "Delete prescriber",
            onDelete = { confirmDelete = true },
          )
        },
        scrollBehavior = scrollBehavior,
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
      item(key = "contact") {
        SectionHeader("Contact")
        val phone = prescriber.phone?.takeIf { it.isNotBlank() }
        val email = prescriber.email?.takeIf { it.isNotBlank() }
        val rows = buildList<Triple<String, String, (() -> Unit)?>> {
          prescriber.practice?.takeIf { it.isNotBlank() }?.let { add(Triple("Practice", it, null)) }
          phone?.let {
            add(Triple("Phone", it) { context.startActivityOrIgnore(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) })
          }
          email?.let {
            add(Triple("Email", it) { context.startActivityOrIgnore(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$it"))) })
          }
        }
        GroupedColumn {
          if (rows.isEmpty()) {
            GroupItem(0, 1) { ListItem(
   colors = groupListItemColors(),
 ) { Text("No contact details") } }
          }
          rows.forEachIndexed { i, (label, value, click) ->
            GroupItem(index = i, count = rows.size, onClick = click) {
              ListItem(
                overlineContent = { Text(label) },
                trailingContent = when (label) {
                  "Phone" -> ({ Icon(Icons.Default.Call, contentDescription = "Call") })
                  "Email" -> ({ Icon(Icons.Default.Email, contentDescription = "Send email") })
                  else -> null
                },
                colors = groupListItemColors(),
              ) { Text(value) }
            }
          }
        }
      }

      item(key = "care") {
        SectionHeader("Care")
        val rows = listOfNotNull(
          "Started" to (UiFormat.dateIso(prescriber.careStartDateIso) ?: "Not set"),
          "Ended" to (UiFormat.dateIso(prescriber.careEndDateIso) ?: "Ongoing"),
          prescriber.notes?.takeIf { it.isNotBlank() }?.let { "Notes" to it },
        )
        GroupedColumn {
          rows.forEachIndexed { i, (label, value) -> GroupItem(index = i, count = rows.size) { InfoRow(label, value) } }
        }
      }

      item(key = "meds") {
        SectionHeader("Medications")
        LinkedMedicationGroup(linkedMeds, emptyText = "No medications linked", onOpenMedication = onOpenMedication)
      }
    }
  }

  if (confirmDelete) {
    ConfirmDialog(
      title = "Delete ${prescriber.name}?",
      text = if (linkedMeds.isEmpty()) {
        "This removes the prescriber."
      } else {
        "This removes the prescriber and unlinks ${medicationCount(linkedMeds.size)}."
      },
      confirmLabel = "Delete",
      destructive = true,
      onConfirm = {
        confirmDelete = false
        onDelete()
      },
      onDismiss = { confirmDelete = false },
    )
  }
}

@Composable
internal fun OverflowDeleteMenu(
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  label: String,
  onDelete: () -> Unit,
) {
  Box {
    IconButton(onClick = { onExpandedChange(true) }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
      DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
        onClick = {
          onExpandedChange(false)
          onDelete()
        },
      )
    }
  }
}

@Composable
internal fun LinkedMedicationGroup(
  medications: List<Medication>,
  emptyText: String,
  onOpenMedication: (String) -> Unit,
) {
  GroupedColumn {
    if (medications.isEmpty()) {
      GroupItem(0, 1) { ListItem(
   colors = groupListItemColors(),
 ) { Text(emptyText) } }
    }
    medications.forEachIndexed { i, med ->
      GroupItem(index = i, count = medications.size, onClick = { onOpenMedication(med.id) }) {
        ListItem(
          leadingContent = { FormIcon(med.form) },
          supportingContent = { Text(listOfNotNull(med.secondaryName, med.strength).joinToString(" · ")) },
          colors = groupListItemColors(),
        ) { Text(med.primaryName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
      }
    }
  }
}

internal fun android.content.Context.startActivityOrIgnore(intent: Intent) {
  runCatching { startActivity(intent) }
}

@Composable
fun PrescriberEditorScreen(
  initialPrescriber: Prescriber?,
  onClose: () -> Unit,
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

  EditorScaffold(
    title = if (initialPrescriber == null) "Add prescriber" else "Edit prescriber",
    onClose = onClose,
    saveEnabled = name.isNotBlank(),
    onSave = {
      onSave(
        Prescriber(
          id = initialPrescriber?.id ?: java.util.UUID.randomUUID().toString(),
          name = name.trim(),
          specialty = specialty.trim().takeIf { it.isNotBlank() },
          practice = practice.trim().takeIf { it.isNotBlank() },
          phone = phone.trim().takeIf { it.isNotBlank() },
          email = email.trim().takeIf { it.isNotBlank() },
          isPcp = isPcp,
          careStartDateIso = startDate.takeIf { it.isNotBlank() },
          careEndDateIso = endDate.takeIf { it.isNotBlank() },
          notes = notes.trim().takeIf { it.isNotBlank() },
          createdAtEpochMillis = initialPrescriber?.createdAtEpochMillis ?: System.currentTimeMillis(),
        )
      )
    },
  ) {
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text("Name *") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = specialty,
      onValueChange = { specialty = it },
      label = { Text("Specialty") },
      placeholder = { Text("e.g. Psychiatry") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = practice,
      onValueChange = { practice = it },
      label = { Text("Practice or hospital") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = phone,
      onValueChange = { phone = it },
      label = { Text("Phone") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text("Email") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      modifier = Modifier.fillMaxWidth(),
    )
    GroupItem(0, 1) {
      SwitchRow(title = "Primary care provider", checked = isPcp, onCheckedChange = { isPcp = it })
    }
    DatePickerField(label = "Care started", valueIso = startDate, onValueChange = { startDate = it })
    DatePickerField(label = "Care ended", valueIso = endDate, onValueChange = { endDate = it })
    OutlinedTextField(
      value = notes,
      onValueChange = { notes = it },
      label = { Text("Notes") },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
