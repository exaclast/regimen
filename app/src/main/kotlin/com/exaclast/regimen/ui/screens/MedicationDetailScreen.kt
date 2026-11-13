@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.notifications.DoseAlarmTiming
import com.exaclast.regimen.ui.ScheduleText
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.InfoRow
import com.exaclast.regimen.ui.components.LogTimeDialog
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.components.groupListItemColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun MedicationDetailScreen(
  medicationId: String,
  snapshot: RegimenSnapshot,
  nowMillis: Long,
  todayIso: String,
  promptForSchedule: Boolean,
  /** The scheduled dose "Log dose" should complete, if one is still to do today. */
  dueDose: ScheduledDoseItem?,
  onTakeDose: (item: ScheduledDoseItem, site: String?, takenAtMillis: Long?, note: String?) -> Unit,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onAddSchedule: () -> Unit,
  onEditSchedule: (MedicationSchedule) -> Unit,
  onDeleteSchedule: (MedicationSchedule) -> Unit,
  onLogDose: (medication: Medication, site: String?, takenAtMillis: Long?, note: String?) -> Unit,
  onEditLog: (DoseLog) -> Unit,
  onOpenProvider: (String) -> Unit,
  onOpenIndication: (String) -> Unit,
) {
  val medication = snapshot.medications.find { it.id == medicationId }
  if (medication == null) {
    // Deleted (or never existed): leave the screen.
    LaunchedEffect(medicationId) { onBack() }
    return
  }
  val indications = snapshot.indications.filter { it.id in medication.indicationIds }
  val prescriber = snapshot.providers.find { it.id == medication.prescriberId }
  val schedules = snapshot.schedules.filter { it.medicationId == medication.id && it.deletedAtEpochMillis == null }
  val logs = snapshot.doseLogs
    .filter { it.medicationId == medication.id && it.status != DoseStatus.SNOOZED }
    .take(10)
  val nextSite = DoseEngine.suggestSite(medication, snapshot.doseLogs)
  val lastSite = DoseEngine.getLastSiteUsed(medication.id, snapshot.doseLogs)
  val pacing = DoseEngine.evaluatePacing(medication, snapshot.doseLogs, nowMillis, todayIso, snapshot.buckets)

  var menuOpen by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  var pacingPrompt by remember { mutableStateOf(false) }
  var choosingSite by remember { mutableStateOf(false) }
  var showSchedulePrompt by rememberSaveable { mutableStateOf(promptForSchedule) }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  val multiSite = medication.activeSites.size > 1

  // An extra dose is logged on its own; otherwise logging completes the dose that's due.
  var loggingExtra by remember { mutableStateOf(false) }
  // When the dose in progress was taken (null = now) and its note, picked in the time dialog.
  var loggingAt by remember { mutableStateOf<Long?>(null) }
  var loggingNote by remember { mutableStateOf<String?>(null) }
  var timeChoice by remember { mutableStateOf<TimeChoice?>(null) }
  val warning = pacing.warningText()

  // A dose logged at another time is paced against that time, not now.
  fun pacingFor(takenAtMillis: Long?) = if (takenAtMillis == null) {
    pacing
  } else {
    val day = Instant.ofEpochMilli(takenAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    DoseEngine.evaluatePacing(medication, snapshot.doseLogs, takenAtMillis, day, snapshot.buckets, backdated = true)
  }

  fun log(site: String?) {
    if (dueDose != null && !loggingExtra) {
      onTakeDose(dueDose, site, loggingAt, loggingNote)
    } else {
      onLogDose(medication, site, loggingAt, loggingNote)
    }
  }

  fun proceedWithoutPacingCheck() {
    if (multiSite) choosingSite = true else log(nextSite)
  }

  fun startLogging(extra: Boolean, takenAtMillis: Long? = null, note: String? = null) {
    loggingExtra = extra
    loggingAt = takenAtMillis
    loggingNote = note
    if (pacingFor(takenAtMillis).warningText() != null) pacingPrompt = true else proceedWithoutPacingCheck()
  }

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      MediumFlexibleTopAppBar(
        title = { Text(medication.primaryName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = medication.secondaryName?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
          IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit medication") }
          Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
              DropdownMenuItem(
                text = { Text("Delete medication") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { menuOpen = false; confirmDelete = true },
              )
            }
          }
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
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
    ) {
      item(key = "hero") {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          shape = MaterialTheme.shapes.extraLarge,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(medication.strength, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            val route = medication.injectableDetails?.route?.displayName
            Text(
              listOfNotNull(medication.form.displayName, route).joinToString(" · "),
              style = MaterialTheme.typography.bodyMedium,
            )
            Text("Default dose: ${medication.formattedDefaultDose()}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            HeroStatus(
              icon = if (warning != null) Icons.Default.Warning else Icons.Default.Check,
              text = warning ?: listOfNotNull(
                "Ready",
                pacing.maxDailyDoses?.let { "${pacing.dosesTakenToday} of ${formatNum(it)} today" },
              ).joinToString(" · "),
            )
            if (nextSite != null) {
              if (medication.rotatesSites) {
                HeroStatus(icon = Icons.Default.Autorenew, text = "Next site: $nextSite")
              } else {
                HeroStatus(icon = Icons.Default.Place, text = "Site: $nextSite")
              }
            }
            Spacer(Modifier.height(8.dp))
            val logLabel = when {
              dueDose == null -> "Log dose"
              (DoseAlarmTiming.windowStartMillis(dueDose.bucket, dueDose.dateIso) ?: 0L) > nowMillis ->
                "Log ${dueDose.bucket.name} dose early"
              else -> "Log ${dueDose.bucket.name} dose"
            }
            if (warning != null) {
              OutlinedButton(onClick = { startLogging(extra = false) }, modifier = Modifier.fillMaxWidth()) {
                Text("$logLabel anyway…")
              }
            } else {
              Button(onClick = { startLogging(extra = false) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(logLabel)
              }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
              TextButton(onClick = { timeChoice = TimeChoice(extra = false, startWithOtherTime = true) }) {
                Text("Other time")
              }
              if (dueDose != null) {
                TextButton(onClick = { timeChoice = TimeChoice(extra = true, startWithOtherTime = false) }) {
                  Text("Extra dose")
                }
              }
            }
          }
        }
      }

      item(key = "schedules") {
        SectionHeader("Schedules") {
          TextButton(onClick = onAddSchedule) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add")
          }
        }
        GroupedColumn {
          if (schedules.isEmpty()) {
            GroupItem(index = 0, count = 1) {
              ListItem(
                supportingContent = { Text("Shows under \"As needed\" on Today") },
                colors = groupListItemColors(),
              ) { Text("No schedule") }
            }
          }
          schedules.forEachIndexed { i, sched ->
            GroupItem(index = i, count = schedules.size, onClick = { onEditSchedule(sched) }) {
              ScheduleRow(sched, snapshot, onEdit = { onEditSchedule(sched) }, onDelete = { onDeleteSchedule(sched) })
            }
          }
        }
      }

      medication.injectableDetails?.let { inj ->
        item(key = "injection") {
          SectionHeader("Injection")
          val pen = medication.form.isPrefilledInjector
          val rows = buildList {
            add("Route" to inj.route.displayName)
            inj.concentrationMgPerMl?.let { add("Concentration" to "${formatNum(it)} mg/mL") }
            inj.vialVolumeMl?.let { add((if (pen) "Device volume" else "Vial size") to "${formatNum(it)} mL") }
            if (pen) {
              inj.formattedInjectNeedle()?.let { add("Pen needle" to it) }
            } else {
              inj.calculateVolumeMl(medication.defaultDoseAmount, medication.defaultDoseUnit)?.let {
                add("Draw volume" to "${formatNum(it)} mL (${formatNum(it * 100.0)} units on a U-100 syringe)")
              }
              val syringe = listOfNotNull(inj.syringeVolumeMl?.let { "${formatNum(it)} mL" }, inj.syringeDescription)
              if (syringe.isNotEmpty()) add("Syringe" to syringe.joinToString(" · "))
              inj.formattedDrawNeedle()?.let { add("Draw needle" to it) }
              inj.formattedInjectNeedle()?.let { add("Injection needle" to it) }
            }
          }
          GroupedColumn {
            rows.forEachIndexed { i, (label, value) ->
              GroupItem(index = i, count = rows.size) { InfoRow(label, value) }
            }
          }
        }
      }

      if (medication.tracksSites) {
        item(key = "sites") {
          val rotates = medication.rotatesSites
          SectionHeader(if (rotates) "Site rotation" else "Sites")
          val sites = medication.activeSites
          GroupedColumn {
            sites.forEachIndexed { i, site ->
              // Recording has no "next": the suggestion is the last site, so only that is tagged.
              val tag = when {
                rotates && site.equals(nextSite, ignoreCase = true) -> "Next"
                site.equals(lastSite, ignoreCase = true) -> "Last used"
                else -> null
              }
              GroupItem(index = i, count = sites.size) {
                ListItem(
                  leadingContent = if (rotates) {
                    { Text("${i + 1}", style = MaterialTheme.typography.titleMedium) }
                  } else {
                    null
                  },
                  trailingContent = tag?.let { { SiteTag(it, highlighted = it == "Next") } },
                  colors = groupListItemColors(),
                ) { Text(site) }
              }
            }
          }
        }
      }

      item(key = "limits") {
        SectionHeader("Pacing limits")
        val rows = listOf(
          "Minimum time between doses" to
            (medication.minIntervalMinutes?.let { DoseEngine.formatDurationMinutes(it) } ?: "None"),
          "Daily dose limit" to (medication.maxDailyDoses?.let { doseCount(it) } ?: "None"),
          "Daily amount limit" to
            (medication.maxDailyAmount?.let { "${formatNum(it)} ${medication.defaultDoseUnit.symbol}" } ?: "None"),
        )
        GroupedColumn {
          rows.forEachIndexed { i, (label, value) -> GroupItem(index = i, count = rows.size) { InfoRow(label, value) } }
        }
      }

      item(key = "details") {
        SectionHeader("Details")
        val rows = buildList<Triple<String, String, (() -> Unit)?>> {
          if (snapshot.tracksIndications) {
            if (indications.isEmpty()) {
              add(Triple("Indication", "Not linked", null))
            } else {
              indications.forEach { c -> add(Triple("Indication", c.name) { onOpenIndication(c.id) }) }
            }
          }
          if (medication.indicationText.isNotBlank()) add(Triple("Indication notes", medication.indicationText, null))
          if (snapshot.tracksProviders) {
            add(
              Triple(
                "Prescriber",
                prescriber?.let { if (it.isPcp) "${it.name} (PCP)" else it.name } ?: "Not set",
                prescriber?.let { p -> { onOpenProvider(p.id) } },
              )
            )
          }
          UiFormat.dateIso(medication.prescribedDateIso)?.let { add(Triple("Prescribed", it, null)) }
          medication.ndc11?.takeIf { it.isNotBlank() }?.let { add(Triple("NDC", it, null)) }
          medication.manufacturer?.takeIf { it.isNotBlank() }?.let { add(Triple("Manufacturer", it, null)) }
          medication.notes?.takeIf { it.isNotBlank() }?.let { add(Triple("Notes", it, null)) }
        }
        GroupedColumn {
          rows.forEachIndexed { i, (label, value, click) ->
            GroupItem(index = i, count = rows.size, onClick = click) { InfoRow(label, value) }
          }
        }
      }

      item(key = "history") {
        SectionHeader("Recent doses")
        GroupedColumn {
          if (logs.isEmpty()) {
            GroupItem(index = 0, count = 1) {
              ListItem(
                colors = groupListItemColors(),
              ) { Text("No doses logged yet") }
            }
          }
          logs.forEachIndexed { i, log ->
            GroupItem(index = i, count = logs.size, onClick = { onEditLog(log) }) {
              ListItem(
                supportingContent = {
                  Text(
                    listOfNotNull(
                      UiFormat.dateTime(log.actionTimestampMillis),
                      log.siteUsed,
                      log.notes?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                colors = groupListItemColors(),
              ) {
                  Text("${log.status.displayName} · ${formatNum(log.amountTaken)} ${log.amountUnit.symbol}")
                }
            }
          }
        }
      }
    }
  }

  if (confirmDelete) {
    ConfirmDialog(
      title = "Delete ${medication.primaryName}?",
      text = "This removes the medication and its schedules. Dose history is kept.",
      confirmLabel = "Delete",
      destructive = true,
      onConfirm = {
        confirmDelete = false
        onDelete()
      },
      onDismiss = { confirmDelete = false },
    )
  }

  timeChoice?.let { choice ->
    val duePart = dueDose.takeIf { !choice.extra }
    LogTimeDialog(
      title = duePart?.let { "Log ${it.bucket.name} dose" } ?: "Log ${medication.primaryName}",
      doseLabel = duePart?.formattedDoseInstruction() ?: medication.formattedDefaultDose(),
      defaultDateIso = todayIso,
      startWithOtherTime = choice.startWithOtherTime,
      onDismiss = { timeChoice = null },
      onConfirm = { takenAtMillis, note ->
        timeChoice = null
        startLogging(choice.extra, takenAtMillis, note)
      },
    )
  }

  if (pacingPrompt) {
    val shownPacing = pacingFor(loggingAt)
    AlertDialog(
      onDismissRequest = { pacingPrompt = false },
      icon = { Icon(Icons.Default.Warning, contentDescription = null) },
      title = { Text("Log ${medication.primaryName} anyway?") },
      text = {
        Text(
          listOfNotNull(
            shownPacing.nextAllowedText()?.let { "The next dose isn't due until $it." },
            if (shownPacing.isDailyLimitReached) "You've reached the daily limit." else null,
            "These limits come from this medication's settings.",
          ).joinToString(" ")
        )
      },
      confirmButton = {
        Button(onClick = {
          pacingPrompt = false
          proceedWithoutPacingCheck()
        }) { Text("Log anyway") }
      },
      dismissButton = { TextButton(onClick = { pacingPrompt = false }) { Text("Cancel") } },
    )
  }

  if (choosingSite) {
    SiteSelectionDialog(
      medication = medication,
      suggestedSite = nextSite,
      lastSiteUsed = lastSite,
      onDismiss = { choosingSite = false },
      onConfirm = { site ->
        choosingSite = false
        log(site)
      },
    )
  }

  if (showSchedulePrompt) {
    AlertDialog(
      onDismissRequest = { showSchedulePrompt = false },
      title = { Text("Add a schedule?") },
      text = { Text("Pick when ${medication.primaryName} is due, or skip to track it as needed.") },
      confirmButton = {
        Button(onClick = {
          showSchedulePrompt = false
          onAddSchedule()
        }) { Text("Add schedule") }
      },
      dismissButton = { TextButton(onClick = { showSchedulePrompt = false }) { Text("As needed") } },
    )
  }
}

/** Which time dialog to open from the medication page: for an extra dose, or for the dose that's due. */
private data class TimeChoice(val extra: Boolean, val startWithOtherTime: Boolean)

private fun doseCount(value: Double): String {
  val n = formatNum(value)
  return if (value == 1.0) "$n dose per day" else "$n doses per day"
}

@Composable
private fun HeroStatus(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun SiteTag(text: String, highlighted: Boolean) {
  Surface(
    color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    shape = MaterialTheme.shapes.small,
  ) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
  }
}

@Composable
private fun ScheduleRow(
  schedule: MedicationSchedule,
  snapshot: RegimenSnapshot,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  val windows = snapshot.buckets.chronological().filter { it.id in schedule.bucketIds }
  val detail = buildList {
    if (windows.isNotEmpty()) add(windows.joinToString(", ") { "${it.name} ${it.formattedTime()}" })
    val today = LocalDate.now()
    val start = schedule.startDateOrNull()
    val end = schedule.endDateOrNull()
    fun windowName(id: String?) = id?.let { wid -> snapshot.buckets.find { it.id == wid }?.name }
    windowName(schedule.startBucketId)?.takeIf { start != null && !start.isBefore(today) }
      ?.let { add("first dose: $it") }
    windowName(schedule.endBucketId)?.takeIf { end != null && !end.isBefore(today) }
      ?.let { add("last dose: $it") }
    if (schedule.usesDateRange) {
      when {
        end != null && end.isBefore(today) -> add("Ended ${UiFormat.date(end, withWeekday = false)}")
        start != null && start.isAfter(today) && end != null ->
          add("${UiFormat.date(start, withWeekday = false)} – ${UiFormat.date(end, withWeekday = false)}")
        start != null && start.isAfter(today) -> add("Starts ${UiFormat.date(start, withWeekday = false)}")
        end != null -> add("Until ${UiFormat.date(end, withWeekday = false)}")
        start != null &&
          (schedule.frequencyType == ScheduleFrequencyType.EVERY_N_DAYS ||
            schedule.frequencyType == ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE) ->
          add("from ${UiFormat.date(start, withWeekday = false)}")
      }
    }
    if (schedule.frequencyType == ScheduleFrequencyType.SPECIFIC_DATES && schedule.specificDatesIso.isNotEmpty()) {
      val nextUpcoming = schedule.specificDatesIso.sorted().firstOrNull { it >= LocalDate.now().toString() }
      if (nextUpcoming != null) {
        UiFormat.dateIso(nextUpcoming)?.let { add("Next: $it") }
      }
    }
    if (!schedule.enabled) add("Paused")
    if (schedule.hasNoValidWindow(snapshot.buckets)) add("No dose window: no doses or reminders until you pick one")
  }.joinToString(" · ")
  ListItem(
    supportingContent = if (detail.isNotEmpty()) ({ Text(detail) }) else null,
    trailingContent = {
      Box {
        IconButton(onClick = { menuOpen = true }) {
          Icon(Icons.Default.MoreVert, contentDescription = "Schedule options")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text("Edit") },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = { menuOpen = false; onEdit() },
          )
          DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = { menuOpen = false; onDelete() },
          )
        }
      }
    },
    colors = groupListItemColors(),
  ) {
      Text("${formatNum(schedule.doseAmount)} ${schedule.doseUnit.symbol} · ${ScheduleText.frequency(schedule)}")
    }
}
