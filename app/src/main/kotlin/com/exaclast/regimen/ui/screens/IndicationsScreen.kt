@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.IndicationTimeline
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.AllergenType
import com.exaclast.regimen.model.Severity
import com.exaclast.regimen.model.IndicationKind
import com.exaclast.regimen.model.IndicationStatus
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
import java.time.LocalDate

/** Short labels that fit a four-way segmented button on a 360 dp phone. */
private fun IndicationKind.shortStatusLabel(status: IndicationStatus): String = when (status) {
  IndicationStatus.MANAGED -> "Managed"
  IndicationStatus.REMISSION -> "Remission"
  else -> statusLabel(status)
}

private fun Indication.matches(query: String): Boolean =
  query.isBlank() || name.contains(query.trim(), ignoreCase = true) ||
    details?.contains(query.trim(), ignoreCase = true) == true ||
    reaction?.contains(query.trim(), ignoreCase = true) == true

/** "Acute", or "Allergy · Drug" / "Intolerance · Food". */
private fun Indication.typeLabel(): String =
  if (kind.isReaction) {
    listOfNotNull(kind.displayName, (allergenType ?: AllergenType.DRUG).displayName).joinToString(" · ")
  } else {
    kind.displayName
  }

/**
 * One line under an indication: "Acute · Until Nov 15 · 1 medication" / "Acute · Ended Jun 3, 2026".
 * [showKind] is off under a heading that already names the kind.
 */
internal fun indicationSummary(
  indication: Indication,
  snapshot: RegimenSnapshot,
  archived: Boolean,
  showKind: Boolean = true,
): String {
  val end = snapshot.indicationEnd(indication)
  val medCount = snapshot.medications.count { indication.id in it.indicationIds && it.deletedAtEpochMillis == null }
  return if (archived) {
    listOfNotNull(
      "Suspected".takeIf { indication.suspected },
      indication.typeLabel(),
      end?.let { "Ended ${UiFormat.date(it.date, withWeekday = false)}" } ?: indication.kind.statusLabel(indication.status),
    ).joinToString(" · ")
  } else {
    listOfNotNull(
      "Suspected".takeIf { indication.suspected && showKind },
      indication.typeLabel().takeIf { showKind },
      // Allergies and intolerances share a heading, so each says which it is.
      indication.kind.displayName.takeIf { !showKind && indication.kind.isReaction },
      indication.severity?.displayName,
      indication.reaction?.takeIf { indication.kind.isReaction && it.isNotBlank() },
      indication.status.displayName.takeIf { indication.kind == IndicationKind.CHRONIC },
      end?.let { "Until ${UiFormat.date(it.date, withWeekday = false)}" },
      medicationCount(medCount),
    ).joinToString(" · ")
  }
}

