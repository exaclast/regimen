package com.exaclast.regimen.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.exaclast.regimen.data.CrossReferenceOutcome
import com.exaclast.regimen.data.CrossReferencedFdaCandidate
import com.exaclast.regimen.data.DrugBarcodeScanner
import com.exaclast.regimen.data.DrugCrossReferenceCriteria
import com.exaclast.regimen.data.DrugLabelParser
import com.exaclast.regimen.data.FdaDrugLookupResult
import com.exaclast.regimen.data.FdaNdcLookupService
import com.exaclast.regimen.data.ParsedMedicationLabel
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Prescriber
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.formatNum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun MedicationsScreen(
  snapshot: RegimenSnapshot,
  selectedMedicationId: String?,
  onSelectMedication: (String?) -> Unit,
  onSaveMedication: (Medication) -> Unit,
  onDeleteMedication: (String) -> Unit,
  onSaveSchedule: (MedicationSchedule) -> Unit,
  onDeleteSchedule: (String) -> Unit,
  onQuickLogDose: (Medication, Double, DoseUnit, String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var editingMedication by remember { mutableStateOf<Medication?>(null) }
  var isCreatingNew by remember { mutableStateOf(false) }

  // Post-creation prompt & separate schedule editor state
  var newlyCreatedMedForSchedulePrompt by remember { mutableStateOf<Medication?>(null) }
  var scheduleEditorTarget by remember { mutableStateOf<Pair<Medication, MedicationSchedule?>?>(null) }

  val filteredMedications = remember(snapshot.medications, searchQuery) {
    if (searchQuery.isBlank()) {
      snapshot.medications
    } else {
      val q = searchQuery.trim().lowercase()
      snapshot.medications.filter {
        it.formulaName.lowercase().contains(q) ||
          it.brandName?.lowercase()?.contains(q) == true ||
          it.indicationText.lowercase().contains(q) ||
          it.ndc11?.lowercase()?.contains(q) == true
      }
    }
  }

  val activeSelectedMedication = remember(snapshot.medications, selectedMedicationId) {
    snapshot.medications.find { it.id == selectedMedicationId }
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isTwoPane = maxWidth >= 700.dp

    if (isTwoPane) {
      val effectiveMedication = activeSelectedMedication ?: filteredMedications.firstOrNull()
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Left Pane: Search + List
        Column(
          modifier = Modifier
            .weight(0.9f)
            .fillMaxHeight(),
        ) {
          MedicationListHeader(
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            onAddClick = { isCreatingNew = true },
          )
          Spacer(modifier = Modifier.height(12.dp))
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
          ) {
            items(filteredMedications, key = { it.id }) { med ->
              MedicationSummaryCard(
                medication = med,
                snapshot = snapshot,
                isSelected = med.id == effectiveMedication?.id,
                onClick = { onSelectMedication(med.id) },
              )
            }
          }
        }

        // Right Pane: Detail
        Box(
          modifier = Modifier
            .weight(1.2f)
            .fillMaxHeight(),
        ) {
          if (effectiveMedication != null) {
            MedicationDetailPane(
              medication = effectiveMedication,
              snapshot = snapshot,
              showBackButton = false,
              onBack = {},
              onEdit = { editingMedication = effectiveMedication },
              onDelete = { onDeleteMedication(effectiveMedication.id) },
              onAddSchedule = { scheduleEditorTarget = effectiveMedication to null },
              onEditSchedule = { sched -> scheduleEditorTarget = effectiveMedication to sched },
              onDeleteSchedule = onDeleteSchedule,
              onQuickLog = { site ->
                onQuickLogDose(
                  effectiveMedication,
                  effectiveMedication.defaultDoseAmount,
                  effectiveMedication.defaultDoseUnit,
                  site,
                )
              },
            )
          } else {
            Card(modifier = Modifier.fillMaxSize()) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select or add a medication to view full details & schedule.")
              }
            }
          }
        }
      }
    } else {
      // Single-pane layout for compact phone screen
      if (activeSelectedMedication != null) {
        MedicationDetailPane(
          medication = activeSelectedMedication,
          snapshot = snapshot,
          showBackButton = true,
          onBack = { onSelectMedication(null) },
          onEdit = { editingMedication = activeSelectedMedication },
          onDelete = { onDeleteMedication(activeSelectedMedication.id) },
          onAddSchedule = { scheduleEditorTarget = activeSelectedMedication to null },
          onEditSchedule = { sched -> scheduleEditorTarget = activeSelectedMedication to sched },
          onDeleteSchedule = onDeleteSchedule,
          onQuickLog = { site ->
            onQuickLogDose(
              activeSelectedMedication,
              activeSelectedMedication.defaultDoseAmount,
              activeSelectedMedication.defaultDoseUnit,
              site,
            )
          },
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
            MedicationListHeader(
              searchQuery = searchQuery,
              onSearchChange = { searchQuery = it },
              onAddClick = { isCreatingNew = true },
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(10.dp),
              contentPadding = PaddingValues(bottom = 88.dp),
            ) {
              items(filteredMedications, key = { it.id }) { med ->
                MedicationSummaryCard(
                  medication = med,
                  snapshot = snapshot,
                  isSelected = false,
                  onClick = { onSelectMedication(med.id) },
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
            Icon(Icons.Default.Add, contentDescription = "Add Medication")
          }
        }
      }
    }
  }

  // 1. Medication-only Add/Edit Dialog
  if (isCreatingNew || editingMedication != null) {
    val existing = editingMedication
    val wasNew = existing == null
    MedicationEditorDialog(
      initialMedication = existing,
      conditions = snapshot.conditions,
      prescribers = snapshot.prescribers,
      onDismiss = {
        isCreatingNew = false
        editingMedication = null
      },
      onSave = { savedMed ->
        onSaveMedication(savedMed)
        isCreatingNew = false
        editingMedication = null
        if (wasNew) {
          newlyCreatedMedForSchedulePrompt = savedMed
        }
      },
    )
  }

  // 2. Post-Save "Set up schedule now?" prompt for newly added medications
  newlyCreatedMedForSchedulePrompt?.let { createdMed ->
    AlertDialog(
      onDismissRequest = { newlyCreatedMedForSchedulePrompt = null },
      title = { Text("Set up a schedule for ${createdMed.displayTitle}?") },
      text = {
        Text(
          "You can assign this medication to daily or recurring time-of-day windows now, or leave it as As-Needed (PRN) and add schedules later."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            newlyCreatedMedForSchedulePrompt = null
            scheduleEditorTarget = createdMed to null
          }
        ) {
          Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Set Up Schedule")
        }
      },
      dismissButton = {
        TextButton(onClick = { newlyCreatedMedForSchedulePrompt = null }) {
          Text("Keep As-Needed")
        }
      },
    )
  }

  // 3. Dedicated Schedule Editor Dialog
  scheduleEditorTarget?.let { (medication, existingSchedule) ->
    ScheduleEditorDialog(
      medication = medication,
      initialSchedule = existingSchedule,
      buckets = snapshot.buckets,
      onDismiss = { scheduleEditorTarget = null },
      onSave = { savedSchedule ->
        onSaveSchedule(savedSchedule)
        scheduleEditorTarget = null
      },
    )
  }
}

