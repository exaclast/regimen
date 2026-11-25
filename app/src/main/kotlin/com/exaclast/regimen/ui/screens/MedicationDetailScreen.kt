@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.exaclast.regimen.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.Adjustments
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.DoseAmount
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.NeedleHub
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.ItemSource
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.RegimenItem
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.Supplement
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.notifications.DoseAlarmTiming
import com.exaclast.regimen.ui.ScheduleText
import com.exaclast.regimen.model.texts
import com.exaclast.regimen.ui.components.AdaptiveMediumTopAppBar
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.CenteredContentBox
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.InfoRow
import com.exaclast.regimen.ui.components.LogTimeDialog
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.components.addHorizontal
import com.exaclast.regimen.ui.components.groupListItemColors
import com.exaclast.regimen.ui.components.doseText
import com.exaclast.regimen.ui.components.label
import com.exaclast.regimen.ui.components.nextAllowed
import com.exaclast.regimen.ui.components.warning
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
  onLogDose: (medication: RegimenItem, site: String?, takenAtMillis: Long?, note: String?) -> Unit,
  onEditLog: (DoseLog) -> Unit,
  onOpenProvider: (String) -> Unit,
  onOpenIndication: (String) -> Unit,
  onPlanChange: () -> Unit,
  onOpenChange: (adjustmentId: String) -> Unit,
) {
  val stored = snapshot.medications.find { it.id == medicationId }
  if (stored == null) {
    // Deleted (or never existed): leave the screen.
    LaunchedEffect(medicationId) { onBack() }
    return
  }
  val today = runCatching { LocalDate.parse(todayIso) }.getOrDefault(LocalDate.now())
  // Everything shown is as of today; the stored record keeps the starting values and the changes.
  val medication = Adjustments.asOf(stored, today)
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
  var showPastSchedules by rememberSaveable { mutableStateOf(false) }
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
  var timeChoiceOpen by rememberSaveable { mutableStateOf(false) }
  var timeChoiceExtra by rememberSaveable { mutableStateOf(false) }
  var timeChoiceOther by rememberSaveable { mutableStateOf(false) }
  val warning = pacing.warning()
  val texts = LocalResources.current.texts()

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
    if (pacingFor(takenAtMillis).warningText(texts) != null) pacingPrompt = true else proceedWithoutPacingCheck()
  }

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      AdaptiveMediumTopAppBar(
        title = { Text(medication.primaryName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        subtitle = (medication.secondaryName ?: medication.manufacturer?.takeIf { medication.isSupplement && it.isNotBlank() })
          ?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
        },
        actions = {
          IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.med_edit)) }
          Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more)) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.med_delete)) },
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
    CenteredContentBox(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) { extra ->
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp).addHorizontal(extra),
      ) {
      item(key = "hero") {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          shape = MaterialTheme.shapes.extraLarge,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (medication.strength.isNotBlank()) {
              Text(medication.strength, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            val route = medication.injectableOrNull?.route?.label()
            val sourceTag = medication.source.label().takeIf { medication.isSupplement || medication.source == ItemSource.OTC }
            Text(
              listOfNotNull(medication.form.label(), route, sourceTag).joinToString(" · "),
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.med_default_dose, medication.formattedDefaultDose()), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            HeroStatus(
              icon = if (warning != null) Icons.Default.Warning else Icons.Default.Check,
              text = warning ?: listOfNotNull(
                stringResource(R.string.status_ready),
                pacing.maxDailyDoses?.let { stringResource(R.string.prn_daily_note_today, pacing.dosesTakenToday, formatNum(it)) },
              ).joinToString(" · "),
            )
            if (nextSite != null) {
              if (medication.rotatesSites) {
                HeroStatus(icon = Icons.Default.Autorenew, text = stringResource(R.string.med_next_site, nextSite))
              } else {
                HeroStatus(icon = Icons.Default.Place, text = stringResource(R.string.dose_site_line, nextSite))
              }
            }
            Spacer(Modifier.height(8.dp))
            val logLabel = when {
              dueDose == null -> stringResource(R.string.action_log_dose)
              (DoseAlarmTiming.windowStartMillis(dueDose.bucket, dueDose.dateIso) ?: 0L) > nowMillis ->
                stringResource(R.string.med_log_window_dose_early, dueDose.bucket.name)
              else -> stringResource(R.string.med_log_window_dose, dueDose.bucket.name)
            }
            if (warning != null) {
              OutlinedButton(onClick = { startLogging(extra = false) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.prn_log_anyway, logLabel), textAlign = TextAlign.Center)
              }
            } else {
              Button(onClick = { startLogging(extra = false) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(logLabel, textAlign = TextAlign.Center)
              }
            }
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
              val buttonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
              TextButton(
                onClick = { timeChoiceExtra = false; timeChoiceOther = true; timeChoiceOpen = true },
                colors = buttonColors,
              ) {
                Text(stringResource(R.string.log_time_other))
              }
              if (dueDose != null) {
                TextButton(
                  onClick = { timeChoiceExtra = true; timeChoiceOther = false; timeChoiceOpen = true },
                  colors = buttonColors,
                ) {
                  Text(stringResource(R.string.med_extra_dose))
                }
              }
            }
          }
        }
      }

      item(key = "schedules") {
        val (pastSchedules, currentSchedules) = schedules.partition { it.hasEndedBefore(today) }
        val sortedPastSchedules = pastSchedules.sortedByDescending { it.lastDayOrNull() }
        val visibleSchedules = currentSchedules.ifEmpty { sortedPastSchedules }
        SectionHeader(stringResource(R.string.med_schedules)) {
          TextButton(onClick = onAddSchedule) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.action_add))
          }
        }
        GroupedColumn {
          if (visibleSchedules.isEmpty()) {
            GroupItem(index = 0, count = 1) {
              ListItem(
                supportingContent = { Text(stringResource(R.string.med_no_schedule_help)) },
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.med_no_schedule)) }
            }
          }
          visibleSchedules.forEachIndexed { i, sched ->
            // Today's dose, and the next step if one is planned.
            val next = Adjustments.sorted(stored).firstOrNull { a ->
              a.effectiveDateOrNull()!!.isAfter(today) && a.scheduleDoses.any { it.scheduleId == sched.id }
            }
            val upcoming = next?.let { a ->
              val dose = Adjustments.doseOn(stored, sched, a.effectiveDateOrNull()!!)
              stringResource(R.string.med_dose_from, formatNum(dose.amount), dose.unit.symbol, UiFormat.date(a.effectiveDateOrNull()!!, withWeekday = false))
            }
            GroupItem(index = i, count = visibleSchedules.size, onClick = { onEditSchedule(sched) }) {
              ScheduleRow(
                schedule = sched,
                dose = Adjustments.doseOn(stored, sched, today),
                upcoming = upcoming,
                snapshot = snapshot,
                onEdit = { onEditSchedule(sched) },
                onDelete = { onDeleteSchedule(sched) },
              )
            }
          }
        }
        if (currentSchedules.isNotEmpty() && sortedPastSchedules.isNotEmpty()) {
          SectionHeader(
            title = stringResource(R.string.med_past_schedules, sortedPastSchedules.size),
            action = {
              TextButton(onClick = { showPastSchedules = !showPastSchedules }) {
                Text(stringResource(if (showPastSchedules) R.string.action_hide else R.string.action_show))
              }
            },
          )
          if (showPastSchedules) {
            GroupedColumn {
              sortedPastSchedules.forEachIndexed { i, sched ->
                GroupItem(index = i, count = sortedPastSchedules.size, onClick = { onEditSchedule(sched) }) {
                  ScheduleRow(
                    schedule = sched,
                    dose = Adjustments.doseOn(stored, sched, sched.lastDayOrNull() ?: today),
                    upcoming = null,
                    snapshot = snapshot,
                    onEdit = { onEditSchedule(sched) },
                    onDelete = { onDeleteSchedule(sched) },
                  )
                }
              }
            }
          }
        }
      }

      if (medication is Supplement && medication.ingredients.isNotEmpty()) {
        item(key = "ingredients") {
          SectionHeader(stringResource(R.string.med_key_ingredients))
          GroupedColumn {
            medication.ingredients.forEachIndexed { i, ing ->
              GroupItem(index = i, count = medication.ingredients.size) {
                ListItem(
                  trailingContent = ing.amount?.takeIf { it.isNotBlank() }?.let { amt ->
                    {
                      Text(
                        amt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  },
                  colors = groupListItemColors(),
                ) { Text(ing.name) }
              }
            }
          }
        }
      }

      item(key = "changes") {
        SectionHeader(stringResource(R.string.med_changes)) {
          TextButton(onClick = onPlanChange) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.med_plan))
          }
        }
        val changes = Adjustments.sorted(stored)
        GroupedColumn {
          if (changes.isEmpty()) {
            GroupItem(index = 0, count = 1) {
              ListItem(
                supportingContent = {
                  Text(stringResource(R.string.med_no_changes_help))
                },
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.med_no_changes)) }
            }
          }
          changes.forEachIndexed { i, change ->
            val date = change.effectiveDateOrNull()!!
            val lines = Adjustments.describe(texts, change) { id ->
              schedules.find { it.id == id }?.let { ScheduleText.describe(texts, it, snapshot.buckets) }
            }
            GroupItem(index = i, count = changes.size, onClick = { onOpenChange(change.id) }) {
              ListItem(
                overlineContent = if (date.isAfter(today)) ({ Text(stringResource(R.string.med_planned)) }) else null,
                supportingContent = {
                  Text((lines + listOfNotNull(change.note)).joinToString("\n"))
                },
                colors = groupListItemColors(),
              ) { Text(UiFormat.date(date)) }
            }
          }
        }
      }

      medication.injectableOrNull?.let { inj ->
        item(key = "injection") {
          SectionHeader(stringResource(R.string.med_injection))
          val pen = medication.form.isPrefilledInjector
          val rows = buildList {
            add(stringResource(R.string.med_route) to inj.route.label())
            // Worked out from the strength; only worth a row when the strength isn't already per 1 mL.
            medication.concentration?.formatted()
              ?.takeUnless { medication.strength.contains(it, ignoreCase = true) }
              ?.let { add(stringResource(R.string.med_per_ml) to it) }
            inj.vialVolumeMl?.let {
              add(stringResource(if (pen) R.string.med_device_volume else R.string.med_vial_size) to stringResource(R.string.med_ml, formatNum(it)))
            }
            // A needle with its standard hub color, which is how it's picked out of a box.
            @Composable
            fun needle(text: String?, gauge: Int?) = text?.let { t ->
              NeedleHub.colorFor(gauge)?.let {
                stringResource(R.string.med_needle_hub, t, stringResource(it.nameRes).lowercase(LocalConfiguration.current.locales[0]))
              } ?: t
            }
            if (pen) {
              needle(inj.formattedInjectNeedle(), inj.injectNeedleGauge)?.let { add(stringResource(R.string.med_pen_needle) to it) }
            } else {
              medication.doseVolumeMl(medication.defaultDoseAmount, medication.defaultDoseUnit)?.let { ml ->
                // Insulin syringes are marked in units; say where to draw to on one.
                val marks = inj.syringeType?.unitsPerMl
                  ?.let { " " + stringResource(R.string.med_syringe_marks, formatNum(ml * it), stringResource(inj.syringeType.labelRes)) }
                add(stringResource(R.string.med_draw_volume) to stringResource(R.string.med_ml, formatNum(ml)) + marks.orEmpty())
              }
              inj.formattedSyringe(LocalResources.current.texts())?.let { add(stringResource(R.string.med_syringe) to it) }
              if (inj.needleAttached) {
                needle(inj.formattedInjectNeedle(), inj.injectNeedleGauge)?.let { add(stringResource(R.string.med_needle_attached) to it) }
              } else {
                needle(inj.formattedDrawNeedle(), inj.drawNeedleGauge)?.let { add(stringResource(R.string.med_draw_needle) to it) }
                needle(inj.formattedInjectNeedle(), inj.injectNeedleGauge)?.let { add(stringResource(R.string.med_injection_needle) to it) }
              }
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
          SectionHeader(stringResource(if (rotates) R.string.med_site_rotation else R.string.med_sites))
          val nextTag = stringResource(R.string.med_site_next)
          val lastTag = stringResource(R.string.med_site_last_used)
          val sites = medication.activeSites
          GroupedColumn {
            sites.forEachIndexed { i, site ->
              // Recording has no "next": the suggestion is the last site, so only that is tagged.
              val tag = when {
                rotates && site.equals(nextSite, ignoreCase = true) -> nextTag
                site.equals(lastSite, ignoreCase = true) -> lastTag
                else -> null
              }
              GroupItem(index = i, count = sites.size) {
                ListItem(
                  leadingContent = if (rotates) {
                    { Text("${i + 1}", style = MaterialTheme.typography.titleMedium) }
                  } else {
                    null
                  },
                  trailingContent = tag?.let { { SiteTag(it, highlighted = it == nextTag) } },
                  colors = groupListItemColors(),
                ) { Text(site) }
              }
            }
          }
        }
      }

      item(key = "limits") {
        SectionHeader(stringResource(R.string.med_pacing_limits))
        val res = LocalResources.current
        val none = stringResource(R.string.value_none)
        val rows = listOf(
          stringResource(R.string.med_min_interval) to
            (medication.minIntervalMinutes?.let { DoseEngine.formatDurationMinutes(it) } ?: none),
          stringResource(R.string.med_daily_dose_limit) to (medication.maxDailyDoses?.let { doseCount(res, it) } ?: none),
          stringResource(R.string.med_daily_amount_limit) to
            (medication.maxDailyAmount?.let { "${formatNum(it)} ${medication.defaultDoseUnit.symbol}" } ?: none),
        )
        GroupedColumn {
          rows.forEachIndexed { i, (label, value) -> GroupItem(index = i, count = rows.size) { InfoRow(label, value) } }
        }
      }

      item(key = "details") {
        SectionHeader(stringResource(R.string.indication_details))
        val indicationLabel = stringResource(R.string.med_indication)
        val rows = buildList<Triple<String, String, (() -> Unit)?>> {
          if (snapshot.tracksIndications) {
            if (indications.isEmpty()) {
              add(Triple(indicationLabel, stringResource(R.string.med_not_linked), null))
            } else {
              indications.forEach { c -> add(Triple(indicationLabel, c.name) { onOpenIndication(c.id) }) }
            }
          }
          if (medication.indicationText.isNotBlank()) add(Triple(stringResource(R.string.med_indication_notes), medication.indicationText, null))
          if (snapshot.tracksProviders && (!medication.isSupplement || prescriber != null)) {
            add(
              Triple(
                stringResource(if (medication.isSupplement) R.string.editor_recommended_by else R.string.med_prescriber),
                prescriber?.let { if (it.isPcp) stringResource(R.string.med_name_pcp, it.name) else it.name } ?: stringResource(R.string.value_not_set),
                prescriber?.let { p -> { onOpenProvider(p.id) } },
              )
            )
          }
          add(Triple(stringResource(R.string.med_source), medication.source.label(), null))
          val dateLabel = if (medication.isSupplement || medication.source == ItemSource.OTC) R.string.med_started else R.string.med_prescribed
          UiFormat.dateIso(medication.startedDateIso)?.let { add(Triple(stringResource(dateLabel), it, null)) }
          medication.ndc11OrNull?.takeIf { it.isNotBlank() }?.let { add(Triple(stringResource(R.string.med_ndc), it, null)) }
          medication.manufacturer?.takeIf { it.isNotBlank() }?.let { add(Triple(stringResource(R.string.med_manufacturer), it, null)) }
          medication.notes?.takeIf { it.isNotBlank() }?.let { add(Triple(stringResource(R.string.field_notes), it, null)) }
        }
        GroupedColumn {
          rows.forEachIndexed { i, (label, value, click) ->
            GroupItem(index = i, count = rows.size, onClick = click) { InfoRow(label, value) }
          }
        }
      }

      item(key = "history") {
        val resources = LocalResources.current
        SectionHeader(stringResource(R.string.med_recent_doses))
        GroupedColumn {
          if (logs.isEmpty()) {
            GroupItem(index = 0, count = 1) {
              ListItem(
                colors = groupListItemColors(),
              ) { Text(stringResource(R.string.med_no_doses)) }
            }
          }
          logs.forEachIndexed { i, log ->
            GroupItem(index = i, count = logs.size, onClick = { onEditLog(log) }) {
              ListItem(
                supportingContent = {
                  Text(
                    listOfNotNull(
                      UiFormat.dateTime(resources, log.actionTimestampMillis),
                      log.siteUsed,
                      log.notes?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                colors = groupListItemColors(),
              ) {
                  Text("${log.status.label()} · ${formatNum(log.amountTaken)} ${log.amountUnit.symbol}")
                }
            }
          }
        }
      }
    }
    }
  }

  if (confirmDelete) {
    ConfirmDialog(
      title = stringResource(R.string.provider_delete_title, medication.primaryName),
      text = stringResource(R.string.med_delete_text),
      confirmLabel = stringResource(R.string.action_delete),
      destructive = true,
      onConfirm = {
        confirmDelete = false
        onDelete()
      },
      onDismiss = { confirmDelete = false },
    )
  }

  if (timeChoiceOpen) {
    val duePart = dueDose.takeIf { !timeChoiceExtra }
    LogTimeDialog(
      title = duePart?.let { stringResource(R.string.med_log_window_dose, it.bucket.name) } ?: stringResource(R.string.today_log_named, medication.primaryName),
      doseLabel = duePart?.doseText() ?: medication.formattedDefaultDose(),
      defaultDateIso = todayIso,
      startWithOtherTime = timeChoiceOther,
      onDismiss = { timeChoiceOpen = false },
      onConfirm = { takenAtMillis, note ->
        val wasExtra = timeChoiceExtra
        timeChoiceOpen = false
        startLogging(wasExtra, takenAtMillis, note)
      },
    )
  }

  if (pacingPrompt) {
    val shownPacing = pacingFor(loggingAt)
    AlertDialog(
      onDismissRequest = { pacingPrompt = false },
      icon = { Icon(Icons.Default.Warning, contentDescription = null) },
      title = { Text(stringResource(R.string.med_log_anyway_title, medication.primaryName)) },
      text = {
        Text(
          listOfNotNull(
            shownPacing.nextAllowed()?.let { stringResource(R.string.pacing_next_dose, it) },
            if (shownPacing.isDailyLimitReached) stringResource(R.string.pacing_daily_limit_reached) else null,
            stringResource(R.string.pacing_from_settings),
          ).joinToString(" ")
        )
      },
      confirmButton = {
        Button(onClick = {
          pacingPrompt = false
          proceedWithoutPacingCheck()
        }) { Text(stringResource(R.string.med_log_anyway)) }
      },
      dismissButton = { TextButton(onClick = { pacingPrompt = false }) { Text(stringResource(R.string.action_cancel)) } },
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
      title = { Text(stringResource(R.string.med_add_schedule_title)) },
      text = { Text(stringResource(R.string.med_add_schedule_text, medication.primaryName)) },
      confirmButton = {
        Button(onClick = {
          showSchedulePrompt = false
          onAddSchedule()
        }) { Text(stringResource(R.string.med_add_schedule)) }
      },
      dismissButton = { TextButton(onClick = { showSchedulePrompt = false }) { Text(stringResource(R.string.providers_as_needed)) } },
    )
  }
}


private fun doseCount(res: Resources, value: Double): String {
  val n = formatNum(value)
  return res.getString(if (value == 1.0) R.string.med_dose_per_day_one else R.string.med_dose_per_day_many, n)
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
  /** The dose today, which a titration step may have changed. */
  dose: DoseAmount,
  /** The next planned dose, e.g. "3 cap from Dec 1". */
  upcoming: String?,
  snapshot: RegimenSnapshot,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  val windows = snapshot.buckets.chronological().filter { it.id in schedule.bucketIds }
  val detail = buildList {
    if (windows.isNotEmpty()) add(windows.map { stringResource(R.string.med_window_time, it.name, it.formattedTime()) }.joinToString(", "))
    val today = LocalDate.now()
    val start = schedule.startDateOrNull()
    val end = schedule.endDateOrNull()
    fun windowName(id: String?) = id?.let { wid -> snapshot.buckets.find { it.id == wid }?.name }
    windowName(schedule.startBucketId)?.takeIf { start != null && !start.isBefore(today) }
      ?.let { add(stringResource(R.string.med_first_dose, it)) }
    windowName(schedule.endBucketId)?.takeIf { end != null && !end.isBefore(today) }
      ?.let { add(stringResource(R.string.med_last_dose, it)) }
    if (schedule.usesDateRange) {
      when {
        start != null && end != null && end.isBefore(today) ->
          add(stringResource(R.string.med_date_range, UiFormat.date(start, withWeekday = false), UiFormat.date(end, withWeekday = false)))
        end != null && end.isBefore(today) -> add(stringResource(R.string.indication_ended_on, UiFormat.date(end, withWeekday = false)))
        start != null && start.isAfter(today) && end != null ->
          add(stringResource(R.string.med_date_range, UiFormat.date(start, withWeekday = false), UiFormat.date(end, withWeekday = false)))
        start != null && start.isAfter(today) -> add(stringResource(R.string.med_starts, UiFormat.date(start, withWeekday = false)))
        end != null -> add(stringResource(R.string.indication_until, UiFormat.date(end, withWeekday = false)))
        start != null &&
          (schedule.frequencyType == ScheduleFrequencyType.EVERY_N_DAYS ||
            schedule.frequencyType == ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE) ->
          add(stringResource(R.string.med_from, UiFormat.date(start, withWeekday = false)))
      }
    }
    if (schedule.frequencyType == ScheduleFrequencyType.SPECIFIC_DATES && schedule.specificDatesIso.isNotEmpty()) {
      val nextUpcoming = schedule.specificDatesIso.sorted().firstOrNull { it >= LocalDate.now().toString() }
      if (nextUpcoming != null) {
        UiFormat.dateIso(nextUpcoming)?.let { add(stringResource(R.string.med_next_date, it)) }
      }
    }
    upcoming?.let { add(stringResource(R.string.med_then, it)) }
    if (!schedule.enabled) add(stringResource(R.string.med_paused))
    if (schedule.hasNoValidWindow(snapshot.buckets)) add(stringResource(R.string.med_no_window))
  }.joinToString(" · ")
  ListItem(
    supportingContent = if (detail.isNotEmpty()) ({ Text(detail) }) else null,
    trailingContent = {
      Box {
        IconButton(onClick = { menuOpen = true }) {
          Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.med_schedule_options))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit)) },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = { menuOpen = false; onEdit() },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = { menuOpen = false; onDelete() },
          )
        }
      }
    },
    colors = groupListItemColors(),
  ) {
      Text("${formatNum(dose.amount)} ${dose.unit.symbol} · ${ScheduleText.frequency(LocalResources.current.texts(), schedule)}")
    }
}
