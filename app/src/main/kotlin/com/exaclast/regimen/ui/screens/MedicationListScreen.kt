@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.MedicationGroups
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationGrouping
import com.exaclast.regimen.ui.ScheduleText
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.ListContentPadding
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.groupListItemColors
import java.time.LocalDate

@Composable
fun MedicationListScreen(
  snapshot: RegimenSnapshot,
  grouping: MedicationGrouping,
  onGroupingChange: (MedicationGrouping) -> Unit,
  onOpenMedication: (String) -> Unit,
  onAddMedication: () -> Unit,
) {
  var searching by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()
  val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

  var showFinished by rememberSaveable { mutableStateOf(false) }
  // The indication chip goes away with indication tracking; the saved choice is kept for if it returns.
  val groupingOptions = MedicationGrouping.entries.filter {
    it != MedicationGrouping.INDICATION || snapshot.tracksIndications
  }
  val activeGrouping = grouping.takeIf { it in groupingOptions } ?: MedicationGrouping.SCHEDULE
  val today = LocalDate.now()

  val grouped = remember(snapshot.medications, snapshot.schedules, snapshot.indications, activeGrouping, today, query) {
    val q = query.trim().lowercase()
    MedicationGroups
      .group(activeGrouping, snapshot.medications, snapshot.schedules, snapshot.indications, today)
      .filter {
        q.isEmpty() ||
          it.formulaName.lowercase().contains(q) ||
          it.brandName?.lowercase()?.contains(q) == true ||
          it.indicationText.lowercase().contains(q) ||
          it.ndc11?.contains(q) == true
      }
  }

  Scaffold(
    topBar = {
      SearchableTopBar(
        title = stringResource(R.string.medications_title),
        searching = searching,
        query = query,
        onQueryChange = { query = it },
        onSearchingChange = { active ->
          searching = active
          if (!active) query = ""
        },
        searchPlaceholder = stringResource(R.string.medications_search),
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onAddMedication,
        expanded = fabExpanded,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text(stringResource(R.string.medications_add)) },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    if (snapshot.medications.isEmpty()) {
      EmptyListMessage(
        text = stringResource(R.string.medications_empty),
        modifier = Modifier.padding(padding),
      )
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
      item(key = "grouping") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          groupingOptions.forEach { option ->
            FilterChip(
              selected = option == activeGrouping,
              onClick = { onGroupingChange(option) },
              label = { Text(option.displayName) },
            )
          }
        }
      }
      if (grouped.sections.isEmpty() && grouped.finished.isEmpty()) {
        item { Text(stringResource(R.string.list_no_matches, query), modifier = Modifier.padding(16.dp)) }
      }
      grouped.sections.forEach { section ->
        item(key = "header-${section.key}") { SectionHeader(section.title) }
        // Grouped by indication a medication can be in several sections, so its id alone isn't unique.
        itemsIndexed(section.medications, key = { _, m -> "${section.key}/${m.id}" }) { index, med ->
          GroupItem(index = index, count = section.medications.size, onClick = { onOpenMedication(med.id) }) {
            MedicationListItem(med, snapshot)
          }
        }
      }
      if (grouped.finished.isNotEmpty()) {
        // A search looks through finished medications too, or a match could stay hidden.
        val expanded = showFinished || query.isNotBlank()
        item(key = "finished-header") {
          SectionHeader(
            title = stringResource(R.string.medications_finished, grouped.finished.size),
            action = { TextButton(onClick = { showFinished = !showFinished }) { Text(stringResource(if (expanded) R.string.action_hide else R.string.action_show)) } },
          )
        }
        if (expanded) {
          itemsIndexed(grouped.finished, key = { _, m -> "finished/${m.id}" }) { index, med ->
            GroupItem(index = index, count = grouped.finished.size, onClick = { onOpenMedication(med.id) }) {
              MedicationListItem(med, snapshot)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MedicationListItem(med: Medication, snapshot: RegimenSnapshot) {
  ListItem(
    leadingContent = { FormIcon(med.form) },
    supportingContent = {
      Text(
        text = listOfNotNull(
          listOfNotNull(med.secondaryName, med.strength).joinToString(" · "),
          ScheduleText.summary(snapshot.schedules.filter { it.medicationId == med.id }, snapshot.buckets),
        ).joinToString("\n"),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    },
    colors = groupListItemColors(),
  ) { Text(med.primaryName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Form icon in a tonal circle. */
@Composable
fun FormIcon(form: MedicationForm) {
  Surface(
    shape = CircleShape,
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier = Modifier.size(40.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(formIcon(form), contentDescription = form.displayName, modifier = Modifier.size(22.dp))
    }
  }
}

private fun formIcon(form: MedicationForm): ImageVector = when (form) {
  MedicationForm.INJECTABLE_VIAL, MedicationForm.INJECTABLE_PEN -> Icons.Default.Vaccines
  MedicationForm.LIQUID, MedicationForm.DROPS -> Icons.Default.WaterDrop
  MedicationForm.TOPICAL -> Icons.Default.Spa
  else -> Icons.Default.Medication
}

/** Top app bar with a search action that expands into an inline search field. */
@Composable
fun SearchableTopBar(
  title: String,
  searching: Boolean,
  query: String,
  onQueryChange: (String) -> Unit,
  onSearchingChange: (Boolean) -> Unit,
  searchPlaceholder: String,
) {
  val focusRequester = remember { FocusRequester() }
  TopAppBar(
    title = {
      if (searching) {
        TextField(
          value = query,
          onValueChange = onQueryChange,
          placeholder = { Text(searchPlaceholder, maxLines = 1) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = {}),
          colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
          ),
          modifier = Modifier.focusRequester(focusRequester),
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
      } else {
        Text(title)
      }
    },
    navigationIcon = {
      if (searching) {
        IconButton(onClick = { onSearchingChange(false) }) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_close))
        }
      }
    },
    actions = {
      if (searching) {
        if (query.isNotEmpty()) {
          IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.search_clear)) }
        }
      } else {
        IconButton(onClick = { onSearchingChange(true) }) { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_open)) }
      }
    },
  )
}

@Composable
fun EmptyListMessage(text: String, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .padding(32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
