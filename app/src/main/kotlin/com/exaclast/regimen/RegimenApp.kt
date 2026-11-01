package com.exaclast.regimen

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exaclast.regimen.model.ThemeMode
import com.exaclast.regimen.notifications.DoseNotificationStage
import com.exaclast.regimen.ui.RegimenViewModel
import com.exaclast.regimen.ui.TopLevelDestination
import com.exaclast.regimen.ui.screens.ConditionsScreen
import com.exaclast.regimen.ui.screens.MedicationsScreen
import com.exaclast.regimen.ui.screens.PrescribersScreen
import com.exaclast.regimen.ui.screens.SettingsDialog
import com.exaclast.regimen.ui.screens.TodayScreen
import com.exaclast.regimen.ui.theme.RegimenTheme
import java.time.LocalDate

@Composable
fun RegimenApp(
  viewModel: RegimenViewModel = viewModel(),
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsState()
  val currentDestination by viewModel.selectedDestination.collectAsState()
  val selectedMedicationId by viewModel.selectedMedicationId.collectAsState()
  val selectedPrescriberId by viewModel.selectedPrescriberId.collectAsState()
  val selectedConditionId by viewModel.selectedConditionId.collectAsState()

  var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

  val settings = uiState.snapshot.settings
  val systemDark = isSystemInDarkTheme()
  val useDarkTheme = when (settings.themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }

  val pendingDosesCount = uiState.dailyBoard.bucketGroups.sumOf { it.pendingCount }

  RegimenTheme(
    darkTheme = useDarkTheme,
    dynamicColor = settings.dynamicColor,
  ) {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background,
    ) {
      NavigationSuiteScaffold(
        navigationSuiteItems = {
          TopLevelDestination.entries.forEach { dest ->
            val icon = destinationIcon(dest)
            item(
              selected = currentDestination == dest,
              onClick = { viewModel.selectDestination(dest) },
              icon = {
                if (dest == TopLevelDestination.TODAY && pendingDosesCount > 0) {
                  BadgedBox(
                    badge = {
                      Badge { Text(pendingDosesCount.toString()) }
                    }
                  ) {
                    Icon(icon, contentDescription = dest.label)
                  }
                } else {
                  Icon(icon, contentDescription = dest.label)
                }
              },
              label = { Text(dest.label) },
            )
          }
        }
      ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

          when (currentDestination) {
            TopLevelDestination.TODAY -> {
              TodayScreen(
                uiState = uiState,
                onShiftDate = { days -> viewModel.shiftDateByDays(days) },
                onResetToToday = { viewModel.selectDate(LocalDate.now().toString()) },
                onTakeScheduledDose = { item, siteOverride ->
                  viewModel.takeScheduledDose(item, siteOverride)
                },
                onSkipScheduledDose = { item -> viewModel.skipScheduledDose(item) },
                onSnoozeScheduledDose = { item ->
                  viewModel.snoozeScheduledDose(item)
                  Toast.makeText(
                    context,
                    "Snoozed for ${settings.snoozeMinutes} minutes",
                    Toast.LENGTH_SHORT,
                  ).show()
                },
                onUndoScheduledDose = { item -> viewModel.undoScheduledDoseLog(item) },
                onTakeAllInBucket = { bucketId -> viewModel.takeAllPendingInBucket(bucketId) },
                onLogPrnDose = { med, amount, site ->
                  viewModel.logPrnOrCustomDose(
                    medication = med,
                    amount = amount,
                    unit = med.defaultDoseUnit,
                    siteUsed = site,
                  )
                },
                onDeleteLog = { logId -> viewModel.deleteDoseLog(logId) },
                onSendTestNotification = { item, stage ->
                  if (!settings.notificationsEnabled) {
                    Toast.makeText(
                      context,
                      "Notifications are globally disabled in Settings",
                      Toast.LENGTH_SHORT,
                    ).show()
                    return@TodayScreen
                  }
                  if (stage == DoseNotificationStage.WINDOW_END_ALARM && !settings.alarmsEnabled) {
                    Toast.makeText(
                      context,
                      "End-of-window alarms are disabled in Settings",
                      Toast.LENGTH_SHORT,
                    ).show()
                    return@TodayScreen
                  }
                  val posted = viewModel.sendTestReminder(item, stage)
                  if (posted) {
                    val msg = if (stage == DoseNotificationStage.WINDOW_END_ALARM) {
                      "Ringing end-of-window alarm for ${item.medication.displayTitle}"
                    } else {
                      "Pinned persistent window notification for ${item.medication.displayTitle}"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                  } else {
                    Toast.makeText(
                      context,
                      "Grant notification permission to receive reminders",
                      Toast.LENGTH_SHORT,
                    ).show()
                  }
                },
                onOpenMedicationDetail = { medId ->
                  viewModel.selectMedication(medId)
                  viewModel.selectDestination(TopLevelDestination.MEDICATIONS)
                },
                onOpenSettings = { showSettingsDialog = true },
                modifier = contentModifier,
              )
            }

            TopLevelDestination.MEDICATIONS -> {
              MedicationsScreen(
                snapshot = uiState.snapshot,
                selectedMedicationId = selectedMedicationId,
                onSelectMedication = { viewModel.selectMedication(it) },
                onSaveMedication = { med -> viewModel.saveMedication(med) },
                onDeleteMedication = { viewModel.deleteMedication(it) },
                onSaveSchedule = { sched -> viewModel.saveSchedule(sched) },
                onDeleteSchedule = { schedId -> viewModel.deleteSchedule(schedId) },
                onQuickLogDose = { med, amt, unit, site ->
                  viewModel.logPrnOrCustomDose(
                    medication = med,
                    amount = amt,
                    unit = unit,
                    siteUsed = site,
                  )
                  val siteText = site?.let { " ($it)" } ?: ""
                  Toast.makeText(
                    context,
                    "Logged ${med.displayTitle}$siteText",
                    Toast.LENGTH_SHORT,
                  ).show()
                },
                modifier = contentModifier,
              )
            }

            TopLevelDestination.PRESCRIBERS -> {
              PrescribersScreen(
                snapshot = uiState.snapshot,
                selectedPrescriberId = selectedPrescriberId,
                onSelectPrescriber = { viewModel.selectPrescriber(it) },
                onSavePrescriber = { viewModel.savePrescriber(it) },
                onDeletePrescriber = { viewModel.deletePrescriber(it) },
                onOpenMedication = { medId ->
                  viewModel.selectMedication(medId)
                  viewModel.selectDestination(TopLevelDestination.MEDICATIONS)
                },
                modifier = contentModifier,
              )
            }

            TopLevelDestination.CONDITIONS -> {
              ConditionsScreen(
                snapshot = uiState.snapshot,
                selectedConditionId = selectedConditionId,
                onSelectCondition = { viewModel.selectCondition(it) },
                onSaveCondition = { viewModel.saveCondition(it) },
                onDeleteCondition = { viewModel.deleteCondition(it) },
                onOpenMedication = { medId ->
                  viewModel.selectMedication(medId)
                  viewModel.selectDestination(TopLevelDestination.MEDICATIONS)
                },
                modifier = contentModifier,
              )
            }
          }
        }
      }

      if (showSettingsDialog) {
        SettingsDialog(
          settings = settings,
          buckets = uiState.snapshot.buckets,
          onSaveSettings = { viewModel.saveSettings(it) },
          onSaveBucket = { viewModel.saveBucket(it) },
          onExportJson = { viewModel.exportBackupJson() },
          onImportJson = { viewModel.importBackupJson(it) },
          onClearAllData = { viewModel.clearAllUserData() },
          onResetSampleData = { viewModel.resetToSampleData() },
          onDismiss = { showSettingsDialog = false },
        )
      }
    }
  }
}

private fun destinationIcon(destination: TopLevelDestination): ImageVector {
  return when (destination) {
    TopLevelDestination.TODAY -> Icons.Default.CheckCircle
    TopLevelDestination.MEDICATIONS -> Icons.Default.Medication
    TopLevelDestination.PRESCRIBERS -> Icons.Default.Badge
    TopLevelDestination.CONDITIONS -> Icons.Default.Favorite
  }
}
