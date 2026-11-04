package com.exaclast.regimen.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.domain.BucketDoseGroup
import com.exaclast.regimen.domain.PrnDoseItem
import com.exaclast.regimen.domain.ScheduledDoseItem
import com.exaclast.regimen.model.DoseLog
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.notifications.DoseNotificationStage
import com.exaclast.regimen.ui.RegimenUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(
  uiState: RegimenUiState,
  onShiftDate: (Long) -> Unit,
  onResetToToday: () -> Unit,
  onTakeScheduledDose: (ScheduledDoseItem, String?) -> Unit,
  onSkipScheduledDose: (ScheduledDoseItem) -> Unit,
  onSnoozeScheduledDose: (ScheduledDoseItem) -> Unit,
  onUndoScheduledDose: (ScheduledDoseItem) -> Unit,
  onTakeAllInBucket: (String) -> Unit,
  onLogPrnDose: (Medication, Double, String?) -> Unit,
  onDeleteLog: (String) -> Unit,
  onSendTestNotification: (ScheduledDoseItem, DoseNotificationStage) -> Unit,
  onOpenMedicationDetail: (String) -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var pendingNotificationItem by remember { mutableStateOf<Pair<ScheduledDoseItem, DoseNotificationStage>?>(null) }
  var notificationPickerItem by remember { mutableStateOf<ScheduledDoseItem?>(null) }
  var siteOverrideDialogItem by remember { mutableStateOf<ScheduledDoseItem?>(null) }
  var confirmTakeDialogItem by remember { mutableStateOf<ScheduledDoseItem?>(null) }
  var prnSiteOverrideItem by remember { mutableStateOf<PrnDoseItem?>(null) }
  val settings = uiState.snapshot.settings

  val handlePrimaryTakeClick: (ScheduledDoseItem) -> Unit = { item ->
    if (settings.confirmBeforeTake) {
      if (item.medication.siteRotationEnabled && item.medication.rotationSites.size > 1) {
        siteOverrideDialogItem = item
      } else {
        confirmTakeDialogItem = item
      }
    } else {
      onTakeScheduledDose(item, item.suggestedSite)
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      pendingNotificationItem?.let { (item, stage) -> onSendTestNotification(item, stage) }
    }
    pendingNotificationItem = null
  }

  val triggerNotification: (ScheduledDoseItem) -> Unit = { item ->
    notificationPickerItem = item
  }

  val dispatchNotificationStage: (ScheduledDoseItem, DoseNotificationStage) -> Unit = { item, stage ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      !DoseNotificationManager.hasNotificationPermission(context)
    ) {
      pendingNotificationItem = item to stage
      permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      onSendTestNotification(item, stage)
    }
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isTwoPane = maxWidth >= 700.dp

    if (isTwoPane) {
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Left Pane: Scheduled Doses by Time-of-Day Bucket
        LazyColumn(
          modifier = Modifier
            .weight(1.15f)
            .fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          contentPadding = PaddingValues(bottom = 24.dp),
        ) {
          item {
            DateAndProgressHeader(
              uiState = uiState,
              onShiftDate = onShiftDate,
              onResetToToday = onResetToToday,
              onOpenSettings = onOpenSettings,
            )
          }

          if (uiState.dailyBoard.bucketGroups.isEmpty()) {
            item {
              EmptyScheduleCard()
            }
          } else {
            items(uiState.dailyBoard.bucketGroups, key = { it.bucket.id }) { group ->
              BucketSectionCard(
                group = group,
                snoozeMinutes = settings.snoozeMinutes,
                globalNotificationsEnabled = settings.notificationsEnabled,
                globalAlarmsEnabled = settings.alarmsEnabled,
                onTakeAll = { onTakeAllInBucket(group.bucket.id) },
                onTakeItem = { item -> handlePrimaryTakeClick(item) },
                onSkipItem = onSkipScheduledDose,
                onSnoozeItem = onSnoozeScheduledDose,
                onUndoItem = onUndoScheduledDose,
                onOverrideSiteClick = { siteOverrideDialogItem = it },
                onSendReminderClick = triggerNotification,
                onMedicationClick = onOpenMedicationDetail,
              )
            }
          }
        }

        // Right Pane: PRN / As-Needed + Recent Dose History & Site Rotation Log
        LazyColumn(
          modifier = Modifier
            .weight(0.85f)
            .fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          contentPadding = PaddingValues(bottom = 24.dp),
        ) {
          item {
            PrnSectionCard(
              prnItems = uiState.dailyBoard.prnItems,
              onLogPrn = { prnItem ->
                if (prnItem.medication.siteRotationEnabled && prnItem.medication.rotationSites.size > 1) {
                  prnSiteOverrideItem = prnItem
                } else {
                  onLogPrnDose(prnItem.medication, prnItem.doseAmount, prnItem.suggestedSite)
                }
              },
              onMedicationClick = onOpenMedicationDetail,
            )
          }

          item {
            RecentDoseLogsCard(
              logs = uiState.snapshot.doseLogs.take(12),
              medications = uiState.snapshot.medications,
              onDeleteLog = onDeleteLog,
            )
          }
        }
      }
    } else {
      // Single-pane layout for compact phone / folded screen
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
      ) {
        item {
          DateAndProgressHeader(
            uiState = uiState,
            onShiftDate = onShiftDate,
            onResetToToday = onResetToToday,
            onOpenSettings = onOpenSettings,
          )
        }

        if (uiState.dailyBoard.bucketGroups.isEmpty()) {
          item {
            EmptyScheduleCard()
          }
        } else {
          items(uiState.dailyBoard.bucketGroups, key = { it.bucket.id }) { group ->
            BucketSectionCard(
              group = group,
              snoozeMinutes = settings.snoozeMinutes,
              globalNotificationsEnabled = settings.notificationsEnabled,
              globalAlarmsEnabled = settings.alarmsEnabled,
              onTakeAll = { onTakeAllInBucket(group.bucket.id) },
              onTakeItem = { item -> handlePrimaryTakeClick(item) },
              onSkipItem = onSkipScheduledDose,
              onSnoozeItem = onSnoozeScheduledDose,
              onUndoItem = onUndoScheduledDose,
              onOverrideSiteClick = { siteOverrideDialogItem = it },
              onSendReminderClick = triggerNotification,
              onMedicationClick = onOpenMedicationDetail,
            )
          }
        }

        item {
          PrnSectionCard(
            prnItems = uiState.dailyBoard.prnItems,
            onLogPrn = { prnItem ->
              if (prnItem.medication.siteRotationEnabled && prnItem.medication.rotationSites.size > 1) {
                prnSiteOverrideItem = prnItem
              } else {
                onLogPrnDose(prnItem.medication, prnItem.doseAmount, prnItem.suggestedSite)
              }
            },
            onMedicationClick = onOpenMedicationDetail,
          )
        }

        item {
          RecentDoseLogsCard(
            logs = uiState.snapshot.doseLogs.take(8),
            medications = uiState.snapshot.medications,
            onDeleteLog = onDeleteLog,
          )
        }
      }
    }
  }

  // Site override dialog for scheduled dose
  siteOverrideDialogItem?.let { item ->
    SiteSelectionDialog(
      medication = item.medication,
      suggestedSite = item.suggestedSite,
      lastSiteUsed = item.lastSiteUsed,
      onDismiss = { siteOverrideDialogItem = null },
      onConfirmSiteAndTake = { chosenSite ->
        onTakeScheduledDose(item, chosenSite)
        siteOverrideDialogItem = null
      },
    )
  }

  // Confirm take dialog when confirmBeforeTake setting is enabled (for non-site-rotation meds)
  confirmTakeDialogItem?.let { item ->
    AlertDialog(
      onDismissRequest = { confirmTakeDialogItem = null },
      title = { Text("Confirm Dose: ${item.medication.displayTitle}") },
      text = {
        Text(
          text = "Log ${item.formattedDoseInstruction()} for ${item.bucket.name} (${item.bucket.formattedTime()})?",
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onTakeScheduledDose(item, item.suggestedSite)
            confirmTakeDialogItem = null
          }
        ) {
          Text("Log Dose Taken")
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmTakeDialogItem = null }) {
          Text("Cancel")
        }
      },
    )
  }

  // Site override dialog for PRN dose
  prnSiteOverrideItem?.let { prnItem ->
    SiteSelectionDialog(
      medication = prnItem.medication,
      suggestedSite = prnItem.suggestedSite,
      lastSiteUsed = prnItem.pacing.lastSiteUsed,
      onDismiss = { prnSiteOverrideItem = null },
      onConfirmSiteAndTake = { chosenSite ->
        onLogPrnDose(prnItem.medication, prnItem.doseAmount, chosenSite)
        prnSiteOverrideItem = null
      },
    )
  }

  // Window notification stage test dialog
  notificationPickerItem?.let { item ->
    AlertDialog(
      onDismissRequest = { notificationPickerItem = null },
      title = { Text("Dose Window Notification: ${item.medication.displayTitle}") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Window (${item.bucket.name}): ${item.bucket.formattedTime()}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = "Choose which stage of the window behavior to trigger right now:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FilledTonalButton(
            onClick = {
              notificationPickerItem = null
              dispatchNotificationStage(item, DoseNotificationStage.WINDOW_START_PERSISTENT)
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("1. Window Start (Pin Persistent Notification)")
          }
          Button(
            onClick = {
              notificationPickerItem = null
              dispatchNotificationStage(item, DoseNotificationStage.WINDOW_END_ALARM)
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("2. Window End (Ring Unlogged Alarm)")
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { notificationPickerItem = null }) {
          Text("Close")
        }
      },
    )
  }
}

