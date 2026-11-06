@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TextButton
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
import com.exaclast.regimen.domain.ConditionTimeline
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.ConditionKind
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
import java.time.LocalDate

/** Short labels that fit a four-way segmented button on a 360 dp phone. */
private val ConditionStatus.shortLabel: String
  get() = when (this) {
    ConditionStatus.ACTIVE -> "Active"
    ConditionStatus.MANAGED -> "Managed"
    ConditionStatus.REMISSION -> "Remission"
    ConditionStatus.RESOLVED -> "Resolved"
  }

private fun Condition.matches(query: String): Boolean =
  query.isBlank() || name.contains(query.trim(), ignoreCase = true) ||
    details?.contains(query.trim(), ignoreCase = true) == true

/** One line under a condition: "Acute · Until Nov 15 · 1 medication" / "Acute · Ended Jun 3, 2026". */
internal fun conditionSummary(condition: Condition, snapshot: RegimenSnapshot, archived: Boolean): String {
  val end = snapshot.conditionEnd(condition)
  val medCount = snapshot.medications.count { it.indicationConditionId == condition.id && it.deletedAtEpochMillis == null }
  return if (archived) {
    listOfNotNull(
      condition.kind.displayName,
      end?.let { "Ended ${UiFormat.date(it.date, withWeekday = false)}" } ?: condition.status.displayName,
    ).joinToString(" · ")
  } else {
    listOfNotNull(
      condition.kind.displayName,
      condition.status.displayName.takeIf { condition.kind == ConditionKind.CHRONIC },
      end?.let { "Until ${UiFormat.date(it.date, withWeekday = false)}" },
      medicationCount(medCount),
    ).joinToString(" · ")
  }
}