@Composable
fun IndicationListScreen(
  snapshot: RegimenSnapshot,
  onOpenIndication: (String) -> Unit,
  onAddIndication: () -> Unit,
) {
  val listState = rememberLazyListState()
  val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
  val today = LocalDate.now()
  var query by rememberSaveable { mutableStateOf("") }
  var showPast by rememberSaveable { mutableStateOf(false) }

  val (pastAll, currentAll) = snapshot.indications.partition { snapshot.isArchived(it, today) }
  val current = currentAll.filter { it.matches(query) }
  // Suspected ones (not formally diagnosed) get a section of their own after the diagnosed ones.
  val currentGroups = IndicationTimeline.sections(current.filter { !it.suspected })
  val suspectedCurrent = current.filter { it.suspected }.sortedBy { it.name.lowercase() }
  val past = pastAll.filter { it.matches(query) }
    .sortedByDescending { snapshot.indicationEnd(it)?.date ?: LocalDate.MIN }
  // Searching looks through past indications too.
  val pastExpanded = showPast || query.isNotBlank()

  Scaffold(
    topBar = { TopAppBar(title = { Text("Indications") }) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onAddIndication,
        expanded = fabExpanded,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text("Add indication") },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    if (snapshot.indications.isEmpty()) {
      EmptyListMessage("No indications yet.", Modifier.padding(padding))
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
      if (pastAll.isNotEmpty() || snapshot.indications.size > 6) {
        item(key = "search") {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search indications") },
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
            "No current indications.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
          )
        }
      }
      currentGroups.forEach { (title, group) ->
        item(key = "header-$title") { SectionHeader(title) }
        itemsIndexed(group, key = { _, c -> c.id }) { index, indication ->
          GroupItem(index = index, count = group.size, onClick = { onOpenIndication(indication.id) }) {
            ListItem(
              supportingContent = { Text(indicationSummary(indication, snapshot, archived = false, showKind = false)) },
              colors = groupListItemColors(),
            ) { Text(indication.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
          }
        }
      }
      if (suspectedCurrent.isNotEmpty()) {
        item(key = "header-suspected") { SectionHeader("Suspected") }
        itemsIndexed(suspectedCurrent, key = { _, c -> c.id }) { index, indication ->
          GroupItem(index = index, count = suspectedCurrent.size, onClick = { onOpenIndication(indication.id) }) {
            ListItem(
              supportingContent = { Text(indicationSummary(indication, snapshot, archived = false).removePrefix("Suspected · ")) },
              colors = groupListItemColors(),
            ) { Text(indication.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
          }
        }
      }
      if (pastAll.isNotEmpty()) {
        item(key = "past-header") {
          SectionHeader(
            title = "Past indications (${if (query.isBlank()) pastAll.size else past.size})",
            action = if (query.isBlank()) {
              {
                TextButton(onClick = { showPast = !showPast }) { Text(if (showPast) "Hide" else "Show") }
              }
            } else null,
          )
        }
        if (pastExpanded) {
          itemsIndexed(past, key = { _, c -> c.id }) { index, indication ->
            GroupItem(index = index, count = past.size, onClick = { onOpenIndication(indication.id) }) {
              ListItem(
                supportingContent = { Text(indicationSummary(indication, snapshot, archived = true)) },
                colors = groupListItemColors(),
              ) { Text(indication.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
          }
        }
      }
      if (query.isNotBlank() && current.isEmpty() && past.isEmpty()) {
        item(key = "no-results") {
          Text(
            "No indications match \"${query.trim()}\".",
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
fun IndicationDetailScreen(
  indicationId: String,
  snapshot: RegimenSnapshot,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onOpenMedication: (String) -> Unit,
  onOpenProvider: (String) -> Unit,
  onSetMedications: (Set<String>) -> Unit,
  onSetProviders: (Set<String>) -> Unit,
  onHadAgain: () -> Unit,
) {
  val indication = snapshot.indications.find { it.id == indicationId }
  if (indication == null) {
    LaunchedEffect(indicationId) { onBack() }
    return
  }
  val linkedMeds = snapshot.medications.filter { indication.id in it.indicationIds && it.deletedAtEpochMillis == null }
  val linkedProviders = snapshot.providers.filter { it.id in indication.providerIds }
  val archived = snapshot.isArchived(indication)
  val end = snapshot.indicationEnd(indication)
  // Past providers stay listed only if already linked.
  val providerOptions = snapshot.providers
    .filter { !IndicationTimeline.isPast(it, LocalDate.now()) || it.id in indication.providerIds }
  var editMedications by remember { mutableStateOf(false) }
  var editProviders by remember { mutableStateOf(false) }
  var menuOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      MediumFlexibleTopAppBar(
        title = { Text(indication.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = {
          Text(
            listOfNotNull(
              "Suspected".takeIf { indication.suspected },
              indication.typeLabel(),
              if (archived) "Past" else indication.kind.statusLabel(indication.status),
            ).joinToString(" · "),
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
          IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit indication") }
          OverflowDeleteMenu(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            label = "Delete indication",
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
      if (archived && indication.kind.endsWithCourse) {
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
          end == null -> if (indication.kind == IndicationKind.ACUTE) "When its medication course ends" else "Ongoing"
          end.fromCourse -> "${UiFormat.date(end.date)} (end of medication course)"
          else -> UiFormat.date(end.date)
        }
        val rows = listOfNotNull(
          "Type" to indication.typeLabel(),
          "Diagnosis" to if (indication.suspected) "Suspected, not formally diagnosed" else "Diagnosed",
          indication.severity?.let { "Severity" to it.displayName },
          indication.reaction?.takeIf { it.isNotBlank() }?.let { "Reaction" to it },
          "Status" to indication.kind.statusLabel(indication.status),
          "Started" to (UiFormat.dateIso(indication.diagnosedDateIso) ?: "Not set"),
          (endLabel to endValue).takeIf { !indication.kind.isReaction || end != null },
          indication.details?.takeIf { it.isNotBlank() }?.let { "Notes" to it },
        )
        GroupedColumn {
          rows.forEachIndexed { i, (label, value) -> GroupItem(index = i, count = rows.size) { InfoRow(label, value) } }
        }
      }
      if (snapshot.tracksProviders) {
        item(key = "providers") {
          SectionHeader(
            title = "Providers",
            action = if (providerOptions.isNotEmpty()) {
              { TextButton(onClick = { editProviders = true }) { Text("Edit") } }
            } else null,
          )
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
        SectionHeader(
          title = "Medications for this indication",
          action = if (snapshot.medications.any { it.deletedAtEpochMillis == null }) {
            { TextButton(onClick = { editMedications = true }) { Text("Edit") } }
          } else null,
        )
        LinkedMedicationGroup(linkedMeds, emptyText = "No medications linked", onOpenMedication = onOpenMedication)
      }
    }
  }

  if (editProviders) {
    IndicationLinksDialog(
      title = "Providers helping manage it",
      options = providerOptions
        .sortedBy { it.name.lowercase() }
        .map { LinkOption(it.id, it.name, it.specialty?.takeIf { s -> s.isNotBlank() }) },
      initialSelection = linkedProviders.mapTo(HashSet()) { it.id },
      onConfirm = {
        editProviders = false
        onSetProviders(it)
      },
      onDismiss = { editProviders = false },
    )
  }

  if (editMedications) {
    IndicationLinksDialog(
      title = "Medications for this indication",
      options = snapshot.medications
        .filter { it.deletedAtEpochMillis == null }
        .sortedBy { it.primaryName.lowercase() }
        .map { LinkOption(it.id, it.primaryName, listOfNotNull(it.secondaryName, it.strength).joinToString(" · ")) },
      initialSelection = linkedMeds.mapTo(HashSet()) { it.id },
      onConfirm = {
        editMedications = false
        onSetMedications(it)
      },
      onDismiss = { editMedications = false },
    )
  }

  if (confirmDelete) {
    ConfirmDialog(
      title = "Delete ${indication.name}?",
      text = if (linkedMeds.isEmpty()) {
        "This removes the indication."
      } else {
        "This removes the indication and unlinks ${medicationCount(linkedMeds.size)}."
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

private class LinkOption(val id: String, val title: String, val subtitle: String?)

/** Pick the medications or providers linked to an indication. */
@Composable
private fun IndicationLinksDialog(
  title: String,
  options: List<LinkOption>,
  initialSelection: Set<String>,
  onConfirm: (Set<String>) -> Unit,
  onDismiss: () -> Unit,
) {
  var selection by remember { mutableStateOf(initialSelection) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        options.forEach { option ->
          val checked = option.id in selection
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { selection = if (it) selection + option.id else selection - option.id },
              )
              .padding(vertical = 4.dp),
          ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
              Text(option.title, style = MaterialTheme.typography.bodyLarge)
              option.subtitle?.let {
                Text(
                  it,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { onConfirm(selection) }) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * Add or edit an indication. [template] pre-fills a new one (a new episode of a past indication).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndicationEditorScreen(
  initialIndication: Indication?,
  template: Indication?,
  snapshot: RegimenSnapshot,
  onClose: () -> Unit,
  onSave: (Indication) -> Unit,
) {
  val source = initialIndication ?: template
  var name by rememberSaveable { mutableStateOf(source?.name.orEmpty()) }
  var details by rememberSaveable { mutableStateOf(source?.details.orEmpty()) }
  var kind by rememberSaveable { mutableStateOf(source?.kind ?: IndicationKind.CHRONIC) }
  var status by rememberSaveable { mutableStateOf(source?.status ?: IndicationStatus.ACTIVE) }
  var suspected by rememberSaveable { mutableStateOf(source?.suspected ?: false) }
  var allergenType by rememberSaveable { mutableStateOf(source?.allergenType ?: AllergenType.DRUG) }
  var severity by rememberSaveable { mutableStateOf(source?.severity) }
  var reaction by rememberSaveable { mutableStateOf(source?.reaction.orEmpty()) }
  val isAllergy = kind.isReaction
  var startDateIso by rememberSaveable { mutableStateOf(source?.diagnosedDateIso.orEmpty()) }
  var endDateIso by rememberSaveable { mutableStateOf(source?.endDateIso.orEmpty()) }
  var providerIds by rememberSaveable { mutableStateOf(source?.providerIds.orEmpty()) }

  val today = LocalDate.now()
  // Past providers stay listed only if already linked.
  val providerOptions = snapshot.providers.filter { !IndicationTimeline.isPast(it, today) || it.id in providerIds }
  val courseEnd = initialIndication?.let { IndicationTimeline.courseEnd(it, snapshot.medications, snapshot.schedules) }
  val endBeforeStart = startDateIso.isNotBlank() && endDateIso.isNotBlank() && endDateIso < startDateIso

  EditorScaffold(
    title = when {
      initialIndication != null -> "Edit indication"
      template != null -> "New episode"
      else -> "Add indication"
    },
    onClose = onClose,
    saveEnabled = name.isNotBlank() && !endBeforeStart,
    onSave = {
      onSave(
        Indication(
          id = initialIndication?.id ?: java.util.UUID.randomUUID().toString(),
          name = name.trim(),
          details = details.trim().takeIf { it.isNotBlank() },
          kind = kind,
          status = status,
          suspected = suspected,
          // Only drug intolerances are recorded, so there's nothing to choose for one.
          allergenType = (if (kind == IndicationKind.INTOLERANCE) AllergenType.DRUG else allergenType).takeIf { isAllergy },
          severity = severity,
          reaction = reaction.trim().takeIf { isAllergy && it.isNotEmpty() },
          diagnosedDateIso = startDateIso.takeIf { it.isNotBlank() },
          endDateIso = endDateIso.takeIf { it.isNotBlank() },
          providerIds = providerIds.filter { id -> snapshot.providers.any { it.id == id } },
          createdAtEpochMillis = initialIndication?.createdAtEpochMillis ?: System.currentTimeMillis(),
        )
      )
    },
  ) {
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = {
        Text(
          when (kind) {
            IndicationKind.ALLERGY -> "Allergen *"
            IndicationKind.INTOLERANCE -> "Trigger *"
            else -> "Name *"
          },
        )
      },
      placeholder = {
        Text(
          when (kind) {
            IndicationKind.ACUTE -> "e.g. Sinus infection"
            IndicationKind.CHRONIC -> "e.g. ADHD"
            IndicationKind.PREVENTIVE -> "e.g. HIV PrEP"
            IndicationKind.ALLERGY -> when (allergenType) {
              AllergenType.DRUG -> "e.g. Penicillin"
              AllergenType.FOOD -> "e.g. Peanuts"
              AllergenType.ENVIRONMENTAL -> "e.g. Birch pollen"
            }
            IndicationKind.INTOLERANCE -> "e.g. Metformin"
          },
        )
      },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Text("Type", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      IndicationKind.entries.forEach { k ->
        FilterChip(
          selected = kind == k,
          onClick = {
            kind = k
            if (status !in k.statuses) status = IndicationStatus.ACTIVE
          },
          label = { Text(k.displayName) },
        )
      }
    }
    Text(
      when (kind) {
        IndicationKind.ACUTE -> "Short-term, like an infection or injury. Moves to past indications when it ends."
        IndicationKind.CHRONIC -> "Ongoing, like diabetes or high blood pressure."
        IndicationKind.PREVENTIVE -> "Taken to prevent something, like PrEP or PEP. Stays current unless its course ends."
        IndicationKind.ALLERGY -> "An immune reaction, like hives or anaphylaxis. Medications you link are the ones that treat it."
        IndicationKind.INTOLERANCE -> "A drug you can't tolerate, like severe nausea or another side effect. Not an immune reaction, so not an allergy."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (isAllergy) {
      if (kind == IndicationKind.ALLERGY) {
        Text("Allergic to", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
          AllergenType.entries.forEachIndexed { i, type ->
            SegmentedButton(
              selected = allergenType == type,
              onClick = { allergenType = type },
              shape = SegmentedButtonDefaults.itemShape(index = i, count = AllergenType.entries.size),
              icon = {},
              label = { Text(type.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
          }
        }
      }
      OutlinedTextField(
        value = reaction,
        onValueChange = { reaction = it },
        label = { Text("Reaction") },
        placeholder = { Text("e.g. Hives, swelling") },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Text("Severity", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Severity.entries.forEach { level ->
        FilterChip(
          selected = severity == level,
          onClick = { severity = if (severity == level) null else level },
          label = { Text(level.displayName) },
        )
      }
    }
    Text("Status", style = MaterialTheme.typography.titleSmall)
    val statuses = kind.statuses
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
      statuses.forEachIndexed { i, s ->
        SegmentedButton(
          selected = status == s,
          onClick = { status = s },
          shape = SegmentedButtonDefaults.itemShape(index = i, count = statuses.size),
          icon = {},
          label = { Text(kind.shortStatusLabel(s), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
      }
    }
    SwitchRow(
      title = "Suspected",
      subtitle = "Not formally diagnosed, such as a symptom or a condition under investigation",
      checked = suspected,
      onCheckedChange = { suspected = it },
    )
    DatePickerField(
      label = when {
        isAllergy -> "First reaction"
        kind == IndicationKind.CHRONIC && !suspected -> "Diagnosed"
        else -> "Started"
      },
      valueIso = startDateIso,
      onValueChange = { startDateIso = it },
    )
    if (!isAllergy) DatePickerField(
      label = "End date",
      valueIso = endDateIso,
      onValueChange = { endDateIso = it },
      supportingText = when {
        endBeforeStart -> "Ends before it starts"
        endDateIso.isNotBlank() -> null
        kind.endsWithCourse && courseEnd != null ->
          "Blank: ends with its medication course (${UiFormat.date(courseEnd, withWeekday = false)})"
        kind == IndicationKind.ACUTE -> "Blank: ends when its medication course ends"
        kind == IndicationKind.PREVENTIVE -> "Blank: ongoing unless its medication course has an end"
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