@Composable
private fun DateAndProgressHeader(
  uiState: RegimenUiState,
  onShiftDate: (Long) -> Unit,
  onResetToToday: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val todayIso = LocalDate.now().toString()
  val isToday = uiState.selectedDateIso == todayIso
  val parsedDate = runCatching { LocalDate.parse(uiState.selectedDateIso) }.getOrDefault(LocalDate.now())
  val dateLabel = parsedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))

  ElevatedCard(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.elevatedCardColors(
      containerColor = MaterialTheme.colorScheme.primaryContainer,
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = { onShiftDate(-1) }) {
            Icon(
              Icons.AutoMirrored.Filled.KeyboardArrowLeft,
              contentDescription = "Previous day",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
          Column {
            Text(
              text = if (isToday) "Today • $dateLabel" else dateLabel,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
              text = "${uiState.dailyBoard.totalTakenCount} of ${uiState.dailyBoard.totalScheduledCount} scheduled doses taken",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
          }
          IconButton(onClick = { onShiftDate(1) }) {
            Icon(
              Icons.AutoMirrored.Filled.KeyboardArrowRight,
              contentDescription = "Next day",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          if (!uiState.snapshot.settings.notificationsEnabled) {
            AssistChip(
              onClick = onOpenSettings,
              label = { Text("Alerts Off") },
              leadingIcon = {
                Icon(
                  Icons.Default.NotificationsOff,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              modifier = Modifier.padding(end = 4.dp),
            )
          }
          if (!isToday) {
            TextButton(onClick = onResetToToday) {
              Text("Today", color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
          }
          IconButton(onClick = onOpenSettings) {
            Icon(
              Icons.Default.Settings,
              contentDescription = "Settings",
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }

      if (uiState.dailyBoard.totalScheduledCount > 0) {
        Spacer(modifier = Modifier.height(8.dp))
        val progress = uiState.dailyBoard.totalTakenCount.toFloat() /
          uiState.dailyBoard.totalScheduledCount.coerceAtLeast(1).toFloat()
        LinearProgressIndicator(
          progress = { progress },
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        )
      }
    }
  }
}

@Composable
private fun EmptyScheduleCard() {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "No fixed-time doses scheduled for this date",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Add or edit a medication in the Medications tab to assign it to Morning, Afternoon, Evening, or Bedtime.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun BucketSectionCard(
  group: BucketDoseGroup,
  snoozeMinutes: Int,
  globalNotificationsEnabled: Boolean,
  globalAlarmsEnabled: Boolean,
  onTakeAll: () -> Unit,
  onTakeItem: (ScheduledDoseItem) -> Unit,
  onSkipItem: (ScheduledDoseItem) -> Unit,
  onSnoozeItem: (ScheduledDoseItem) -> Unit,
  onUndoItem: (ScheduledDoseItem) -> Unit,
  onOverrideSiteClick: (ScheduledDoseItem) -> Unit,
  onSendReminderClick: (ScheduledDoseItem) -> Unit,
  onMedicationClick: (String) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .weight(1f)
            .padding(end = 8.dp),
        ) {
          Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = group.bucket.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
              )
              if (group.bucket.crossesMidnight) {
                Surface(
                  color = MaterialTheme.colorScheme.tertiaryContainer,
                  shape = MaterialTheme.shapes.extraSmall,
                ) {
                  Text(
                    text = "🌙 Overnight",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
            }
            val alarmNote = when {
              !globalNotificationsEnabled || !group.bucket.notificationsEnabled -> " • Notifications off"
              globalAlarmsEnabled && group.bucket.alarmAtWindowEnd ->
                " • Alarm at ${group.bucket.formattedEndTime()}"
              else -> " • Persistent notification only"
            }
            Text(
              text = "Window: ${group.bucket.formattedTime()}$alarmNote",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        if (group.pendingCount > 1) {
          FilledTonalButton(
            onClick = onTakeAll,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          ) {
            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Take All (${group.pendingCount})")
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        group.items.forEach { item ->
          ScheduledDoseRowCard(
            item = item,
            snoozeMinutes = snoozeMinutes,
            onTake = { onTakeItem(item) },
            onSkip = { onSkipItem(item) },
            onSnooze = { onSnoozeItem(item) },
            onUndo = { onUndoItem(item) },
            onOverrideSiteClick = { onOverrideSiteClick(item) },
            onSendReminderClick = { onSendReminderClick(item) },
            onMedicationClick = { onMedicationClick(item.medication.id) },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduledDoseRowCard(
  item: ScheduledDoseItem,
  snoozeMinutes: Int,
  onTake: () -> Unit,
  onSkip: () -> Unit,
  onSnooze: () -> Unit,
  onUndo: () -> Unit,
  onOverrideSiteClick: () -> Unit,
  onSendReminderClick: () -> Unit,
  onMedicationClick: () -> Unit,
) {
  val med = item.medication
  val containerColor = when {
    item.isTaken -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    item.isSkipped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    else -> MaterialTheme.colorScheme.surface
  }

  Surface(
    onClick = onMedicationClick,
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    tonalElevation = if (item.isCompleted) 0.dp else 2.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = med.displayTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "${med.strength} • ${med.form.displayName} • Dose: ${item.formattedDoseInstruction()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
          )
        }

        IconButton(
          onClick = onSendReminderClick,
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            Icons.Default.NotificationsActive,
            contentDescription = "Send 1-tap notification reminder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }

      // Injectable needle details if applicable
      med.injectableDetails?.let { inj ->
        val draw = inj.formattedDrawNeedle()?.let { "Draw: $it" }
        val inject = inj.formattedInjectNeedle()?.let { "Inject: $it" }
        val syringe = inj.syringeDescription ?: inj.syringeVolumeMl?.let { "${formatNum(it)} mL syringe" }
        val specs = listOfNotNull(syringe, draw, inject).joinToString(" • ")
        if (specs.isNotEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = specs,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Indication & Prescriber metadata
      val indicationLabel = item.indicationCondition?.name
        ?: med.indicationText.takeIf { it.isNotBlank() }
      val prescriberLabel = item.prescriber?.name
      if (indicationLabel != null || prescriberLabel != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = listOfNotNull(
            indicationLabel?.let { "For: $it" },
            prescriberLabel?.let { "Rx: $it" },
          ).joinToString(" • "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Site rotation chip (auto-advances from actual site used!)
      if (med.siteRotationEnabled && med.rotationSites.isNotEmpty()) {
        Spacer(modifier = Modifier.height(6.dp))
        val siteLabel = if (item.isTaken) {
          "Site used: ${item.existingLog?.siteUsed ?: item.suggestedSite ?: "—"}"
        } else {
          val prev = item.lastSiteUsed?.let { " (Last: $it)" } ?: ""
          "Next site: ${item.suggestedSite}$prev"
        }
        AssistChip(
          onClick = onOverrideSiteClick,
          label = { Text(siteLabel) },
          leadingIcon = {
            Icon(
              Icons.Default.Autorenew,
              contentDescription = "Change rotation site",
              modifier = Modifier.size(16.dp),
            )
          },
          colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
          ),
        )
      }

      // Pacing / safety constraint status if not yet completed
      if (!item.isCompleted && (item.pacing.isIntervalWarning || item.pacing.isDailyLimitReached)) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = item.pacing.statusSummary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // 1-Tap Action Row
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (item.isCompleted) {
          val statusBadgeText = if (item.isTaken) {
            val timeFormatted = item.existingLog?.actionTimestampMillis?.let { ts ->
              Instant.ofEpochMilli(ts)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a"))
            } ?: ""
            "✓ Taken $timeFormatted"
          } else {
            "✕ Skipped"
          }
          Text(
            text = statusBadgeText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (item.isTaken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically),
          )
          Spacer(modifier = Modifier.weight(1f))
          TextButton(onClick = onUndo) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Undo")
          }
        } else {
          Button(
            onClick = onTake,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            val btnLabel = if (!item.suggestedSite.isNullOrBlank()) {
              "Take (${item.suggestedSite})"
            } else {
              "Take"
            }
            Text(btnLabel)
          }

          OutlinedButton(
            onClick = onSnooze,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
          ) {
            Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (item.isSnoozed) "Snoozed (${snoozeMinutes}m)" else "Snooze (${snoozeMinutes}m)")
          }

          TextButton(
            onClick = onSkip,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
          ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Skip")
          }
        }
      }
    }
  }
}

@Composable
private fun PrnSectionCard(
  prnItems: List<PrnDoseItem>,
  onLogPrn: (PrnDoseItem) -> Unit,
  onMedicationClick: (String) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          Icons.Default.AccessTime,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.secondary,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text(
            text = "As-Needed (PRN) & Pacing Monitor",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "Checks minimum time between doses & daily limits on each medication",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      if (prnItems.isEmpty()) {
        Text(
          text = "No As-Needed (PRN) medications configured.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          prnItems.forEach { prnItem ->
            val med = prnItem.medication
            Surface(
              onClick = { onMedicationClick(med.id) },
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.surface,
              tonalElevation = 1.dp,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
              ) {
                Text(
                  text = "${med.displayTitle} (${med.strength})",
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = prnItem.pacing.statusSummary,
                      style = MaterialTheme.typography.bodySmall,
                      color = if (prnItem.pacing.canTakeNow) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.error
                      },
                      fontWeight = FontWeight.Medium,
                    )
                    if (!prnItem.suggestedSite.isNullOrBlank()) {
                      Text(
                        text = "Next site: ${prnItem.suggestedSite}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }

                  Spacer(modifier = Modifier.width(8.dp))

                  FilledTonalButton(
                    onClick = { onLogPrn(prnItem) },
                    enabled = !prnItem.pacing.isDailyLimitReached,
                  ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Log ${formatNum(prnItem.doseAmount)} ${prnItem.doseUnit.symbol}")
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

@Composable
private fun RecentDoseLogsCard(
  logs: List<DoseLog>,
  medications: List<Medication>,
  onDeleteLog: (String) -> Unit,
) {
  val medMap = remember(medications) { medications.associateBy { it.id } }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
        text = "Recent Dose & Site Log",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(8.dp))

      if (logs.isEmpty()) {
        Text(
          text = "No doses logged yet.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        logs.forEachIndexed { idx, log ->
          if (idx > 0) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
          }
          val med = medMap[log.medicationId]
          val dt = Instant.ofEpochMilli(log.actionTimestampMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "${med?.displayTitle ?: "Medication"} • ${formatNum(log.amountTaken)} ${log.amountUnit.symbol}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
              )
              val siteStr = log.siteUsed?.let { " • Site: $it" } ?: ""
              Text(
                text = "${log.status.displayName} • $dt$siteStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(
              onClick = { onDeleteLog(log.id) },
              modifier = Modifier.size(28.dp),
            ) {
              Icon(
                Icons.Default.Close,
                contentDescription = "Delete log entry",
                modifier = Modifier.size(16.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SiteSelectionDialog(
  medication: Medication,
  suggestedSite: String?,
  lastSiteUsed: String?,
  onDismiss: () -> Unit,
  onConfirmSiteAndTake: (String) -> Unit,
) {
  var selectedSite by remember(suggestedSite, medication) {
    mutableStateOf(suggestedSite ?: medication.rotationSites.firstOrNull().orEmpty())
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Select Site for ${medication.displayTitle}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!lastSiteUsed.isNullOrBlank()) {
          Text(
            text = "Last site used: $lastSiteUsed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Text(
          text = "Future doses will automatically continue rotating from the actual site you select below:",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        medication.rotationSites.forEach { site ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = selectedSite == site,
              onClick = { selectedSite = site },
            )
            Column(modifier = Modifier.padding(start = 6.dp)) {
              Text(
                text = site,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (site == suggestedSite) FontWeight.Bold else FontWeight.Normal,
              )
              if (site == suggestedSite) {
                Text(
                  text = "Recommended next in rotation",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirmSiteAndTake(selectedSite) },
        enabled = selectedSite.isNotBlank(),
      ) {
        Text("Log Dose at Selected Site")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