@Composable
fun ConditionListScreen(
  snapshot: RegimenSnapshot,
  onOpenCondition: (String) -> Unit,
  onAddCondition: () -> Unit,
) {
  val listState = rememberLazyListState()
  val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
  val today = LocalDate.now()
  var query by rememberSaveable { mutableStateOf("") }
  var showPast by rememberSaveable { mutableStateOf(false) }

  val (pastAll, currentAll) = snapshot.conditions.partition { snapshot.isArchived(it, today) }
  val current = currentAll.filter { it.matches(query) }
    .sortedWith(compareBy<Condition> { it.kind != ConditionKind.ACUTE }.thenBy { it.name.lowercase() })
  val past = pastAll.filter { it.matches(query) }
    .sortedByDescending { snapshot.conditionEnd(it)?.date ?: LocalDate.MIN }
  // Searching looks through past conditions too.
  val pastExpanded = showPast || query.isNotBlank()

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
    if (snapshot.conditions.isEmpty()) {
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
      if (pastAll.isNotEmpty() || snapshot.conditions.size > 6) {
        item(key = "search") {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search conditions") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
              { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") } }
            } else null,
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 12.dp),
          )
        }
      }
      if (current.isEmpty() && query.isBlank()) {
        item(key = "no-current") {
          Text(
            "No current conditions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
          )
        }
      }
      itemsIndexed(current, key = { _, c -> c.id }) { index, condition ->
        GroupItem(index = index, count = current.size, onClick = { onOpenCondition(condition.id) }) {
          ListItem(
            supportingContent = { Text(conditionSummary(condition, snapshot, archived = false)) },
            colors = groupListItemColors(),
          ) { Text(condition.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
      }
      if (pastAll.isNotEmpty()) {
        item(key = "past-header") {
          SectionHeader(
            title = "Past conditions (${if (query.isBlank()) pastAll.size else past.size})",
            action = if (query.isBlank()) {
              {
                TextButton(onClick = { showPast = !showPast }) { Text(if (showPast) "Hide" else "Show") }
              }
            } else null,
          )
        }
        if (pastExpanded) {
          itemsIndexed(past, key = { _, c -> c.id }) { index, condition ->
            GroupItem(index = index, count = past.size, onClick = { onOpenCondition(condition.id) }) {
              ListItem(
                supportingContent = { Text(conditionSummary(condition, snapshot, archived = true)) },
                colors = groupListItemColors(),
              ) { Text(condition.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
          }
        }
      }
      if (query.isNotBlank() && current.isEmpty() && past.isEmpty()) {
        item(key = "no-results") {
          Text(
            "No conditions match \"${query.trim()}\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
          )
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
  onOpenProvider: (String) -> Unit,
  onHadAgain: () -> Unit,
) {
  val condition = snapshot.conditions.find { it.id == conditionId }
  if (condition == null) {
    LaunchedEffect(conditionId) { onBack() }
    return
  }
  val linkedMeds = snapshot.medications.filter { it.indicationConditionId == condition.id }
  val linkedProviders = snapshot.providers.filter { it.id in condition.providerIds }
  val archived = snapshot.isArchived(condition)
  val end = snapshot.conditionEnd(condition)
  var menuOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      MediumFlexibleTopAppBar(
        title = { Text(condition.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = {
          Text(
            listOf(condition.kind.displayName, if (archived) "Past" else condition.status.displayName)
              .joinToString(" · "),
          )
        },
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
      if (archived && condition.kind == ConditionKind.ACUTE) {
        item(key = "again") {
          FilledTonalButton(onClick = onHadAgain, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Had this again", modifier = Modifier.padding(start = 8.dp))
          }
        }
      }
      item(key = "details") {
        SectionHeader("Details")
        val endLabel = if (end != null && !end.date.isAfter(LocalDate.now()) || archived) "Ended" else "Ends"
        val endValue = when {
          end == null -> if (condition.kind == ConditionKind.ACUTE) "When its medication course ends" else "Ongoing"
          end.fromCourse -> "${UiFormat.date(end.date)} (end of medication course)"
          else -> UiFormat.date(end.date)
        }
        val rows = listOfNotNull(
          "Type" to condition.kind.displayName,
          "Status" to condition.status.displayName,
          "Started" to (UiFormat.dateIso(condition.diagnosedDateIso) ?: "Not set"),
          endLabel to endValue,
          condition.details?.takeIf { it.isNotBlank() }?.let { "Notes" to it },
        )
        GroupedColumn {
          rows.forEachIndexed { i, (label, value) -> GroupItem(index = i, count = rows.size) { InfoRow(label, value) } }
        }
      }
      if (snapshot.tracksProviders) {
        item(key = "providers") {
          SectionHeader("Providers")
          GroupedColumn {
            if (linkedProviders.isEmpty()) {
              GroupItem(0, 1) { ListItem(colors = groupListItemColors()) { Text("No providers linked") } }
            }
            linkedProviders.forEachIndexed { i, provider ->
              GroupItem(index = i, count = linkedProviders.size, onClick = { onOpenProvider(provider.id) }) {
                ListItem(
                  supportingContent = provider.specialty?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                  colors = groupListItemColors(),
                ) { Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
              }
            }
          }
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

/**
 * Add or edit a condition. [template] pre-fills a new one (a new episode of an acute condition).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConditionEditorScreen(
  initialCondition: Condition?,
  template: Condition?,
  snapshot: RegimenSnapshot,
  onClose: () -> Unit,
  onSave: (Condition) -> Unit,
) {
  val source = initialCondition ?: template
  var name by rememberSaveable { mutableStateOf(source?.name.orEmpty()) }
  var details by rememberSaveable { mutableStateOf(source?.details.orEmpty()) }
  var kind by rememberSaveable { mutableStateOf(source?.kind ?: ConditionKind.CHRONIC) }
  var status by rememberSaveable { mutableStateOf(source?.status ?: ConditionStatus.ACTIVE) }
  var startDateIso by rememberSaveable { mutableStateOf(source?.diagnosedDateIso.orEmpty()) }
  var endDateIso by rememberSaveable { mutableStateOf(source?.endDateIso.orEmpty()) }
  var providerIds by rememberSaveable { mutableStateOf(source?.providerIds.orEmpty()) }

  val today = LocalDate.now()
  // Past providers stay listed only if already linked.
  val providerOptions = snapshot.providers.filter { !ConditionTimeline.isPast(it, today) || it.id in providerIds }
  val courseEnd = initialCondition?.let { ConditionTimeline.courseEnd(it, snapshot.medications, snapshot.schedules) }
  val endBeforeStart = startDateIso.isNotBlank() && endDateIso.isNotBlank() && endDateIso < startDateIso

  EditorScaffold(
    title = when {
      initialCondition != null -> "Edit condition"
      template != null -> "New episode"
      else -> "Add condition"
    },
    onClose = onClose,
    saveEnabled = name.isNotBlank() && !endBeforeStart,
    onSave = {
      onSave(
        Condition(
          id = initialCondition?.id ?: java.util.UUID.randomUUID().toString(),
          name = name.trim(),
          details = details.trim().takeIf { it.isNotBlank() },
          kind = kind,
          status = status,
          diagnosedDateIso = startDateIso.takeIf { it.isNotBlank() },
          endDateIso = endDateIso.takeIf { it.isNotBlank() },
          providerIds = providerIds.filter { id -> snapshot.providers.any { it.id == id } },
          createdAtEpochMillis = initialCondition?.createdAtEpochMillis ?: System.currentTimeMillis(),
        )
      )
    },
  ) {
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text("Name *") },
      placeholder = { Text(if (kind == ConditionKind.ACUTE) "e.g. Sinus infection" else "e.g. ADHD") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Text("Type", style = MaterialTheme.typography.titleSmall)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      ConditionKind.entries.forEachIndexed { i, k ->
        SegmentedButton(
          selected = kind == k,
          onClick = {
            kind = k
            if (status !in k.statuses) status = ConditionStatus.ACTIVE
          },
          shape = SegmentedButtonDefaults.itemShape(index = i, count = ConditionKind.entries.size),
          icon = {},
          label = { Text(k.displayName) },
        )
      }
    }
    Text(
      if (kind == ConditionKind.ACUTE) {
        "Short-term, like an infection or injury. Moves to past conditions when it ends."
      } else {
        "Ongoing, like diabetes or high blood pressure."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Status", style = MaterialTheme.typography.titleSmall)
    val statuses = kind.statuses
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
    DatePickerField(
      label = if (kind == ConditionKind.ACUTE) "Started" else "Diagnosed",
      valueIso = startDateIso,
      onValueChange = { startDateIso = it },
    )
    DatePickerField(
      label = "End date",
      valueIso = endDateIso,
      onValueChange = { endDateIso = it },
      supportingText = when {
        endBeforeStart -> "Ends before it starts"
        endDateIso.isNotBlank() -> null
        kind == ConditionKind.ACUTE && courseEnd != null ->
          "Blank: ends with its medication course (${UiFormat.date(courseEnd, withWeekday = false)})"
        kind == ConditionKind.ACUTE -> "Blank: ends when its medication course ends"
        else -> "Blank: ongoing"
      },
    )
    if (snapshot.tracksProviders && providerOptions.isNotEmpty()) {
      Text("Providers helping manage it", style = MaterialTheme.typography.titleSmall)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        providerOptions.forEach { provider ->
          val selected = provider.id in providerIds
          FilterChip(
            selected = selected,
            onClick = { providerIds = if (selected) providerIds - provider.id else providerIds + provider.id },
            label = { Text(provider.name) },
            leadingIcon = if (selected) {
              { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else null,
          )
        }
      }
    }
    OutlinedTextField(
      value = details,
      onValueChange = { details = it },
      label = { Text("Notes") },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
