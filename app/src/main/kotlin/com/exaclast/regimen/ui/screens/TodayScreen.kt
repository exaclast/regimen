@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.exaclast.regimen.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.domain.BucketDoseGroup
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.DoseTimeline
import com.exaclast.regimen.domain.PacingEvaluation
import com.exaclast.regimen.domain.PrnDoseItem
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.domain.WindowPhase
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.DoseStatus
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.notifications.DoseAlarmTiming
import com.exaclast.regimen.ui.RegimenUiState
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.ConfirmDialog
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.LogTimeDialog
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.components.groupListItemColors
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TodayScreen(
  uiState: RegimenUiState,
  onPreviousDay: () -> Unit,
  onNextDay: () -> Unit,
  onGoToToday: () -> Unit,
  onOpenSettings: () -> Unit,
  onTake: (item: ScheduledDoseItem, site: String?, takenAtMillis: Long?) -> Unit,
  onSkip: (ScheduledDoseItem) -> Unit,
  onSnooze: (ScheduledDoseItem) -> Unit,
  onClearLog: (ScheduledDoseItem) -> Unit,
  onTakeAll: (List<ScheduledDoseItem>) -> Unit,
  onLogPrn: (item: PrnDoseItem, site: String?, takenAtMillis: Long?, note: String?) -> Unit,
  onDeleteLog: (DoseLog) -> Unit,
  onEditLog: (DoseLog) -> Unit,
  onOpenMedication: (String) -> Unit,
  onOpenWindow: (String) -> Unit,
  onAddMedication: () -> Unit,
  onReviewYesterday: () -> Unit,
  onLoadDemoData: (() -> Unit)? = null,
  onRestoreBackup: ((Uri) -> Unit)? = null,
) {
  val board = uiState.dailyBoard
  val settings = uiState.snapshot.settings
  val now = uiState.nowMillis
  val selectedDate = runCatching { LocalDate.parse(uiState.selectedDateIso) }.getOrDefault(LocalDate.now())
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  // A list keeps its place by item key, so a notice or card added above the current position would
  // stay out of sight. Go to the top when they change, but not when returning from another screen.
  val listState = rememberLazyListState()
  val topOfList = listOf(
    uiState.selectedDateIso,
    uiState.carriedOver.map { it.bucket.id },
    uiState.yesterdayMissedCount > 0,
    uiState.medicationsWithoutWindow.isNotEmpty(),
    settings.notificationsEnabled,
  ).toString()
  var scrolledForTop by rememberSaveable { mutableStateOf(topOfList) }
  LaunchedEffect(topOfList) {
    if (topOfList != scrolledForTop) {
      scrolledForTop = topOfList
      listState.scrollToItem(0)
    }
  }

  // Take flow: pacing gate → optional confirmation / site choice → log.
  var pacingPrompt by remember { mutableStateOf<TakeRequest?>(null) }
  var confirmPrompt by remember { mutableStateOf<TakeRequest?>(null) }
  var sitePrompt by remember { mutableStateOf<TakeRequest?>(null) }
  var takeAllPrompt by remember { mutableStateOf<List<ScheduledDoseItem>?>(null) }
  var pickingExtraDose by remember { mutableStateOf(false) }
  var timePrompt by remember { mutableStateOf<LogTimeRequest?>(null) }
  val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

  fun commit(request: TakeRequest, site: String?) {
    when (request) {
      is TakeRequest.Scheduled -> onTake(request.item, site, request.takenAtMillis)
      is TakeRequest.Prn -> onLogPrn(request.item, site, request.takenAtMillis, request.note)
    }
  }

  fun afterPacing(request: TakeRequest, alreadyConfirmed: Boolean) {
    val med = request.medication
    val needsSite = med.activeSites.size > 1
    when {
      request is TakeRequest.Scheduled && request.chooseSite -> sitePrompt = request
      request is TakeRequest.Prn && needsSite -> sitePrompt = request
      request is TakeRequest.Scheduled && settings.confirmBeforeTake && !alreadyConfirmed ->
        if (needsSite) sitePrompt = request else confirmPrompt = request
      else -> commit(request, request.suggestedSite)
    }
  }

  fun requestTake(request: TakeRequest) {
    if (!request.pacing.canTakeNow) pacingPrompt = request else afterPacing(request, alreadyConfirmed = false)
  }

  // An extra dose of any medication, scheduled or not, takes the same pacing, site and time flow as an
  // as-needed one, and is logged on the day being viewed.
  fun extraDoseItem(medication: Medication): PrnDoseItem {
    val snapshot = uiState.snapshot
    return PrnDoseItem(
      medication = medication,
      schedule = null,
      suggestedSite = DoseEngine.suggestSite(medication, snapshot.doseLogs),
      pacing = DoseEngine.evaluatePacing(medication, snapshot.doseLogs, now, uiState.selectedDateIso, snapshot.buckets),
      indications = emptyList(),
    )
  }

  // Pacing for a dose logged at another time is judged at that time, not now.
  fun requestPrnDose(item: PrnDoseItem, takenAtMillis: Long?, note: String?) {
    if (takenAtMillis == null) {
      requestTake(TakeRequest.Prn(item, note = note))
      return
    }
    val snapshot = uiState.snapshot
    val day = java.time.Instant.ofEpochMilli(takenAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    val pacing = DoseEngine.evaluatePacing(
      item.medication, snapshot.doseLogs, takenAtMillis, day, snapshot.buckets, backdated = true,
    )
    requestTake(TakeRequest.Prn(item.copy(pacing = pacing), takenAtMillis, note))
  }

  val title = if (uiState.isToday) "Today" else UiFormat.date(selectedDate)
  val progress = when {
    board.totalScheduledCount == 0 -> "Nothing scheduled"
    else -> "${board.totalTakenCount} of ${board.totalScheduledCount} taken"
  }
  val subtitle = if (uiState.isToday) "${UiFormat.date(selectedDate)} · $progress" else progress

  @Composable
  fun DoseWindow(group: BucketDoseGroup, dateIso: String, occurrenceLabel: String? = null) {
    WindowSection(
      group = group,
      phase = DoseTimeline.windowPhase(group, dateIso, now),
      isFutureDate = uiState.isFuture,
      nowMillis = now,
      settings = settings,
      occurrenceLabel = occurrenceLabel,
      onTake = { requestTake(TakeRequest.Scheduled(it)) },
      // Recording a missed dose that was really taken in its window: no "too soon" pacing
      // gate (that compares against now), but the site / confirmation prompts still apply.
      onTakenOnTime = { item ->
        val windowEnd = DoseAlarmTiming.windowEndMillis(item.bucket, item.dateIso)
        afterPacing(TakeRequest.Scheduled(item, takenAtMillis = windowEnd), alreadyConfirmed = false)
      },
      onSnooze = onSnooze,
      onSkip = onSkip,
      onClearLog = onClearLog,
      onChangeSite = { requestTake(TakeRequest.Scheduled(it, chooseSite = true)) },
      onTakeAll = { pending ->
        if (pending.any { !it.pacing.canTakeNow }) takeAllPrompt = pending else onTakeAll(pending)
      },
      onOpenMedication = onOpenMedication,
      onOpenWindow = { onOpenWindow(group.bucket.id) },
    )
  }

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      LargeFlexibleTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        subtitle = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
          IconButton(onClick = onPreviousDay) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
          }
          IconButton(onClick = onNextDay) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
          }
          // "Go to today" keeps its slot even when hidden, so showing it never shifts the day
          // arrows under the user's finger. Hidden = transparent, disabled, and out of a11y.
          val showGoToToday = !uiState.isToday
          val goToTodayAlpha by animateFloatAsState(if (showGoToToday) 1f else 0f, label = "goToTodayAlpha")
          IconButton(
            onClick = onGoToToday,
            enabled = showGoToToday,
            modifier = Modifier
              .graphicsLayer { alpha = goToTodayAlpha }
              .then(if (showGoToToday) Modifier else Modifier.clearAndSetSemantics {}),
          ) {
            Icon(Icons.Default.Today, contentDescription = "Go to today")
          }
          IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        },
        scrollBehavior = scrollBehavior,
      )
    },
    floatingActionButton = {
      if (!uiState.isFuture && uiState.snapshot.medications.isNotEmpty()) {
        ExtendedFloatingActionButton(
          onClick = { pickingExtraDose = true },
          expanded = fabExpanded,
          icon = { Icon(Icons.Default.Add, contentDescription = null) },
          text = { Text("Log extra dose") },
        )
      }
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
      // Bottom room so the last card can scroll clear of the button.
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!settings.notificationsEnabled) {
        item(key = "reminders-off") {
          NoticeCard(
            text = "Reminders are turned off. Doses won't notify you.",
            actionLabel = "Settings",
            onAction = onOpenSettings,
          )
        }
      }

      uiState.medicationsWithoutWindow.firstOrNull()?.let { first ->
        item(key = "no-window") {
          val count = uiState.medicationsWithoutWindow.size
          NoticeCard(
            icon = Icons.Default.Warning,
            text = if (count == 1) {
              "${first.primaryName} has no dose window, so it won't appear here or remind you."
            } else {
              "$count medications have no dose window, so they won't appear here or remind you."
            },
            actionLabel = "Fix",
            onAction = { onOpenMedication(first.id) },
          )
        }
      }

      if (uiState.isToday && uiState.yesterdayMissedCount > 0) {
        item(key = "yesterday-missed") {
          val count = uiState.yesterdayMissedCount
          NoticeCard(
            icon = Icons.Default.Warning,
            text = if (count == 1) "1 dose from yesterday wasn't logged." else "$count doses from yesterday weren't logged.",
            actionLabel = "Review",
            onAction = onReviewYesterday,
          )
        }
      }

      if (uiState.isToday) {
        items(uiState.carriedOver, key = { "carried-${it.bucket.id}" }) { group ->
          DoseWindow(group, uiState.yesterdayIso, occurrenceLabel = "From last night")
        }
      }

      if (board.bucketGroups.isEmpty() && board.prnItems.isEmpty() && (!uiState.isToday || uiState.carriedOver.isEmpty())) {
        item(key = "empty") {
          EmptyDayCard(
            hasAnyMedications = uiState.snapshot.medications.isNotEmpty(),
            onAddMedication = onAddMedication,
            onLoadDemoData = onLoadDemoData,
            onRestoreBackup = onRestoreBackup,
          )
        }
      }

      items(board.bucketGroups, key = { "window-${it.bucket.id}" }) { group ->
        DoseWindow(group, uiState.selectedDateIso)
      }

      if (board.prnItems.isNotEmpty()) {
        item(key = "prn-header") { SectionHeader("As needed") }
        items(board.prnItems, key = { "prn-${it.medication.id}" }) { prn ->
          PrnCard(
            item = prn,
            isToday = uiState.isToday,
            enabled = !uiState.isFuture,
            onLog = { requestTake(TakeRequest.Prn(prn)) },
            onLogAtOtherTime = { timePrompt = LogTimeRequest(prn, startWithOtherTime = true) },
            onOpenMedication = { onOpenMedication(prn.medication.id) },
          )
        }
      }

      val dayLogs = uiState.snapshot.doseLogs
        .filter { it.scheduledDateIso == uiState.selectedDateIso && it.status != DoseStatus.SNOOZED }
        .sortedByDescending { it.actionTimestampMillis }
      if (dayLogs.isNotEmpty()) {
        item(key = "log-header") { SectionHeader(if (uiState.isToday) "Logged today" else "Logged this day") }
        item(key = "log-list") {
          DayLogList(
            logs = dayLogs,
            medications = uiState.snapshot.medications,
            onDelete = onDeleteLog,
            onEdit = onEditLog,
          )
        }
      }
    }
  }

  if (pickingExtraDose) {
    ExtraDosePickerDialog(
      medications = uiState.snapshot.medications,
      onDismiss = { pickingExtraDose = false },
      onPick = {
        pickingExtraDose = false
        timePrompt = LogTimeRequest(extraDoseItem(it), startWithOtherTime = false)
      },
    )
  }

  timePrompt?.let { request ->
    LogTimeDialog(
      title = "Log ${request.item.medication.primaryName}",
      doseLabel = "${formatNum(request.item.doseAmount)} ${request.item.doseUnit.symbol}",
      defaultDateIso = uiState.selectedDateIso,
      startWithOtherTime = request.startWithOtherTime,
      onDismiss = { timePrompt = null },
      onConfirm = { takenAtMillis, note ->
        timePrompt = null
        requestPrnDose(request.item, takenAtMillis, note)
      },
    )
  }

  pacingPrompt?.let { request ->
    AlertDialog(
      onDismissRequest = { pacingPrompt = null },
      icon = { Icon(Icons.Default.Warning, contentDescription = null) },
      title = { Text("Take ${request.medication.primaryName} anyway?") },
      text = { Text(pacingExplanation(request.pacing, request.medication)) },
      confirmButton = {
        Button(onClick = {
          pacingPrompt = null
          afterPacing(request, alreadyConfirmed = true)
        }) { Text("Take anyway") }
      },
      dismissButton = { TextButton(onClick = { pacingPrompt = null }) { Text("Cancel") } },
    )
  }

  confirmPrompt?.let { request ->
    val item = (request as TakeRequest.Scheduled).item
    AlertDialog(
      onDismissRequest = { confirmPrompt = null },
      title = { Text("Log ${item.medication.primaryName}?") },
      text = { Text("${item.formattedDoseInstruction()} · ${item.bucket.name}") },
      confirmButton = {
        Button(onClick = {
          confirmPrompt = null
          commit(request, item.suggestedSite)
        }) { Text("Log dose") }
      },
      dismissButton = { TextButton(onClick = { confirmPrompt = null }) { Text("Cancel") } },
    )
  }

  sitePrompt?.let { request ->
    SiteSelectionDialog(
      medication = request.medication,
      suggestedSite = request.suggestedSite,
      lastSiteUsed = request.pacing.lastSiteUsed,
      onDismiss = { sitePrompt = null },
      onConfirm = { site ->
        sitePrompt = null
        commit(request, site)
      },
    )
  }

  takeAllPrompt?.let { pending ->
    val limited = pending.filter { !it.pacing.canTakeNow }.joinToString { it.medication.primaryName }
    AlertDialog(
      onDismissRequest = { takeAllPrompt = null },
      icon = { Icon(Icons.Default.Warning, contentDescription = null) },
      title = { Text("Take all ${pending.size} anyway?") },
      text = { Text("Pacing limits apply to $limited.") },
      confirmButton = {
        Button(onClick = {
          takeAllPrompt = null
          onTakeAll(pending)
        }) { Text("Take all") }
      },
      dismissButton = { TextButton(onClick = { takeAllPrompt = null }) { Text("Cancel") } },
    )
  }
}

