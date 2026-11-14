@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.exaclast.regimen.ui.screens

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.IndicationTimeline
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.Provider
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

@Composable
fun ProviderListScreen(
  snapshot: RegimenSnapshot,
  onOpenProvider: (String) -> Unit,
  onAddProvider: () -> Unit,
) {
  val listState = rememberLazyListState()
  val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
  val today = LocalDate.now()
  val (past, providers) = snapshot.providers.partition { IndicationTimeline.isPast(it, today) }
  val (regular, asNeeded) = providers.partition { it.isRegular }
  var showPast by rememberSaveable { mutableStateOf(false) }
  val regularTitle = stringResource(R.string.providers_regular)
  val asNeededTitle = stringResource(R.string.providers_as_needed)
  Scaffold(
    topBar = { TopAppBar(title = { Text(stringResource(R.string.providers_title)) }) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onAddProvider,
        expanded = fabExpanded,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(stringResource(R.string.providers_add)) },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    if (snapshot.providers.isEmpty()) {
      EmptyListMessage(stringResource(R.string.providers_empty), Modifier.padding(padding))
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
      listOf(regularTitle to regular, asNeededTitle to asNeeded).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
        item(key = "header-$title") { SectionHeader(title) }
        itemsIndexed(group, key = { _, p -> p.id }) { index, doc ->
          GroupItem(index = index, count = group.size, onClick = { onOpenProvider(doc.id) }) {
            ProviderListItem(doc, snapshot, past = false)
          }
        }
      }
      if (past.isNotEmpty()) {
        item(key = "past-header") {
          SectionHeader(
            title = stringResource(R.string.providers_past, past.size),
            action = { TextButton(onClick = { showPast = !showPast }) { Text(stringResource(if (showPast) R.string.action_hide else R.string.action_show)) } },
          )
        }
        if (showPast) {
          itemsIndexed(past, key = { _, p -> p.id }) { index, doc ->
            GroupItem(index = index, count = past.size, onClick = { onOpenProvider(doc.id) }) {
              ProviderListItem(doc, snapshot, past = true)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProviderListItem(doc: Provider, snapshot: RegimenSnapshot, past: Boolean) {
  val medCount = snapshot.medications.count { it.prescriberId == doc.id }
  val indicationCount = snapshot.indications.count { doc.id in it.providerIds }
  val res = LocalResources.current
  ListItem(
    supportingContent = {
      Text(
        listOfNotNull(
          doc.specialty?.takeIf { it.isNotBlank() },
          doc.practice?.takeIf { it.isNotBlank() },
          if (past) UiFormat.dateIso(doc.careEndDateIso)?.let { res.getString(R.string.providers_care_ended, it) } else null,
          medicationCount(res, medCount).takeIf { medCount > 0 },
          indicationCount(res, indicationCount).takeIf { indicationCount > 0 && snapshot.tracksIndications },
        ).joinToString(" · "),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    trailingContent = if (doc.isPcp && !past) ({ Tag(stringResource(R.string.provider_pcp)) }) else null,
    colors = groupListItemColors(),
  ) { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

internal fun indicationCount(res: Resources, n: Int): String = res.getQuantityString(R.plurals.indication_count, n, n)

internal fun medicationCount(res: Resources, n: Int): String = res.getQuantityString(R.plurals.medication_count, n, n)

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
fun ProviderDetailScreen(
  providerId: String,
  snapshot: RegimenSnapshot,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onOpenMedication: (String) -> Unit,
  onOpenIndication: (String) -> Unit,
  onSetIndications: (Set<String>) -> Unit,
) {
  val provider = snapshot.providers.find { it.id == providerId }
  if (provider == null) {
    LaunchedEffect(providerId) { onBack() }
    return
  }
  val context = LocalContext.current
  val linkedMeds = snapshot.medications.filter { it.prescriberId == provider.id }
  val linkedIndications = snapshot.indications
    .filter { provider.id in it.providerIds }
    .sortedWith(compareBy<Indication> { snapshot.isArchived(it) }.thenBy { it.name.lowercase() })
  var editIndications by remember { mutableStateOf(false) }
  var menuOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      MediumFlexibleTopAppBar(
        title = { Text(provider.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = {
          Text(
            listOfNotNull(
              when {
                provider.isPcp -> stringResource(R.string.provider_primary_care)
                provider.seenRegularly -> stringResource(R.string.provider_seen_regularly)
                else -> null
              },
              provider.specialty?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifEmpty { stringResource(R.string.provider_label) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
        },
        actions = {
          IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.provider_edit)) }
          OverflowDeleteMenu(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            label = stringResource(R.string.provider_delete),
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
        SectionHeader(stringResource(R.string.provider_contact))
        val practiceLabel = stringResource(R.string.field_practice)
        val phoneLabel = stringResource(R.string.field_phone)
        val emailLabel = stringResource(R.string.field_email)
        val phone = provider.phone?.takeIf { it.isNotBlank() }
        val email = provider.email?.takeIf { it.isNotBlank() }
        val rows = buildList<Triple<String, String, (() -> Unit)?>> {
          provider.practice?.takeIf { it.isNotBlank() }?.let { add(Triple(practiceLabel, it, null)) }
          phone?.let {
            add(Triple(phoneLabel, it) { context.startActivityOrIgnore(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) })
          }
          email?.let {
            add(Triple(emailLabel, it) { context.startActivityOrIgnore(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$it"))) })
          }
        }
        GroupedColumn {
          if (rows.isEmpty()) {
            GroupItem(0, 1) { ListItem(
   colors = groupListItemColors(),
 ) { Text(stringResource(R.string.provider_no_contact)) } }
          }
          rows.forEachIndexed { i, (label, value, click) ->
            GroupItem(index = i, count = rows.size, onClick = click) {
              ListItem(
                overlineContent = { Text(label) },
                trailingContent = when (label) {
                  phoneLabel -> ({ Icon(Icons.Default.Call, contentDescription = stringResource(R.string.provider_call)) })
                  emailLabel -> ({ Icon(Icons.Default.Email, contentDescription = stringResource(R.string.provider_send_email)) })
                  else -> null
                },
                colors = groupListItemColors(),
              ) { Text(value) }
            }
          }
        }
      }

      item(key = "care") {
        SectionHeader(stringResource(R.string.provider_care))
        val rows = listOfNotNull(
          stringResource(R.string.field_started) to (UiFormat.dateIso(provider.careStartDateIso) ?: stringResource(R.string.value_not_set)),
          stringResource(R.string.field_ended) to (UiFormat.dateIso(provider.careEndDateIso) ?: stringResource(R.string.value_ongoing)),
          provider.notes?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.field_notes) to it },
        )
        GroupedColumn {
          rows.forEachIndexed { i, (label, value) -> GroupItem(index = i, count = rows.size) { InfoRow(label, value) } }
        }
      }

      if (snapshot.tracksIndications) {
        item(key = "indications") {
          SectionHeader(
            title = stringResource(R.string.provider_indications),
            action = if (snapshot.indications.isNotEmpty()) {
              { TextButton(onClick = { editIndications = true }) { Text(stringResource(R.string.action_edit)) } }
            } else null,
          )
          GroupedColumn {
            if (linkedIndications.isEmpty()) {
              GroupItem(0, 1) { ListItem(colors = groupListItemColors()) { Text(stringResource(R.string.provider_no_indications)) } }
            }
            linkedIndications.forEachIndexed { i, indication ->
              GroupItem(index = i, count = linkedIndications.size, onClick = { onOpenIndication(indication.id) }) {
                ListItem(
                  supportingContent = { Text(indicationSummary(LocalResources.current, indication, snapshot, snapshot.isArchived(indication))) },
                  colors = groupListItemColors(),
                ) { Text(indication.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
              }
            }
          }
        }
      }

      item(key = "meds") {
        SectionHeader(stringResource(R.string.provider_medications))
        LinkedMedicationGroup(linkedMeds, emptyText = stringResource(R.string.provider_no_medications), onOpenMedication = onOpenMedication)
      }
    }
  }

  if (editIndications) {
    ProviderIndicationsDialog(
      snapshot = snapshot,
      initialSelection = linkedIndications.mapTo(HashSet()) { it.id },
      onConfirm = {
        editIndications = false
        onSetIndications(it)
      },
      onDismiss = { editIndications = false },
    )
  }

  if (confirmDelete) {
    ConfirmDialog(
      title = stringResource(R.string.provider_delete_title, provider.name),
      text = if (linkedMeds.isEmpty()) {
        stringResource(R.string.provider_delete_text)
      } else {
        stringResource(R.string.provider_delete_text_linked, medicationCount(LocalResources.current, linkedMeds.size))
      },
      confirmLabel = stringResource(R.string.action_delete),
      destructive = true,
      onConfirm = {
        confirmDelete = false
        onDelete()
      },
      onDismiss = { confirmDelete = false },
    )
  }
}

/** Pick the indications a provider manages: current ones by kind, then past ones. */
@Composable
private fun ProviderIndicationsDialog(
  snapshot: RegimenSnapshot,
  initialSelection: Set<String>,
  onConfirm: (Set<String>) -> Unit,
  onDismiss: () -> Unit,
) {
  var selection by remember { mutableStateOf(initialSelection) }
  val pastLabel = stringResource(R.string.label_past)
  val resources = LocalResources.current
  val (past, current) = snapshot.indications.partition { snapshot.isArchived(it) }
  val sections = IndicationTimeline.sections(current) +
    listOfNotNull(past.sortedBy { it.name.lowercase() }.takeIf { it.isNotEmpty() }?.let { pastLabel to it })
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.provider_indications)) },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        sections.forEach { (title, group) ->
          Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
          group.forEach { indication ->
            val checked = indication.id in selection
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                  value = checked,
                  role = Role.Checkbox,
                  onValueChange = { selection = if (it) selection + indication.id else selection - indication.id },
                )
                .padding(vertical = 4.dp),
            ) {
              Checkbox(checked = checked, onCheckedChange = null)
              Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(indication.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                  indicationSummary(resources, indication, snapshot, snapshot.isArchived(indication)),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { onConfirm(selection) }) { Text(stringResource(R.string.action_save)) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

@Composable
internal fun OverflowDeleteMenu(
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  label: String,
  onDelete: () -> Unit,
) {
  Box {
    IconButton(onClick = { onExpandedChange(true) }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more)) }
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
fun ProviderEditorScreen(
  initialProvider: Provider?,
  onClose: () -> Unit,
  onSave: (Provider) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf(initialProvider?.name.orEmpty()) }
  var specialty by rememberSaveable { mutableStateOf(initialProvider?.specialty.orEmpty()) }
  var practice by rememberSaveable { mutableStateOf(initialProvider?.practice.orEmpty()) }
  var phone by rememberSaveable { mutableStateOf(initialProvider?.phone.orEmpty()) }
  var email by rememberSaveable { mutableStateOf(initialProvider?.email.orEmpty()) }
  var isPcp by rememberSaveable { mutableStateOf(initialProvider?.isPcp ?: false) }
  var seenRegularly by rememberSaveable { mutableStateOf(initialProvider?.seenRegularly ?: false) }
  var startDate by rememberSaveable { mutableStateOf(initialProvider?.careStartDateIso.orEmpty()) }
  var endDate by rememberSaveable { mutableStateOf(initialProvider?.careEndDateIso.orEmpty()) }
  var notes by rememberSaveable { mutableStateOf(initialProvider?.notes.orEmpty()) }

  EditorScaffold(
    title = stringResource(if (initialProvider == null) R.string.providers_add else R.string.provider_edit),
    onClose = onClose,
    saveEnabled = name.isNotBlank(),
    onSave = {
      onSave(
        Provider(
          id = initialProvider?.id ?: java.util.UUID.randomUUID().toString(),
          name = name.trim(),
          specialty = specialty.trim().takeIf { it.isNotBlank() },
          practice = practice.trim().takeIf { it.isNotBlank() },
          phone = phone.trim().takeIf { it.isNotBlank() },
          email = email.trim().takeIf { it.isNotBlank() },
          isPcp = isPcp,
          seenRegularly = seenRegularly,
          careStartDateIso = startDate.takeIf { it.isNotBlank() },
          careEndDateIso = endDate.takeIf { it.isNotBlank() },
          notes = notes.trim().takeIf { it.isNotBlank() },
          createdAtEpochMillis = initialProvider?.createdAtEpochMillis ?: System.currentTimeMillis(),
        )
      )
    },
  ) {
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text(stringResource(R.string.field_name_required)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = specialty,
      onValueChange = { specialty = it },
      label = { Text(stringResource(R.string.field_specialty)) },
      placeholder = { Text(stringResource(R.string.field_specialty_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = practice,
      onValueChange = { practice = it },
      label = { Text(stringResource(R.string.field_practice_or_hospital)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = phone,
      onValueChange = { phone = it },
      label = { Text(stringResource(R.string.field_phone)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text(stringResource(R.string.field_email)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      modifier = Modifier.fillMaxWidth(),
    )
    GroupedColumn {
      GroupItem(0, if (isPcp) 1 else 2) {
        SwitchRow(title = stringResource(R.string.provider_pcp_switch), checked = isPcp, onCheckedChange = { isPcp = it })
      }
      // A primary care provider always counts as regular.
      if (!isPcp) {
        GroupItem(1, 2) {
          SwitchRow(
            title = stringResource(R.string.provider_regular_switch),
            subtitle = stringResource(R.string.provider_regular_switch_help),
            checked = seenRegularly,
            onCheckedChange = { seenRegularly = it },
          )
        }
      }
    }
    DatePickerField(label = stringResource(R.string.provider_care_started), valueIso = startDate, onValueChange = { startDate = it })
    DatePickerField(label = stringResource(R.string.provider_care_ended), valueIso = endDate, onValueChange = { endDate = it })
    OutlinedTextField(
      value = notes,
      onValueChange = { notes = it },
      label = { Text(stringResource(R.string.field_notes)) },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
