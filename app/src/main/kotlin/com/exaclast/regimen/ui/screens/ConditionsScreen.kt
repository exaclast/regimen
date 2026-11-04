@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.ConditionStatus
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.DatePickerField
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.InfoRow
import com.exaclast.regimen.ui.components.ListContentPadding
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.components.groupListItemColors

/** Short labels that fit a four-way segmented button on a 360 dp phone. */
private val ConditionStatus.shortLabel: String
  get() = when (this) {
    ConditionStatus.ACTIVE -> "Active"
    ConditionStatus.MANAGED -> "Managed"
    ConditionStatus.REMISSION -> "Remission"
    ConditionStatus.RESOLVED -> "Resolved"
  }

@Composable
fun ConditionListScreen(
  snapshot: RegimenSnapshot,
  onOpenCondition: (String) -> Unit,
  onAddCondition: () -> Unit,
) {
  val listState = rememberLazyListState()
  val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
  val conditions = snapshot.conditions
  Scaffold(
    topBar = { TopAppBar(title = { Text("Conditions") }) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onAddCondition,
        expanded = fabExpanded,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text("Add condition") },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    if (conditions.isEmpty()) {
      EmptyListMessage("No conditions yet.", Modifier.padding(padding))
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
      itemsIndexed(conditions, key = { _, c -> c.id }) { index, condition ->
        val medCount = snapshot.medications.count { it.indicationConditionId == condition.id }
        GroupItem(index = index, count = conditions.size, onClick = { onOpenCondition(condition.id) }) {
          ListItem(
            supportingContent = {
              Text(listOf(condition.status.displayName, medicationCount(medCount)).joinToString(" · "))
            },
            colors = groupListItemColors(),
          ) { Text(condition.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
      }
    }
  }
}

@Composable
fun ConditionDetailScreen(
  conditionId: String,
  snapshot: RegimenSnapshot,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onOpenMedication: (String) -> Unit,
) {
  val condition = snapshot.conditions.find { it.id == conditionId }
  if (condition == null) {
    LaunchedEffect(conditionId) { onBack() }
    return
  }
  val linkedMeds = snapshot.medications.filter { it.indicationConditionId == condition.id }
  var menuOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      MediumFlexibleTopAppBar(
        title = { Text(condition.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = { Text(condition.status.displayName) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
          IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit condition") }
          OverflowDeleteMenu(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            label = "Delete condition",
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
      item(key = "details") {
        SectionHeader("Details")
        val rows = listOfNotNull(
          "Diagnosed" to (UiFormat.dateIso(condition.diagnosedDateIso) ?: "Not set"),
          condition.details?.takeIf { it.isNotBlank() }?.let { "Notes" to it },
        )
        GroupedColumn {
          rows.forEachIndexed { i, (label, value) -> GroupItem(index = i, count = rows.size) { InfoRow(label, value) } }
        }
      }
      item(key = "meds") {
        SectionHeader("Medications for this condition")
        LinkedMedicationGroup(linkedMeds, emptyText = "No medications linked", onOpenMedication = onOpenMedication)
      }
    }
  }

  if (confirmDelete) {
    ConfirmDialog(
      title = "Delete ${condition.name}?",
      text = if (linkedMeds.isEmpty()) {
        "This removes the condition."
      } else {
        "This removes the condition and unlinks ${medicationCount(linkedMeds.size)}."
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
fun ConditionEditorScreen(
  initialCondition: Condition?,
  onClose: () -> Unit,
  onSave: (Condition) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf(initialCondition?.name.orEmpty()) }
  var details by rememberSaveable { mutableStateOf(initialCondition?.details.orEmpty()) }
  var status by rememberSaveable { mutableStateOf(initialCondition?.status ?: ConditionStatus.ACTIVE) }
  var diagnosedDateIso by rememberSaveable { mutableStateOf(initialCondition?.diagnosedDateIso.orEmpty()) }

  EditorScaffold(
    title = if (initialCondition == null) "Add condition" else "Edit condition",
    onClose = onClose,
    saveEnabled = name.isNotBlank(),
    onSave = {
      onSave(
        Condition(
          id = initialCondition?.id ?: java.util.UUID.randomUUID().toString(),
          name = name.trim(),
          details = details.trim().takeIf { it.isNotBlank() },
          status = status,
          diagnosedDateIso = diagnosedDateIso.takeIf { it.isNotBlank() },
          createdAtEpochMillis = initialCondition?.createdAtEpochMillis ?: System.currentTimeMillis(),
        )
      )
    },
  ) {
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text("Name *") },
      placeholder = { Text("e.g. ADHD") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Text("Status", style = MaterialTheme.typography.titleSmall)
    val statuses = ConditionStatus.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      statuses.forEachIndexed { i, s ->
        SegmentedButton(
          selected = status == s,
          onClick = { status = s },
          shape = SegmentedButtonDefaults.itemShape(index = i, count = statuses.size),
          icon = {},
          label = { Text(s.shortLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
      }
    }
    DatePickerField(label = "Diagnosed", valueIso = diagnosedDateIso, onValueChange = { diagnosedDateIso = it })
    OutlinedTextField(
      value = details,
      onValueChange = { details = it },
      label = { Text("Notes") },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