/** Pick any medication to log an extra dose of; searchable, since the list can be long. */
@Composable
private fun ExtraDosePickerDialog(
  medications: List<Medication>,
  onDismiss: () -> Unit,
  onPick: (Medication) -> Unit,
) {
  var query by rememberSaveable { mutableStateOf("") }
  val matches = remember(medications, query) {
    val q = query.trim().lowercase()
    medications
      .filter {
        q.isEmpty() || it.formulaName.lowercase().contains(q) || it.brandName?.lowercase()?.contains(q) == true
      }
      .sortedBy { it.primaryName.lowercase() }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Log extra dose") },
    text = {
      Column {
        if (medications.size > 6) {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search medications") },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 8.dp),
          )
        }
        if (matches.isEmpty()) {
          Text("No matches for \"$query\"", modifier = Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
          items(matches, key = { it.id }) { med ->
            ListItem(
              leadingContent = { FormIcon(med.form) },
              supportingContent = {
                Text(
                  listOfNotNull(med.secondaryName, med.strength).joinToString(" · "),
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              },
              colors = ListItemDefaults.colors(containerColor = Color.Transparent),
              modifier = Modifier.clickable { onPick(med) },
            ) { Text(med.primaryName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** An as-needed or extra dose waiting for the user to say when it was taken. */
private data class LogTimeRequest(val item: PrnDoseItem, val startWithOtherTime: Boolean)

/** A pending "take" through the pacing/confirmation flow. */
private sealed interface TakeRequest {
  val medication: Medication
  val pacing: PacingEvaluation
  val suggestedSite: String?

  /** [takenAtMillis] back-dates the log (null = now). [chooseSite] asks for the site before logging. */
  data class Scheduled(
    val item: ScheduledDoseItem,
    val takenAtMillis: Long? = null,
    val chooseSite: Boolean = false,
  ) : TakeRequest {
    override val medication get() = item.medication
    override val pacing get() = item.pacing
    override val suggestedSite get() = item.suggestedSite
  }

  /** [takenAtMillis] logs the dose at that time instead of now. [note] is saved with the log. */
  data class Prn(val item: PrnDoseItem, val takenAtMillis: Long? = null, val note: String? = null) : TakeRequest {
    override val medication get() = item.medication
    override val pacing get() = item.pacing
    override val suggestedSite get() = item.suggestedSite
  }
}

private fun pacingExplanation(pacing: PacingEvaluation, medication: Medication): String {
  val parts = mutableListOf<String>()
  pacing.nextAllowedText()?.let { parts.add("The next dose isn't due until $it.") }
  if (pacing.isDailyLimitReached) {
    val limit = pacing.maxDailyDoses?.let { formatNum(it) }
    parts.add(
      if (limit != null) {
        "You've reached the daily limit (${pacing.dosesTakenToday} of $limit)."
      } else {
        "You've reached the daily amount limit for ${medication.primaryName}."
      }
    )
  }
  parts.add("These limits come from this medication's settings.")
  return parts.joinToString(" ")
}

@Composable
private fun WindowSection(
  group: BucketDoseGroup,
  phase: WindowPhase,
  isFutureDate: Boolean,
  nowMillis: Long,
  settings: AppSettings,
  occurrenceLabel: String? = null,
  onTake: (ScheduledDoseItem) -> Unit,
  onTakenOnTime: (ScheduledDoseItem) -> Unit,
  onSnooze: (ScheduledDoseItem) -> Unit,
  onSkip: (ScheduledDoseItem) -> Unit,
  onClearLog: (ScheduledDoseItem) -> Unit,
  onChangeSite: (ScheduledDoseItem) -> Unit,
  onTakeAll: (List<ScheduledDoseItem>) -> Unit,
  onOpenMedication: (String) -> Unit,
  onOpenWindow: () -> Unit,
) {
  val bucket = group.bucket
  var expanded by remember(bucket.id) { mutableStateOf(false) }
  val colors = MaterialTheme.colorScheme
  val (container, onContainer) = when {
    isFutureDate -> colors.surfaceContainerLow to colors.onSurface
    phase == WindowPhase.NOW -> colors.primaryContainer to colors.onPrimaryContainer
    phase == WindowPhase.MISSED -> colors.errorContainer to colors.onErrorContainer
    else -> colors.surfaceContainerLow to colors.onSurface
  }
  val pending = group.items.filter { !it.isCompleted }
  val showItems = phase != WindowPhase.DONE || expanded

  Surface(
    color = container,
    contentColor = onContainer,
    shape = MaterialTheme.shapes.extraLarge,
    modifier = Modifier
      .fillMaxWidth()
      .animateContentSize(),
  ) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = bucket.name,
              style = if (phase == WindowPhase.NOW && !isFutureDate) {
                MaterialTheme.typography.titleLarge
              } else {
                MaterialTheme.typography.titleMedium
              },
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            PhaseLabel(phase = phase, isFutureDate = isFutureDate)
          }
          Text(
            text = listOfNotNull(occurrenceLabel, windowTimeLabel(bucket)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer.copy(alpha = 0.8f),
          )
        }
        ReminderStatusButton(bucket = bucket, settings = settings, onClick = onOpenWindow)
        if (phase == WindowPhase.DONE) {
          IconButton(onClick = { expanded = !expanded }) {
            Icon(
              if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
              contentDescription = if (expanded) "Hide doses" else "Show doses",
            )
          }
        }
      }

      if (phase == WindowPhase.DONE && !expanded) {
        Text(
          text = doneSummary(group),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 4.dp, end = 12.dp),
        )
      }

      if (showItems) {
        Spacer(Modifier.height(8.dp))
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.padding(end = 12.dp),
        ) {
          group.items.forEach { item ->
            DoseCard(
              item = item,
              phase = phase,
              isFutureDate = isFutureDate,
              nowMillis = nowMillis,
              snoozeMinutes = settings.snoozeMinutes,
              onTake = { onTake(item) },
              onTakenOnTime = { onTakenOnTime(item) },
              onSnooze = { onSnooze(item) },
              onSkip = { onSkip(item) },
              onClearLog = { onClearLog(item) },
              onChangeSite = { onChangeSite(item) },
              onOpenMedication = { onOpenMedication(item.medication.id) },
            )
          }
        }
        if (pending.size > 1 && !isFutureDate && (phase == WindowPhase.NOW || phase == WindowPhase.MISSED)) {
          TextButton(onClick = { onTakeAll(pending) }, modifier = Modifier.align(Alignment.End)) {
            Text("Take all ${pending.size}")
          }
        }
      }
    }
  }
}

private fun windowTimeLabel(bucket: TimeOfDayBucket): String =
  if (bucket.crossesMidnight) "${bucket.formattedTime()} (overnight)" else bucket.formattedTime()

private fun doneSummary(group: BucketDoseGroup): String {
  val taken = group.takenCount
  val skipped = group.items.count { it.isSkipped }
  val last = group.items.mapNotNull { it.existingLog?.actionTimestampMillis }.maxOrNull()
  val parts = buildList {
    if (taken > 0) add("$taken taken")
    if (skipped > 0) add("$skipped skipped")
    last?.let { add("last at ${UiFormat.time(it)}") }
  }
  return parts.joinToString(" · ")
}

@Composable
private fun PhaseLabel(phase: WindowPhase, isFutureDate: Boolean) {
  val text = when {
    isFutureDate -> "Scheduled"
    phase == WindowPhase.NOW -> "Now"
    phase == WindowPhase.UPCOMING -> "Upcoming"
    phase == WindowPhase.MISSED -> "Missed"
    else -> "Done"
  }
  val colors = MaterialTheme.colorScheme
  val (bg, fg) = when {
    isFutureDate -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    phase == WindowPhase.NOW -> colors.primary to colors.onPrimary
    phase == WindowPhase.MISSED -> colors.error to colors.onError
    phase == WindowPhase.DONE -> colors.secondaryContainer to colors.onSecondaryContainer
    else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
  }
  Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun ReminderStatusButton(bucket: TimeOfDayBucket, settings: AppSettings, onClick: () -> Unit) {
  val remindersOn = settings.notificationsEnabled && bucket.notificationsEnabled
  val alarmOn = remindersOn && settings.alarmsEnabled && bucket.alarmAtWindowEnd
  val (icon, description) = when {
    !remindersOn -> Icons.Default.NotificationsOff to "${bucket.name} reminders off"
    alarmOn -> Icons.Default.Alarm to "${bucket.name}: reminder at ${bucket.formattedStartTime()}, alarm at ${bucket.formattedEndTime()}"
    else -> Icons.Default.Notifications to "${bucket.name}: reminder at ${bucket.formattedStartTime()}"
  }
  IconButton(onClick = onClick) { Icon(icon, contentDescription = description) }
}

@Composable
private fun DoseCard(
  item: ScheduledDoseItem,
  phase: WindowPhase,
  isFutureDate: Boolean,
  nowMillis: Long,
  snoozeMinutes: Int,
  onTake: () -> Unit,
  onTakenOnTime: () -> Unit,
  onSnooze: () -> Unit,
  onSkip: () -> Unit,
  onClearLog: () -> Unit,
  onChangeSite: () -> Unit,
  onOpenMedication: () -> Unit,
) {
  val med = item.medication
  val missed = DoseTimeline.isMissed(item, nowMillis)
  val snoozedUntil = DoseTimeline.activeSnoozeUntil(item, nowMillis)
  val warning = if (!item.isCompleted) item.pacing.warningText() else null
  val canAct = !item.isCompleted && !isFutureDate
  val canSnooze = canAct && !missed && phase == WindowPhase.NOW
  val tracksSites = med.tracksSites
  var menuOpen by remember { mutableStateOf(false) }

  val statusText = when {
    item.isTaken -> "Taken at ${UiFormat.time(item.existingLog!!.actionTimestampMillis)}" +
      (item.existingLog.siteUsed?.let { " · $it" } ?: "")
    item.isSkipped -> "Skipped"
    snoozedUntil != null -> "Snoozed until ${UiFormat.time(snoozedUntil)}"
    missed -> "Missed"
    else -> null
  }
  val takeLabel = when {
    !item.pacing.canTakeNow -> "Take anyway…"
    missed -> "Take now"
    phase == WindowPhase.UPCOMING -> "Take early"
    else -> "Take"
  }
  // Overdue: either taking it late (now) or it was taken on time and just not logged, which is
  // recorded at the window's end.
  val windowEnd = if (missed) DoseAlarmTiming.windowEndMillis(item.bucket, item.dateIso) else null
  val canLogOnTime = canAct && windowEnd != null
  // Flipping a logged dose between taken and skipped directly; take and skip overwrite the
  // existing log (with Undo), so there's no need to clear it first.
  val canMarkTaken = item.isSkipped && !isFutureDate
  val canMarkSkipped = item.isTaken && !isFutureDate
  val markTakenOnTimeAt = if (canMarkTaken) {
    DoseAlarmTiming.windowEndMillis(item.bucket, item.dateIso)?.takeIf { it <= nowMillis }
  } else null

  val a11yActions = buildList {
    if (canAct) add(CustomAccessibilityAction(takeLabel.removeSuffix("…")) { onTake(); true })
    if (canLogOnTime) {
      add(CustomAccessibilityAction("Taken on time, at ${UiFormat.time(windowEnd!!)}") { onTakenOnTime(); true })
    }
    if (canSnooze) add(CustomAccessibilityAction("Snooze $snoozeMinutes minutes") { onSnooze(); true })
    if (canAct) add(CustomAccessibilityAction("Skip") { onSkip(); true })
    if (canMarkTaken) {
      if (markTakenOnTimeAt != null) {
        add(CustomAccessibilityAction("Mark as taken on time, at ${UiFormat.time(markTakenOnTimeAt)}") { onTakenOnTime(); true })
        add(CustomAccessibilityAction("Mark as taken now") { onTake(); true })
      } else {
        add(CustomAccessibilityAction("Mark as taken") { onTake(); true })
      }
    }
    if (canMarkSkipped) add(CustomAccessibilityAction("Mark as skipped") { onSkip(); true })
    if (item.existingLog != null) add(CustomAccessibilityAction("Mark as not taken") { onClearLog(); true })
  }
  val a11yDescription = listOfNotNull(
    med.primaryName,
    med.secondaryName,
    item.formattedDoseInstruction(),
    statusText,
    warning,
    if (tracksSites && !item.isCompleted) item.suggestedSite?.let { "Site $it" } else null,
  ).joinToString(", ")

  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier
      .fillMaxWidth()
      .clearAndSetSemantics {
        contentDescription = a11yDescription
        customActions = a11yActions
        onClick(label = "Open medication") { onOpenMedication(); true }
      },
    onClick = onOpenMedication,
  ) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
          Text(
            text = med.primaryName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = listOfNotNull(med.secondaryName, med.strength).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = item.formattedDoseInstruction(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
          )
        }
        Box {
          IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (canAct) {
              DropdownMenuItem(
                text = { Text("Skip") },
                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                onClick = { menuOpen = false; onSkip() },
              )
            }
            if (canAct && med.activeSites.size > 1) {
              DropdownMenuItem(
                text = { Text("Take at a different site…") },
                leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null) },
                onClick = { menuOpen = false; onChangeSite() },
              )
            }
            if (canMarkTaken) {
              if (markTakenOnTimeAt != null) {
                DropdownMenuItem(
                  text = { Text("Mark as taken on time (${UiFormat.time(markTakenOnTimeAt)})") },
                  leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                  onClick = { menuOpen = false; onTakenOnTime() },
                )
                DropdownMenuItem(
                  text = { Text("Mark as taken now") },
                  leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                  onClick = { menuOpen = false; onTake() },
                )
              } else {
                DropdownMenuItem(
                  text = { Text("Mark as taken") },
                  leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                  onClick = { menuOpen = false; onTake() },
                )
              }
            }
            if (canMarkSkipped) {
              DropdownMenuItem(
                text = { Text("Mark as skipped") },
                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                onClick = { menuOpen = false; onSkip() },
              )
            }
            if (item.existingLog != null) {
              DropdownMenuItem(
                text = { Text("Mark as not taken") },
                onClick = { menuOpen = false; onClearLog() },
              )
            }
            DropdownMenuItem(text = { Text("View medication") }, onClick = { menuOpen = false; onOpenMedication() })
          }
        }
      }

      Column(modifier = Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (tracksSites && !item.isCompleted && item.suggestedSite != null) {
          StatusLine(
            icon = if (med.rotatesSites) Icons.Default.Autorenew else Icons.Default.Place,
            text = "Site: ${item.suggestedSite}",
          )
        }
        statusText?.let { text ->
          val icon = when {
            item.isTaken -> Icons.Default.Check
            item.isSkipped -> Icons.Default.Close
            snoozedUntil != null -> Icons.Default.Snooze
            else -> Icons.Default.Warning
          }
          StatusLine(
            icon = icon,
            text = text,
            color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        warning?.let { StatusLine(icon = Icons.Default.Warning, text = it, color = MaterialTheme.colorScheme.error) }

        if (canAct) {
          Spacer(Modifier.height(4.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
              canLogOnTime -> {
                Button(onClick = onTake, modifier = Modifier.weight(1f)) {
                  Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(Modifier.width(8.dp))
                  Text(takeLabel, maxLines = 1)
                }
                OutlinedButton(onClick = onTakenOnTime, modifier = Modifier.weight(1f)) {
                  Text("Taken on time", maxLines = 1)
                }
              }
              !item.pacing.canTakeNow || phase == WindowPhase.UPCOMING ->
                OutlinedButton(onClick = onTake, modifier = Modifier.weight(1f)) { Text(takeLabel) }
              else -> Button(onClick = onTake, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(takeLabel)
              }
            }
            if (canSnooze) {
              FilledTonalButton(onClick = onSnooze) {
                Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Snooze")
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StatusLine(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  text: String,
  color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
  }
}

@Composable
private fun PrnCard(
  item: PrnDoseItem,
  isToday: Boolean,
  enabled: Boolean,
  onLog: () -> Unit,
  onLogAtOtherTime: () -> Unit,
  onOpenMedication: () -> Unit,
) {
  val med = item.medication
  val warning = item.pacing.warningText()
  val dayWord = if (isToday) "today" else "that day"
  val dailyNote = item.pacing.maxDailyDoses?.let { "${item.pacing.dosesTakenToday} of ${formatNum(it)} $dayWord" }
  val logLabel = "Log ${formatNum(item.doseAmount)} ${item.doseUnit.symbol}"
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = MaterialTheme.shapes.extraLarge,
    onClick = onOpenMedication,
    modifier = Modifier
      .fillMaxWidth()
      .clearAndSetSemantics {
        contentDescription = listOfNotNull(med.primaryName, med.secondaryName, med.strength, warning ?: "Ready", dailyNote)
          .joinToString(", ")
        onClick(label = "Open medication") { onOpenMedication(); true }
        if (enabled) {
          customActions = listOf(
            CustomAccessibilityAction(logLabel) { onLog(); true },
            CustomAccessibilityAction("$logLabel at another time") { onLogAtOtherTime(); true },
          )
        }
      },
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = med.primaryName,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = listOfNotNull(med.secondaryName, med.strength).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (warning != null) {
        StatusLine(icon = Icons.Default.Warning, text = warning, color = MaterialTheme.colorScheme.error)
      } else {
        StatusLine(icon = Icons.Default.Check, text = listOfNotNull("Ready", dailyNote).joinToString(" · "))
      }
      if (enabled) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = onLogAtOtherTime) { Text("Other time") }
          Spacer(Modifier.width(4.dp))
          if (warning != null) {
            OutlinedButton(onClick = onLog) { Text("$logLabel anyway…") }
          } else {
            FilledTonalButton(onClick = onLog) { Text(logLabel) }
          }
        }
      }
    }
  }
}

@Composable
private fun DayLogList(
  logs: List<DoseLog>,
  medications: List<Medication>,
  onDelete: (DoseLog) -> Unit,
  onEdit: (DoseLog) -> Unit,
) {
  val medMap = remember(medications) { medications.associateBy { it.id } }
  GroupedColumn {
    logs.forEachIndexed { index, log ->
      val med = medMap[log.medicationId]
      val name = med?.primaryName ?: "Medication"
      val verb = if (log.status == DoseStatus.SKIPPED) "Skipped" else "Taken"
      // The editor needs the medication, so a log whose medication was deleted stays read-only.
      GroupItem(index = index, count = logs.size, onClick = if (med != null) ({ onEdit(log) }) else null) {
        ListItem(
          supportingContent = {
            Text(
              listOfNotNull(
                "$verb ${UiFormat.time(log.actionTimestampMillis)}",
                log.siteUsed,
                log.notes?.takeIf { it.isNotBlank() },
              ).joinToString(" · "),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          },
          trailingContent = {
            IconButton(onClick = { onDelete(log) }) {
              Icon(Icons.Default.Delete, contentDescription = "Delete $name log entry")
            }
          },
          colors = groupListItemColors(),
        ) { Text("$name · ${formatNum(log.amountTaken)} ${log.amountUnit.symbol}") }
      }
    }
  }
}

@Composable
private fun NoticeCard(
  text: String,
  actionLabel: String,
  onAction: () -> Unit,
  icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.NotificationsOff,
) {
  Surface(
    color = MaterialTheme.colorScheme.tertiaryContainer,
    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null)
      Spacer(Modifier.width(12.dp))
      Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      TextButton(onClick = onAction) { Text(actionLabel) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyDayCard(
  hasAnyMedications: Boolean,
  onAddMedication: () -> Unit,
  onLoadDemoData: (() -> Unit)?,
  onRestoreBackup: ((Uri) -> Unit)?,
) {
  var confirmDemo by remember { mutableStateOf(false) }
  val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) onRestoreBackup?.invoke(uri)
  }
  val isFirstRun = !hasAnyMedications

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape = MaterialTheme.shapes.extraLarge,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = if (hasAnyMedications) "Nothing scheduled" else "No medications yet",
        style = MaterialTheme.typography.titleMedium,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = if (hasAnyMedications) {
          "Add a medication and give it a schedule to see doses here."
        } else {
          "Add your medications and schedules to start tracking doses, restore a backup, or load demo data to explore."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(16.dp))
      // Wraps onto a second line on narrow screens instead of squeezing the buttons.
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(onClick = onAddMedication) { Text("Add medication") }
        if (isFirstRun && onRestoreBackup != null) {
          OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) { Text("Restore backup") }
        }
        if (isFirstRun && onLoadDemoData != null) {
          OutlinedButton(onClick = { confirmDemo = true }) { Text("Load demo data") }
        }
      }
    }
  }

  if (confirmDemo && onLoadDemoData != null) {
    ConfirmDialog(
      title = "Load demo data?",
      text = "This populates sample medications, indications, providers, and schedules.",
      confirmLabel = "Load demo data",
      onConfirm = {
        confirmDemo = false
        onLoadDemoData()
      },
      onDismiss = { confirmDemo = false },
    )
  }
}

@Composable
fun SiteSelectionDialog(
  medication: Medication,
  suggestedSite: String?,
  lastSiteUsed: String?,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var selected by remember(suggestedSite, medication.id) {
    mutableStateOf(suggestedSite ?: medication.activeSites.firstOrNull().orEmpty())
  }
  val suggestionLabel = if (medication.rotatesSites) "Next in rotation" else "Same as last time"
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Choose a site") },
    text = {
      // Scrolls: a long site list would otherwise push the buttons off a short window.
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        lastSiteUsed?.takeIf { it.isNotBlank() }?.let {
          Text(
            "Last used: $it",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        medication.activeSites.forEach { site ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .selectable(selected = selected == site, role = Role.RadioButton, onClick = { selected = site })
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = selected == site, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
              Text(site, style = MaterialTheme.typography.bodyLarge)
              if (site == suggestedSite) {
                Text(
                  suggestionLabel,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = { onConfirm(selected) }, enabled = selected.isNotBlank()) { Text("Log dose") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