@Composable
private fun MedicationListHeader(
  searchQuery: String,
  onSearchChange: (String) -> Unit,
  onAddClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
      value = searchQuery,
      onValueChange = onSearchChange,
      placeholder = {
        Text(
          text = "Search formula, brand, NDC...",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { onSearchChange("") }) {
            Icon(Icons.Default.Close, contentDescription = "Clear search")
          }
        }
      },
      singleLine = true,
      modifier = Modifier.weight(1f),
    )
    Button(onClick = onAddClick) {
      Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Add")
    }
  }
}

@Composable
private fun MedicationSummaryCard(
  medication: Medication,
  snapshot: RegimenSnapshot,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  val condition = snapshot.conditions.find { it.id == medication.indicationConditionId }
  val nextSite = DoseEngine.computeNextSite(medication, snapshot.doseLogs)

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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = medication.displayTitle,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = medication.strength,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.SemiBold,
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      val routeInfo = medication.injectableDetails?.let { " (${it.route.code})" } ?: ""
      Text(
        text = "${medication.form.displayName}$routeInfo • Default: ${medication.formattedDefaultDose()}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      val indicationStr = condition?.name ?: medication.indicationText.takeIf { it.isNotBlank() }
      if (indicationStr != null) {
        Text(
          text = "Indication: $indicationStr",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (nextSite != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Next rotation site: $nextSite",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.tertiary,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedicationDetailPane(
  medication: Medication,
  snapshot: RegimenSnapshot,
  showBackButton: Boolean,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onAddSchedule: () -> Unit,
  onEditSchedule: (MedicationSchedule) -> Unit,
  onDeleteSchedule: (String) -> Unit,
  onQuickLog: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val condition = snapshot.conditions.find { it.id == medication.indicationConditionId }
  val prescriber = snapshot.prescribers.find { it.id == medication.prescriberId }
  val schedules = snapshot.schedules.filter { it.medicationId == medication.id }
  val medLogs = snapshot.doseLogs.filter { it.medicationId == medication.id }
  val nextSite = DoseEngine.computeNextSite(medication, snapshot.doseLogs)
  val lastSite = DoseEngine.getLastSiteUsed(medication.id, snapshot.doseLogs)
  val pacing = DoseEngine.evaluatePacing(medication, snapshot.doseLogs)

  var confirmDelete by remember { mutableStateOf(false) }

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to list")
              }
            }
            Column {
              Text(
                text = medication.formulaName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
              )
              if (!medication.brandName.isNullOrBlank()) {
                Text(
                  text = "Brand: ${medication.brandName}",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onEdit) {
              Icon(Icons.Default.Edit, contentDescription = "Edit medication")
            }
            IconButton(onClick = { confirmDelete = true }) {
              Icon(
                Icons.Default.Delete,
                contentDescription = "Delete medication",
                tint = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }

      // Quick Log & Pacing Status Banner
      item {
        ElevatedCard(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
          ),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "${medication.strength} • ${medication.form.displayName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Text(
                text = "Default Dose: ${medication.formattedDefaultDose()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Text(
                text = "Pacing: ${pacing.statusSummary}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
              )
            }
            Button(onClick = { onQuickLog(nextSite) }) {
              Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(if (nextSite != null) "Log ($nextSite)" else "Log Dose")
            }
          }
        }
      }

      // Dedicated Schedules Card (Separated from Medication Editor)
      item {
        Surface(
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 1.dp,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = "Dose Schedules",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
              )
              FilledTonalButton(
                onClick = onAddSchedule,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Schedule")
              }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (schedules.isEmpty()) {
              Text(
                text = "No recurring schedule rules configured (tracked As-Needed / PRN).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              val bucketMap = snapshot.buckets.associateBy { it.id }
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                schedules.forEach { sched ->
                  val bucketNames = sched.bucketIds
                    .mapNotNull { bucketMap[it]?.let { b -> "${b.name} (${b.formattedTime()})" } }
                    .joinToString(", ")
                  val freqDesc = when (sched.frequencyType) {
                    ScheduleFrequencyType.DAILY -> "Daily • $bucketNames"
                    ScheduleFrequencyType.EVERY_N_DAYS ->
                      "Every ${sched.intervalDays} days (from ${sched.anchorDateIso}) • $bucketNames"
                    ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE ->
                      "Every ${sched.intervalDays} days after last dose • $bucketNames"
                    ScheduleFrequencyType.DAYS_OF_WEEK ->
                      "Days ${sched.daysOfWeek.sorted().joinToString("/")} • $bucketNames"
                    ScheduleFrequencyType.AS_NEEDED -> "As Needed (PRN)"
                  }
                  Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(
                          text = "${formatNum(sched.doseAmount)} ${sched.doseUnit.symbol} • ${sched.frequencyType.displayName}",
                          style = MaterialTheme.typography.bodyMedium,
                          fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                          text = freqDesc,
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      Row {
                        IconButton(
                          onClick = { onEditSchedule(sched) },
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit schedule",
                            modifier = Modifier.size(18.dp),
                          )
                        }
                        IconButton(
                          onClick = { onDeleteSchedule(sched.id) },
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete schedule",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
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

      // Injectable & Vial Hardware Specifications
      medication.injectableDetails?.let { inj ->
        item {
          SectionDetailCard(title = "Injectable / Vial & Syringe Specifications") {
            DetailRow("Administration Route", inj.route.displayName)
            inj.concentrationMgPerMl?.let {
              DetailRow("Vial Concentration", "${formatNum(it)} mg/mL")
            }
            inj.vialVolumeMl?.let {
              DetailRow("Vial Size", "${formatNum(it)} mL")
            }
            val calcVol = inj.calculateVolumeMl(medication.defaultDoseAmount, medication.defaultDoseUnit)
            if (calcVol != null) {
              DetailRow(
                "Calculated Draw Volume",
                "${formatNum(calcVol)} mL (${formatNum(calcVol * 100.0)} units on U-100)",
              )
            }
            inj.syringeVolumeMl?.let {
              val desc = inj.syringeDescription?.let { d -> " ($d)" } ?: ""
              DetailRow("Syringe", "${formatNum(it)} mL$desc")
            } ?: inj.syringeDescription?.let {
              DetailRow("Syringe", it)
            }
            inj.formattedDrawNeedle()?.let {
              DetailRow("Draw Needle", it)
            }
            inj.formattedInjectNeedle()?.let {
              DetailRow("Injection Needle", it)
            }
          }
        }
      }

      // Site Rotation Queue
      if (medication.siteRotationEnabled && medication.rotationSites.isNotEmpty()) {
        item {
          SectionDetailCard(title = "Automated Site Rotation Queue") {
            Text(
              text = "Advances automatically from the actual site used on your last dose.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              medication.rotationSites.forEachIndexed { index, site ->
                val isNext = site.equals(nextSite, ignoreCase = true)
                val isLast = site.equals(lastSite, ignoreCase = true)
                AssistChip(
                  onClick = { onQuickLog(site) },
                  label = {
                    val tag = when {
                      isNext -> " (NEXT)"
                      isLast -> " (LAST)"
                      else -> ""
                    }
                    Text("${index + 1}. $site$tag")
                  },
                  leadingIcon = if (isNext) {
                    {
                      Icon(
                        Icons.Default.Autorenew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  } else null,
                )
              }
            }
          }
        }
      }

      // Pacing & Safety Limits (on the Medication object)
      item {
        SectionDetailCard(title = "Pacing & Daily Limits (Medication Attributes)") {
          DetailRow(
            "Min Time Between Doses",
            medication.minIntervalMinutes?.let { DoseEngine.formatDurationMinutes(it) } ?: "No minimum interval",
          )
          DetailRow(
            "Max Daily Doses",
            medication.maxDailyDoses?.let { "${formatNum(it)} doses / day" } ?: "Unlimited",
          )
          DetailRow(
            "Max Daily Amount",
            medication.maxDailyAmount?.let { "${formatNum(it)} ${medication.defaultDoseUnit.symbol} / day" }
              ?: "Unlimited",
          )
        }
      }

      // Indication, Prescriber & Drug Metadata (NDC-11, Brand, Manufacturer)
      item {
        SectionDetailCard(title = "Indication, Prescriber & Drug Info") {
          DetailRow("Linked Condition", condition?.name ?: "None linked")
          if (medication.indicationText.isNotBlank()) {
            DetailRow("Indication Notes", medication.indicationText)
          }
          DetailRow(
            "Prescriber",
            prescriber?.let { if (it.isPcp) "${it.name} (PCP)" else it.name } ?: "Not specified",
          )
          medication.prescribedDateIso?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Prescribed Date", it)
          }
          medication.ndc11?.takeIf { it.isNotBlank() }?.let {
            DetailRow("NDC-11", it)
          }
          medication.brandName?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Brand Name", it)
          }
          medication.manufacturer?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Manufacturer", it)
          }
          medication.notes?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Instructions / Notes", it)
          }
        }
      }

      // Dose History for this Medication
      item {
        SectionDetailCard(title = "Recent Dose History (${medLogs.size})") {
          if (medLogs.isEmpty()) {
            Text(
              text = "No doses logged yet for this medication.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            medLogs.take(10).forEach { log ->
              val dt = Instant.ofEpochMilli(log.actionTimestampMillis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a"))
              val sitePart = log.siteUsed?.let { " • $it" } ?: ""
              DetailRow(
                label = dt,
                value = "${log.status.displayName}: ${formatNum(log.amountTaken)} ${log.amountUnit.symbol}$sitePart",
              )
            }
          }
        }
      }
    }
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete ${medication.displayTitle}?") },
      text = { Text("This will remove the medication and its active schedules.") },
      confirmButton = {
        Button(
          onClick = {
            confirmDelete = false
            onDelete()
          }
        ) {
          Text("Delete")
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmDelete = false }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
fun SectionDetailCard(
  title: String,
  content: @Composable () -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.height(8.dp))
      content()
    }
  }
}

@Composable
fun DetailRow(label: String, value: String) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(0.45f),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(0.55f),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownSelectorField(
  label: String,
  selectedText: String,
  options: List<T>,
  optionLabel: (T) -> String,
  onOptionSelected: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier,
  ) {
    OutlinedTextField(
      value = selectedText,
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
      modifier = Modifier
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        .fillMaxWidth(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(optionLabel(option)) },
          onClick = {
            onOptionSelected(option)
            expanded = false
          },
          contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
        )
      }
    }
  }
}

@Composable
private fun CollapsibleFormSection(
  title: String,
  subtitle: String? = null,
  initiallyExpanded: Boolean = false,
  content: @Composable () -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded }
          .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          if (!subtitle.isNullOrBlank()) {
            Text(
              text = subtitle,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Icon(
          imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (expanded) "Collapse" else "Expand",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          HorizontalDivider()
          content()
        }
      }
    }
  }
}

/**
 * Streamlined Medication Editor Dialog:
 * - Clean vertical hierarchy with full-width long fields and 2-column short fields
 * - ExposedDropdownMenuBox selectors instead of sprawling chip grids
 * - Inline, non-verbose NDC-11 lookup inside the NDC text field trailing icon
 * - Collapsible sections for Pacing Limits & Optional Rx/Drug Metadata
 * - Separated from Schedule configuration
 */
@Composable
private fun MedicationEditorDialog(
  initialMedication: Medication?,
  conditions: List<Condition>,
  prescribers: List<Prescriber>,
  onDismiss: () -> Unit,
  onSave: (Medication) -> Unit,
) {
  var formulaName by rememberSaveable { mutableStateOf(initialMedication?.formulaName.orEmpty()) }
  var brandName by rememberSaveable { mutableStateOf(initialMedication?.brandName.orEmpty()) }
  var manufacturer by rememberSaveable { mutableStateOf(initialMedication?.manufacturer.orEmpty()) }
  var ndc11 by rememberSaveable { mutableStateOf(initialMedication?.ndc11.orEmpty()) }
  var strength by rememberSaveable { mutableStateOf(initialMedication?.strength.orEmpty()) }
  var form by rememberSaveable { mutableStateOf(initialMedication?.form ?: MedicationForm.TABLET) }
  var defaultDoseAmountText by rememberSaveable {
    mutableStateOf(initialMedication?.defaultDoseAmount?.let { formatNum(it) } ?: "1")
  }
  var defaultDoseUnit by rememberSaveable {
    mutableStateOf(initialMedication?.defaultDoseUnit ?: DoseUnit.TABLET)
  }

  // Injectable fields
  val initInj = initialMedication?.injectableDetails
  var route by rememberSaveable { mutableStateOf(initInj?.route ?: InjectionRoute.IM) }
  var concentrationText by rememberSaveable {
    mutableStateOf(initInj?.concentrationMgPerMl?.let { formatNum(it) }.orEmpty())
  }
  var vialVolumeText by rememberSaveable {
    mutableStateOf(initInj?.vialVolumeMl?.let { formatNum(it) }.orEmpty())
  }
  var syringeVolumeText by rememberSaveable {
    mutableStateOf(initInj?.syringeVolumeMl?.let { formatNum(it) }.orEmpty())
  }
  var syringeDesc by rememberSaveable { mutableStateOf(initInj?.syringeDescription.orEmpty()) }
  var drawGaugeText by rememberSaveable { mutableStateOf(initInj?.drawNeedleGauge?.toString().orEmpty()) }
  var drawLengthText by rememberSaveable {
    mutableStateOf(initInj?.drawNeedleLengthInches?.let { formatNum(it) }.orEmpty())
  }
  var injectGaugeText by rememberSaveable { mutableStateOf(initInj?.injectNeedleGauge?.toString().orEmpty()) }
  var injectLengthText by rememberSaveable {
    mutableStateOf(initInj?.injectNeedleLengthInches?.let { formatNum(it) }.orEmpty())
  }

  // Site Rotation
  var siteRotationEnabled by rememberSaveable {
    mutableStateOf(initialMedication?.siteRotationEnabled ?: false)
  }
  val rotationSites = remember {
    mutableStateListOf<String>().apply {
      addAll(initialMedication?.rotationSites ?: Medication.PRESET_IM_GLUTE_SITES)
    }
  }
  var newCustomSite by rememberSaveable { mutableStateOf("") }

  // Pacing & Safety Limits (on Medication object)
  var minIntervalHoursText by rememberSaveable {
    mutableStateOf(
      initialMedication?.minIntervalMinutes?.let { formatNum(it / 60.0) }.orEmpty()
    )
  }
  var maxDailyDosesText by rememberSaveable {
    mutableStateOf(initialMedication?.maxDailyDoses?.let { formatNum(it) }.orEmpty())
  }
  var maxDailyAmountText by rememberSaveable {
    mutableStateOf(initialMedication?.maxDailyAmount?.let { formatNum(it) }.orEmpty())
  }

  // Indication & Prescriber
  var selectedConditionId by rememberSaveable { mutableStateOf(initialMedication?.indicationConditionId) }
  var indicationText by rememberSaveable { mutableStateOf(initialMedication?.indicationText.orEmpty()) }
  var selectedPrescriberId by rememberSaveable { mutableStateOf(initialMedication?.prescriberId) }
  var prescribedDateIso by rememberSaveable { mutableStateOf(initialMedication?.prescribedDateIso.orEmpty()) }
  var notes by rememberSaveable { mutableStateOf(initialMedication?.notes.orEmpty()) }

  // Gemini Nano (AICore) Label Scanner & Cross-Reference lookup state
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var isGeminiNanoSupported by remember { mutableStateOf(false) }
  var isLookingUpNdc by remember { mutableStateOf(false) }
  var ndcErrorText by remember { mutableStateOf<String?>(null) }
  var ndcStatusText by remember { mutableStateOf<String?>(null) }
  var ndcMatched by remember { mutableStateOf(false) }
  var showLabelScanDialog by remember { mutableStateOf(false) }
  var manualLabelInput by remember { mutableStateOf("") }
  var pendingLabelPhotoUri by remember { mutableStateOf<Uri?>(null) }
  var disambiguationPrompt by remember {
    mutableStateOf<CrossReferenceOutcome.NeedsUserSelection?>(null)
  }

  LaunchedEffect(Unit) {
    isGeminiNanoSupported = DrugBarcodeScanner.isGeminiNanoAvailable()
  }

  fun applyFdaResultToForm(fda: FdaDrugLookupResult, statusSummary: String? = null) {
    ndc11 = fda.ndc11
    formulaName = fda.formulaName
    fda.brandName?.let { brandName = it }
    fda.manufacturer?.let { manufacturer = it }
    strength = fda.strength
    form = fda.form
    defaultDoseUnit = fda.defaultDoseUnit
    fda.route?.let { route = it }
    fda.concentrationMgPerMl?.let { concentrationText = formatNum(it) }
    fda.vialVolumeMl?.let { vialVolumeText = formatNum(it) }
    if (fda.form.supportsSiteRotation) {
      siteRotationEnabled = true
    }
    val metaParts = mutableListOf<String>()
    fda.lotNumber?.let { metaParts.add("Lot: $it") }
    fda.expirationDateIso?.let { metaParts.add("Exp: $it") }
    if (metaParts.isNotEmpty()) {
      val metaLine = metaParts.joinToString(" • ")
      ndcStatusText = statusSummary ?: "Autofilled (${fda.ndc11} • $metaLine)"
      if (!notes.contains(metaLine)) {
        notes = if (notes.isBlank()) metaLine else "$notes\n$metaLine"
      }
    } else {
      ndcStatusText = statusSummary ?: "Autofilled from FDA NDC (${fda.ndc11})"
    }
    ndcErrorText = null
    ndcMatched = true
  }

  fun runCrossReferenceLookup(criteria: DrugCrossReferenceCriteria) {
    isLookingUpNdc = true
    ndcErrorText = null
    coroutineScope.launch {
      val outcome = FdaNdcLookupService.searchAndCrossReference(criteria)
      isLookingUpNdc = false
      when (outcome) {
        is CrossReferenceOutcome.UnambiguousMatch -> {
          val cand = outcome.candidate
          val signals = cand.matchedSignals.joinToString(", ")
          applyFdaResultToForm(
            fda = cand.result,
            statusSummary = "Verified via OpenFDA (${cand.result.ndc11} • Matched: $signals)",
          )
        }
        is CrossReferenceOutcome.NeedsUserSelection -> {
          disambiguationPrompt = outcome
        }
        is CrossReferenceOutcome.NoMatch -> {
          if (formulaName.isBlank() && brandName.isBlank()) {
            ndcErrorText = outcome.message
          } else {
            ndcStatusText = outcome.message
          }
        }
      }
    }
  }

  fun applyParsedMedicationLabel(parsedLabel: ParsedMedicationLabel) {
    if (!parsedLabel.hasAnyClinicalSignal) {
      ndcErrorText = "Could not detect medication name or NDC on the label. Try moving closer or check lighting."
      return
    }
    ndcErrorText = null
    parsedLabel.extractedNdc?.let { ndc11 = FdaNdcLookupService.toCanonicalNdc11(it) }
    parsedLabel.brandName?.let { brandName = it }
    parsedLabel.formulaName?.let { formulaName = it }
    parsedLabel.strength?.let { strength = it }
    parsedLabel.form?.let { detectedForm ->
      form = detectedForm
      if (detectedForm.supportsSiteRotation) {
        siteRotationEnabled = true
      }
    }
    parsedLabel.defaultDoseUnit?.let { defaultDoseUnit = it }
    parsedLabel.defaultDoseAmount?.let { defaultDoseAmountText = formatNum(it) }
    parsedLabel.manufacturer?.let { manufacturer = it }

    // Auto-link prescriber if name matches an existing prescriber in snapshot
    parsedLabel.prescriberName?.let { scannedDoc ->
      val docTokens = scannedDoc.lowercase().split(" ", ",").filter { it.length >= 3 }
      val matchedPrescriber = prescribers.firstOrNull { p ->
        val pLower = p.name.lowercase()
        docTokens.isNotEmpty() && docTokens.all { t -> pLower.contains(t) }
      }
      if (matchedPrescriber != null) {
        selectedPrescriberId = matchedPrescriber.id
      }
    }

    val noteAdditions = mutableListOf<String>()
    parsedLabel.instructions?.let { noteAdditions.add(it) }
    val lotExp = listOfNotNull(
      parsedLabel.lotNumber?.let { "Lot: $it" },
      parsedLabel.expirationDateIso?.let { "Exp: $it" },
      parsedLabel.prescriberName?.takeIf { selectedPrescriberId == null }?.let { "Prescriber: $it" },
    ).joinToString(" • ")
    if (lotExp.isNotEmpty()) {
      noteAdditions.add(lotExp)
    }
    noteAdditions.forEach { line ->
      if (!notes.contains(line)) {
        notes = if (notes.isBlank()) line else "$notes\n$line"
      }
    }

    if (parsedLabel.isCompound) {
      ndcMatched = true
      ndcStatusText = "Parsed compounded medication label (no FDA NDC applicable)"
    } else {
      ndcStatusText = "Parsed label text — cross-referencing OpenFDA..."
      runCrossReferenceLookup(
        DrugCrossReferenceCriteria(
          ndc = parsedLabel.extractedNdc ?: ndc11.takeIf { it.isNotBlank() },
          formulaName = parsedLabel.formulaName ?: formulaName.takeIf { it.isNotBlank() },
          brandName = parsedLabel.brandName ?: brandName.takeIf { it.isNotBlank() },
          strength = parsedLabel.strength ?: strength.takeIf { it.isNotBlank() },
          form = parsedLabel.form ?: form,
          manufacturer = parsedLabel.manufacturer ?: manufacturer.takeIf { it.isNotBlank() },
        )
      )
    }
  }

  fun runGeminiLabelImageAnalysis(imageUri: Uri) {
    isLookingUpNdc = true
    ndcErrorText = null
    ndcStatusText = "Analyzing label with on-device Gemini Nano..."
    coroutineScope.launch {
      val res = DrugBarcodeScanner.analyzeLabelFromImageUri(
        context = context,
        imageUri = imageUri,
        onStatusUpdate = { status -> ndcStatusText = status },
      )
      isLookingUpNdc = false
      res.fold(
        onSuccess = { parsed -> applyParsedMedicationLabel(parsed) },
        onFailure = { err -> ndcErrorText = err.localizedMessage ?: "Failed to analyze label." },
      )
    }
  }

  fun runGeminiLabelTextAnalysis(rawText: String) {
    isLookingUpNdc = true
    ndcErrorText = null
    ndcStatusText = "Analyzing label text with on-device Gemini Nano..."
    coroutineScope.launch {
      val res = DrugBarcodeScanner.analyzeLabelFromText(
        rawLabelText = rawText,
        onStatusUpdate = { status -> ndcStatusText = status },
      )
      isLookingUpNdc = false
      res.fold(
        onSuccess = { parsed -> applyParsedMedicationLabel(parsed) },
        onFailure = { err -> ndcErrorText = err.localizedMessage ?: "Failed to analyze label text." },
      )
    }
  }

  val cameraLabelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
  ) { success ->
    val uri = pendingLabelPhotoUri
    if (success && uri != null) {
      runGeminiLabelImageAnalysis(uri)
    }
  }

  val galleryLabelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) {
      runGeminiLabelImageAnalysis(uri)
    }
  }

  fun runNdcOrBarcodeLookup(rawQuery: String) {
    val query = rawQuery.trim()
    if (query.isEmpty()) return
    isLookingUpNdc = true
    ndcErrorText = null
    ndcStatusText = null
    coroutineScope.launch {
      val res = FdaNdcLookupService.lookupByNdc(query)
      isLookingUpNdc = false
      res.fold(
        onSuccess = { fda ->
          applyFdaResultToForm(fda)
        },
        onFailure = { err ->
          ndcMatched = false
          ndcErrorText = err.message ?: "NDC not found"
        },
      )
    }
  }

  val hasInitialPacing = initialMedication?.let {
    it.minIntervalMinutes != null || it.maxDailyDoses != null || it.maxDailyAmount != null
  } ?: false

  val hasInitialOptionalDetails = initialMedication?.let {
    !it.ndc11.isNullOrBlank() ||
      !it.manufacturer.isNullOrBlank() ||
      it.prescriberId != null ||
      !it.prescribedDateIso.isNullOrBlank() ||
      !it.notes.isNullOrBlank()
  } ?: false

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // Dialog Header
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = if (initialMedication == null) "Add Medication" else "Edit Medication",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) {
              Text("Cancel")
            }
            Button(
              onClick = {
                val doseAmt = defaultDoseAmountText.toDoubleOrNull() ?: 1.0
                val minIntervalMins = minIntervalHoursText.toDoubleOrNull()?.let { (it * 60.0).toInt() }
                val injDetails = if (form.isInjectable) {
                  InjectableDetails(
                    route = route,
                    concentrationMgPerMl = concentrationText.toDoubleOrNull(),
                    vialVolumeMl = vialVolumeText.toDoubleOrNull(),
                    syringeVolumeMl = syringeVolumeText.toDoubleOrNull(),
                    syringeDescription = syringeDesc.takeIf { it.isNotBlank() },
                    drawNeedleGauge = drawGaugeText.toIntOrNull(),
                    drawNeedleLengthInches = drawLengthText.toDoubleOrNull(),
                    injectNeedleGauge = injectGaugeText.toIntOrNull(),
                    injectNeedleLengthInches = injectLengthText.toDoubleOrNull(),
                  )
                } else null

                val medId = initialMedication?.id ?: java.util.UUID.randomUUID().toString()
                val savedMed = Medication(
                  id = medId,
                  formulaName = formulaName.trim(),
                  strength = strength.trim(),
                  form = form,
                  defaultDoseAmount = doseAmt,
                  defaultDoseUnit = defaultDoseUnit,
                  injectableDetails = injDetails,
                  siteRotationEnabled = form.supportsSiteRotation && siteRotationEnabled,
                  rotationSites = if (form.supportsSiteRotation && siteRotationEnabled) {
                    rotationSites.toList()
                  } else emptyList(),
                  minIntervalMinutes = minIntervalMins,
                  maxDailyDoses = maxDailyDosesText.toDoubleOrNull(),
                  maxDailyAmount = maxDailyAmountText.toDoubleOrNull(),
                  indicationConditionId = selectedConditionId,
                  indicationText = indicationText.trim(),
                  prescriberId = selectedPrescriberId,
                  prescribedDateIso = prescribedDateIso.trim().takeIf { it.isNotBlank() },
                  ndc11 = ndc11.trim().takeIf { it.isNotBlank() },
                  brandName = brandName.trim().takeIf { it.isNotBlank() },
                  manufacturer = manufacturer.trim().takeIf { it.isNotBlank() },
                  notes = notes.trim().takeIf { it.isNotBlank() },
                )
                onSave(savedMed)
              },
              enabled = formulaName.isNotBlank() && strength.isNotBlank(),
            ) {
              Text("Save")
            }
          }
        }

        HorizontalDivider()

        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          // 1. Top Label OCR Scanner & OpenFDA Cross-Reference Bar + Core Medication Identity
          Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                FilledTonalButton(
                  onClick = {
                    val uri = runCatching { DrugBarcodeScanner.createTempLabelPhotoUri(context) }.getOrNull()
                    if (uri != null) {
                      pendingLabelPhotoUri = uri
                      runCatching {
                        cameraLabelLauncher.launch(uri)
                      }.onFailure {
                        showLabelScanDialog = true
                      }
                    } else {
                      showLabelScanDialog = true
                    }
                  },
                  enabled = isGeminiNanoSupported && !isLookingUpNdc,
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                  Icon(
                    Icons.Default.DocumentScanner,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Scan Label")
                }
                OutlinedButton(
                  onClick = { showLabelScanDialog = true },
                  enabled = isGeminiNanoSupported && !isLookingUpNdc,
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                  Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Label Photo")
                }
                OutlinedButton(
                  onClick = {
                    runCrossReferenceLookup(
                      DrugCrossReferenceCriteria(
                        ndc = ndc11.takeIf { it.isNotBlank() },
                        formulaName = formulaName.takeIf { it.isNotBlank() },
                        brandName = brandName.takeIf { it.isNotBlank() },
                        strength = strength.takeIf { it.isNotBlank() },
                        form = form,
                        manufacturer = manufacturer.takeIf { it.isNotBlank() },
                      )
                    )
                  },
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                  if (isLookingUpNdc) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(14.dp),
                      strokeWidth = 2.dp,
                    )
                  } else {
                    Icon(
                      Icons.Default.Search,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                  }
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Match FDA")
                }
              }

              val activeFeedback = ndcErrorText ?: ndcStatusText
              if (!activeFeedback.isNullOrBlank()) {
                Text(
                  text = activeFeedback,
                  style = MaterialTheme.typography.labelSmall,
                  color = if (ndcErrorText != null) {
                    MaterialTheme.colorScheme.error
                  } else {
                    MaterialTheme.colorScheme.primary
                  },
                )
              }
            }
          }

          OutlinedTextField(
            value = brandName,
            onValueChange = { brandName = it },
            label = { Text("Brand Name (Optional)") },
            placeholder = { Text("e.g. Depo-Testosterone, Vyvanse") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          OutlinedTextField(
            value = formulaName,
            onValueChange = { formulaName = it },
            label = { Text("Formula / Generic Name *") },
            placeholder = { Text("e.g. Testosterone Cypionate") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = strength,
              onValueChange = { strength = it },
              label = { Text("Strength *") },
              placeholder = { Text("e.g. 200 mg/mL") },
              singleLine = true,
              modifier = Modifier.weight(0.45f),
            )
            DropdownSelectorField(
              label = "Form",
              selectedText = form.displayName,
              options = MedicationForm.entries,
              optionLabel = { it.displayName },
              onOptionSelected = { selectedForm ->
                form = selectedForm
                if (selectedForm.isInjectable && defaultDoseUnit == DoseUnit.TABLET) {
                  defaultDoseUnit = DoseUnit.MG
                  siteRotationEnabled = true
                } else if (selectedForm == MedicationForm.TOPICAL) {
                  defaultDoseUnit = DoseUnit.APPLICATION
                  siteRotationEnabled = true
                } else if (selectedForm == MedicationForm.CAPSULE && defaultDoseUnit == DoseUnit.TABLET) {
                  defaultDoseUnit = DoseUnit.CAPSULE
                }
              },
              modifier = Modifier.weight(0.55f),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = defaultDoseAmountText,
              onValueChange = { defaultDoseAmountText = it },
              label = { Text("Default Dose") },
              singleLine = true,
              modifier = Modifier.weight(0.45f),
            )
            DropdownSelectorField(
              label = "Unit",
              selectedText = "${defaultDoseUnit.symbol} (${defaultDoseUnit.displayName})",
              options = DoseUnit.entries,
              optionLabel = { "${it.symbol} — ${it.displayName}" },
              onOptionSelected = { defaultDoseUnit = it },
              modifier = Modifier.weight(0.55f),
            )
          }

          // Indication (Condition selector + optional notes)
          val selectedConditionObj = conditions.find { it.id == selectedConditionId }
          DropdownSelectorField(
            label = "Indication (Condition)",
            selectedText = selectedConditionObj?.name ?: "None linked",
            options = listOf<Condition?>(null) + conditions,
            optionLabel = { it?.name ?: "None linked" },
            onOptionSelected = { selectedConditionId = it?.id },
            modifier = Modifier.fillMaxWidth(),
          )

          // 2. Injectable / Vial Specifications (only when Form is Injectable)
          if (form.isInjectable) {
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Text(
                  text = "Injectable & Syringe / Needle Details",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )

                DropdownSelectorField(
                  label = "Administration Route (IM vs SC)",
                  selectedText = route.displayName,
                  options = InjectionRoute.entries,
                  optionLabel = { it.displayName },
                  onOptionSelected = { route = it },
                  modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  OutlinedTextField(
                    value = concentrationText,
                    onValueChange = { concentrationText = it },
                    label = { Text("Conc. (mg/mL)") },
                    placeholder = { Text("200") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  OutlinedTextField(
                    value = vialVolumeText,
                    onValueChange = { vialVolumeText = it },
                    label = { Text("Vial Size (mL)") },
                    placeholder = { Text("10") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  OutlinedTextField(
                    value = syringeVolumeText,
                    onValueChange = { syringeVolumeText = it },
                    label = { Text("Syringe (mL)") },
                    placeholder = { Text("1.0") },
                    singleLine = true,
                    modifier = Modifier.weight(0.45f),
                  )
                  OutlinedTextField(
                    value = syringeDesc,
                    onValueChange = { syringeDesc = it },
                    label = { Text("Syringe Type") },
                    placeholder = { Text("1 mL Luer-Lok") },
                    singleLine = true,
                    modifier = Modifier.weight(0.55f),
                  )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  OutlinedTextField(
                    value = drawGaugeText,
                    onValueChange = { drawGaugeText = it },
                    label = { Text("Draw Gauge (G)") },
                    placeholder = { Text("18") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  OutlinedTextField(
                    value = drawLengthText,
                    onValueChange = { drawLengthText = it },
                    label = { Text("Draw Length (in)") },
                    placeholder = { Text("1.0") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  OutlinedTextField(
                    value = injectGaugeText,
                    onValueChange = { injectGaugeText = it },
                    label = { Text("Inject Gauge (G)") },
                    placeholder = { Text("25") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  OutlinedTextField(
                    value = injectLengthText,
                    onValueChange = { injectLengthText = it },
                    label = { Text("Inject Length (in)") },
                    placeholder = { Text("1.0") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
          }

          // 3. Site Rotation Card (only when Form is Injectable or Topical)
          if (form.supportsSiteRotation) {
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "Automated Site Rotation",
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                      text = "Advances automatically from the actual site used",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Switch(
                    checked = siteRotationEnabled,
                    onCheckedChange = { siteRotationEnabled = it },
                  )
                }

                if (siteRotationEnabled) {
                  val presets = listOf(
                    "L/R Ventrogluteal (IM)" to Medication.PRESET_IM_GLUTE_SITES,
                    "L/R Deltoid (IM)" to Medication.PRESET_IM_DELT_SITES,
                    "L/R Thigh (IM)" to Medication.PRESET_IM_THIGH_SITES,
                    "6-Site Full Rotation (IM)" to Medication.PRESET_IM_FULL_ROTATION,
                    "4-Quadrant Abdomen (SC)" to Medication.PRESET_SC_ABDOMEN_SITES,
                    "L/R Arms & Shoulders (Topical)" to Medication.PRESET_TOPICAL_SITES,
                  )
                  DropdownSelectorField(
                    label = "Load Site Preset",
                    selectedText = "${rotationSites.size} sites configured",
                    options = presets,
                    optionLabel = { it.first },
                    onOptionSelected = { (_, siteList) ->
                      rotationSites.clear()
                      rotationSites.addAll(siteList)
                    },
                    modifier = Modifier.fillMaxWidth(),
                  )

                  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rotationSites.forEachIndexed { idx, site ->
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Text(
                          text = "${idx + 1}. $site",
                          style = MaterialTheme.typography.bodyMedium,
                          modifier = Modifier.weight(1f),
                        )
                        IconButton(
                          onClick = {
                            if (idx > 0) {
                              val item = rotationSites.removeAt(idx)
                              rotationSites.add(idx - 1, item)
                            }
                          },
                          enabled = idx > 0,
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                          onClick = {
                            if (idx < rotationSites.lastIndex) {
                              val item = rotationSites.removeAt(idx)
                              rotationSites.add(idx + 1, item)
                            }
                          },
                          enabled = idx < rotationSites.lastIndex,
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                          onClick = { rotationSites.removeAt(idx) },
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(Icons.Default.Close, contentDescription = "Remove site", modifier = Modifier.size(16.dp))
                        }
                      }
                    }
                  }

                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    OutlinedTextField(
                      value = newCustomSite,
                      onValueChange = { newCustomSite = it },
                      label = { Text("Add Custom Site") },
                      singleLine = true,
                      modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                      onClick = {
                        if (newCustomSite.isNotBlank()) {
                          rotationSites.add(newCustomSite.trim())
                          newCustomSite = ""
                        }
                      },
                    ) {
                      Text("Add")
                    }
                  }
                }
              }
            }
          }

          // 4. Collapsible Pacing & Daily Limits Section
          val pacingSummary = listOfNotNull(
            minIntervalHoursText.takeIf { it.isNotBlank() }?.let { "Min ${it}h interval" },
            maxDailyDosesText.takeIf { it.isNotBlank() }?.let { "Max $it doses/day" },
          ).joinToString(" • ").ifEmpty { "Minimum time between doses & daily limits" }

          CollapsibleFormSection(
            title = "Pacing & Daily Limits (Optional)",
            subtitle = pacingSummary,
            initiallyExpanded = hasInitialPacing,
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedTextField(
                value = minIntervalHoursText,
                onValueChange = { minIntervalHoursText = it },
                label = { Text("Min Interval (Hours)") },
                placeholder = { Text("e.g. 4") },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
              OutlinedTextField(
                value = maxDailyDosesText,
                onValueChange = { maxDailyDosesText = it },
                label = { Text("Max Daily Doses") },
                placeholder = { Text("e.g. 2") },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
            }
            OutlinedTextField(
              value = maxDailyAmountText,
              onValueChange = { maxDailyAmountText = it },
              label = { Text("Max Daily Amount (${defaultDoseUnit.symbol})") },
              placeholder = { Text("Optional total daily limit") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          // 5. Collapsible Optional Rx & Drug Details Section (NDC-11, Manufacturer, Prescriber, Notes)
          val optionalSummary = listOfNotNull(
            ndc11.takeIf { it.isNotBlank() }?.let { "NDC $it" },
            manufacturer.takeIf { it.isNotBlank() },
            prescribers.find { it.id == selectedPrescriberId }?.name,
          ).joinToString(" • ").ifEmpty { "NDC-11, manufacturer, prescriber, prescribed date & notes" }

          CollapsibleFormSection(
            title = "Optional Rx & Drug Details",
            subtitle = optionalSummary,
            initiallyExpanded = hasInitialOptionalDetails,
          ) {
            OutlinedTextField(
              value = ndc11,
              onValueChange = {
                ndc11 = it
                ndcErrorText = null
                ndcStatusText = null
                ndcMatched = false
              },
              label = { Text("NDC-11 (Optional)") },
              placeholder = { Text("e.g. 00009-0085-10") },
              singleLine = true,
              trailingIcon = {
                if (ndc11.isNotBlank()) {
                  TextButton(
                    onClick = { runNdcOrBarcodeLookup(ndc11) }
                  ) {
                    if (ndcMatched) {
                      Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text("Lookup")
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = manufacturer,
              onValueChange = { manufacturer = it },
              label = { Text("Manufacturer") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )

            val selectedDoc = prescribers.find { it.id == selectedPrescriberId }
            DropdownSelectorField(
              label = "Prescriber",
              selectedText = selectedDoc?.let { if (it.isPcp) "${it.name} (PCP)" else it.name } ?: "None selected",
              options = listOf<Prescriber?>(null) + prescribers,
              optionLabel = { doc ->
                doc?.let { if (it.isPcp) "${it.name} (PCP)" else it.name } ?: "None selected"
              },
              onOptionSelected = { selectedPrescriberId = it?.id },
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = prescribedDateIso,
              onValueChange = { prescribedDateIso = it },
              label = { Text("Prescribed Date (YYYY-MM-DD)") },
              placeholder = { Text("2026-01-15") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = indicationText,
              onValueChange = { indicationText = it },
              label = { Text("Indication Notes") },
              placeholder = { Text("Supplemental indication details") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = notes,
              onValueChange = { notes = it },
              label = { Text("Instructions / Notes") },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }
  }

  // Label OCR Photo / Paste Dialog (for pharmacy vials, compounded bottles, and OTC labels)
  if (showLabelScanDialog) {
    AlertDialog(
      onDismissRequest = { showLabelScanDialog = false },
      title = { Text("Scan or Paste Medication Label Text") },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = "Pharmacy prescription vials, compounded bottles, and OTC containers print drug name, strength, manufacturer (MFG), NDC, and prescriber in text rather than an FDA barcode.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilledTonalButton(
              onClick = {
                showLabelScanDialog = false
                val uri = runCatching { DrugBarcodeScanner.createTempLabelPhotoUri(context) }.getOrNull()
                if (uri != null) {
                  pendingLabelPhotoUri = uri
                  cameraLabelLauncher.launch(uri)
                }
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Camera")
            }
            OutlinedButton(
              onClick = {
                showLabelScanDialog = false
                galleryLabelLauncher.launch("image/*")
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Photos")
            }
          }
          HorizontalDivider()
          OutlinedTextField(
            value = manualLabelInput,
            onValueChange = { manualLabelInput = it },
            label = { Text("Or Paste / Test Printed Label Text") },
            placeholder = {
              Text("ANASTROZOLE 1MG TABLET\nMFG ZYDUS\nTAKE 1 TABLET BY MOUTH")
            },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            text = "Sample Real Bottle Labels:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            AssistChip(
              onClick = {
                manualLabelInput = """
                  CASEY BROWN
                  ANASTROZOLE 1MG TABLET
                  MFG ZYDUS
                  TAKE 1 TABLET BY MOUTH 1 TIME A WEEK
                  2 REFILLS BEFORE 04/05/27 QTY 12
                """.trimIndent()
              },
              label = { Text("Pharmacy Vial (Anastrozole Zydus)") },
            )
            AssistChip(
              onClick = {
                manualLabelInput = """
                  CASEY BROWN
                  Finasteride 1.2mg Minoxidil 3mg Biotin 2.5mg
                  Vitamin B5 11mg Vitamin B6 2.5mg Vitamin C 100mg Chewable Tablet
                  Chew or swallow 1 tablet by mouth daily
                  USE BY: 11/07/2026
                  PRESCRIBED BY: Vacek, Jeffrey
                  LOT #: DPTSESKSLIEE
                  XeCare LLC
                  THIS IS A COMPOUND PRODUCT
                """.trimIndent()
              },
              label = { Text("Compound (XeCare)") },
            )
            AssistChip(
              onClick = {
                manualLabelInput = """
                  NDC 41167-4122-2
                  NON-DROWSY
                  Allegra ALLERGY 24HR
                  fexofenadine HCl tablet
                  180 mg antihistamine
                  90 GELCAPS
                  Dist. By Chattem, Inc.
                  LOT 43336
                  EXP 10/2027
                """.trimIndent()
              },
              label = { Text("OTC (Allegra 180mg)") },
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val raw = manualLabelInput.trim()
            showLabelScanDialog = false
            if (raw.isNotEmpty()) {
              runGeminiLabelTextAnalysis(raw)
            }
          },
          enabled = manualLabelInput.isNotBlank(),
        ) {
          Text("Parse & Cross-Reference")
        }
      },
      dismissButton = {
        TextButton(onClick = { showLabelScanDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }

  // Disambiguation Dialog when multiple OpenFDA packages/strengths/manufacturers match
  disambiguationPrompt?.let { prompt ->
    CrossReferenceDisambiguationDialog(
      reason = prompt.reason,
      candidates = prompt.candidates,
      onSelectCandidate = { chosen ->
        disambiguationPrompt = null
        val signals = chosen.matchedSignals.joinToString(", ")
        applyFdaResultToForm(
          fda = chosen.result,
          statusSummary = "Selected FDA match (${chosen.result.ndc11} • $signals)",
        )
      },
      onKeepWithoutNdc = {
        disambiguationPrompt = null
        ndcStatusText = "Kept entered/scanned fields without assigning a specific manufacturer NDC"
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrossReferenceDisambiguationDialog(
  reason: String,
  candidates: List<CrossReferencedFdaCandidate>,
  onSelectCandidate: (CrossReferencedFdaCandidate) -> Unit,
  onKeepWithoutNdc: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onKeepWithoutNdc,
    title = { Text("Confirm Exact Medication Match") },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = reason,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidates.forEach { cand ->
          val res = cand.result
          Card(
            onClick = { onSelectCandidate(cand) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = if (!res.brandName.isNullOrBlank()) {
                    "${res.brandName} (${res.formulaName})"
                  } else {
                    res.formulaName
                  },
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.weight(1f),
                )
                Text(
                  text = res.strength,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.SemiBold,
                )
              }
              Text(
                text = "${res.form.displayName} • Mfg: ${res.manufacturer ?: "Unspecified"} • NDC: ${res.ndc11}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (cand.matchedSignals.isNotEmpty()) {
                Text(
                  text = "Matched: ${cand.matchedSignals.joinToString(" • ")}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
              if (cand.missingOrDifferentSignals.isNotEmpty()) {
                Text(
                  text = "Note: ${cand.missingOrDifferentSignals.joinToString(" • ")}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onKeepWithoutNdc) {
        Text("Keep Fields Only (No Specific NDC)")
      }
    },
  )
}

/**
 * Dedicated Schedule Editor Dialog separated from Medication entry.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditorDialog(
  medication: Medication,
  initialSchedule: MedicationSchedule?,
  buckets: List<TimeOfDayBucket>,
  onDismiss: () -> Unit,
  onSave: (MedicationSchedule) -> Unit,
) {
  var frequencyType by rememberSaveable {
    mutableStateOf(initialSchedule?.frequencyType ?: ScheduleFrequencyType.DAILY)
  }
  val selectedBucketIds = remember {
    mutableStateListOf<String>().apply {
      addAll(initialSchedule?.bucketIds ?: listOf(TimeOfDayBucket.BUCKET_MORNING))
    }
  }
  var doseAmountText by rememberSaveable {
    mutableStateOf(
      formatNum(initialSchedule?.doseAmount ?: medication.defaultDoseAmount)
    )
  }
  var doseUnit by rememberSaveable {
    mutableStateOf(initialSchedule?.doseUnit ?: medication.defaultDoseUnit)
  }
  var intervalDaysText by rememberSaveable {
    mutableStateOf((initialSchedule?.intervalDays ?: 7).toString())
  }
  var anchorDateIso by rememberSaveable {
    mutableStateOf(initialSchedule?.anchorDateIso ?: LocalDate.now().toString())
  }
  val selectedDaysOfWeek = remember {
    mutableStateListOf<Int>().apply {
      addAll(initialSchedule?.daysOfWeek ?: setOf(1, 2, 3, 4, 5, 6, 7))
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (initialSchedule == null) {
          "Add Schedule for ${medication.displayTitle}"
        } else {
          "Edit Schedule for ${medication.displayTitle}"
        }
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        DropdownSelectorField(
          label = "Schedule Frequency",
          selectedText = frequencyType.displayName,
          options = ScheduleFrequencyType.entries,
          optionLabel = { it.displayName },
          onOptionSelected = { frequencyType = it },
          modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = doseAmountText,
            onValueChange = { doseAmountText = it },
            label = { Text("Dose Amount") },
            singleLine = true,
            modifier = Modifier.weight(0.45f),
          )
          DropdownSelectorField(
            label = "Unit",
            selectedText = doseUnit.symbol,
            options = DoseUnit.entries,
            optionLabel = { "${it.symbol} — ${it.displayName}" },
            onOptionSelected = { doseUnit = it },
            modifier = Modifier.weight(0.55f),
          )
        }

        if (frequencyType != ScheduleFrequencyType.AS_NEEDED) {
          Text(
            text = "Time-of-Day Dose Windows:",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            buckets.sortedBy { it.sortOrder }.forEach { b ->
              val isSelected = selectedBucketIds.contains(b.id)
              FilterChip(
                selected = isSelected,
                onClick = {
                  if (isSelected) selectedBucketIds.remove(b.id) else selectedBucketIds.add(b.id)
                },
                label = { Text("${b.name} (${b.formattedTime()})") },
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }
        }

        if (frequencyType == ScheduleFrequencyType.EVERY_N_DAYS ||
          frequencyType == ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
              value = intervalDaysText,
              onValueChange = { intervalDaysText = it },
              label = { Text("Every N Days") },
              placeholder = { Text("7") },
              singleLine = true,
              modifier = Modifier.weight(0.45f),
            )
            OutlinedTextField(
              value = anchorDateIso,
              onValueChange = { anchorDateIso = it },
              label = { Text("Start Date") },
              placeholder = { Text("YYYY-MM-DD") },
              singleLine = true,
              modifier = Modifier.weight(0.55f),
            )
          }
        }

        if (frequencyType == ScheduleFrequencyType.DAYS_OF_WEEK) {
          Text("Days of Week:", style = MaterialTheme.typography.labelLarge)
          val dayNames = listOf(
            1 to "Mon",
            2 to "Tue",
            3 to "Wed",
            4 to "Thu",
            5 to "Fri",
            6 to "Sat",
            7 to "Sun",
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dayNames.forEach { (dayNum, label) ->
              val isSelected = selectedDaysOfWeek.contains(dayNum)
              FilterChip(
                selected = isSelected,
                onClick = {
                  if (isSelected) selectedDaysOfWeek.remove(dayNum) else selectedDaysOfWeek.add(dayNum)
                },
                label = { Text(label) },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val doseAmt = doseAmountText.toDoubleOrNull() ?: medication.defaultDoseAmount
          val saved = MedicationSchedule(
            id = initialSchedule?.id ?: java.util.UUID.randomUUID().toString(),
            medicationId = medication.id,
            frequencyType = frequencyType,
            bucketIds = if (frequencyType == ScheduleFrequencyType.AS_NEEDED) {
              emptyList()
            } else {
              selectedBucketIds.toList().ifEmpty { listOf(TimeOfDayBucket.BUCKET_MORNING) }
            },
            intervalDays = intervalDaysText.toIntOrNull()?.coerceAtLeast(1) ?: 7,
            anchorDateIso = anchorDateIso.trim().ifBlank { LocalDate.now().toString() },
            daysOfWeek = selectedDaysOfWeek.toSet().ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) },
            doseAmount = doseAmt,
            doseUnit = doseUnit,
            enabled = true,
          )
          onSave(saved)
        }
      ) {
        Text("Save Schedule")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
