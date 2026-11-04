package com.exaclast.regimen

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.ThemeMode
import com.exaclast.regimen.notifications.DoseNotificationStage
import com.exaclast.regimen.ui.RegimenViewModel
import com.exaclast.regimen.ui.components.LocalSnackbarHostState
import com.exaclast.regimen.ui.navigation.Navigator
import com.exaclast.regimen.ui.navigation.Route
import com.exaclast.regimen.ui.navigation.TopLevel
import com.exaclast.regimen.ui.navigation.isFullScreenTask
import com.exaclast.regimen.ui.screens.ConditionDetailScreen
import com.exaclast.regimen.ui.screens.ConditionEditorScreen
import com.exaclast.regimen.ui.screens.ConditionListScreen
import com.exaclast.regimen.ui.screens.DiagnosticsTarget
import com.exaclast.regimen.ui.screens.DoseWindowEditorScreen
import com.exaclast.regimen.ui.screens.MedicationDetailScreen
import com.exaclast.regimen.ui.screens.MedicationEditorScreen
import com.exaclast.regimen.ui.screens.MedicationListScreen
import com.exaclast.regimen.ui.screens.PrescriberDetailScreen
import com.exaclast.regimen.ui.screens.PrescriberEditorScreen
import com.exaclast.regimen.ui.screens.PrescriberListScreen
import com.exaclast.regimen.ui.screens.ScheduleEditorScreen
import com.exaclast.regimen.ui.screens.SettingsScreen
import com.exaclast.regimen.ui.screens.StartupPermissionsDialog
import com.exaclast.regimen.ui.screens.TodayScreen
import com.exaclast.regimen.ui.theme.RegimenTheme
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RegimenApp(
  viewModel: RegimenViewModel = viewModel(),
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snapshot = uiState.snapshot
  val settings = snapshot.settings
  val backStack = rememberNavBackStack(Route.Today)
  val navigator = remember(backStack) { Navigator(backStack) }
  val snackbarHostState = remember { SnackbarHostState() }

  // One message at a time; a newer message replaces the one on screen.
  LaunchedEffect(viewModel) {
    viewModel.messages.collectLatest { message ->
      val result = snackbarHostState.showSnackbar(
        message = message.text,
        actionLabel = if (message.undo != null) "Undo" else null,
        withDismissAction = message.undo != null,
        // Undo needs time to read and reach; Long also honors the system accessibility timeout.
        duration = if (message.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
      )
      if (result == SnackbarResult.ActionPerformed) message.undo?.let(viewModel::undo)
    }
  }

  val useDarkTheme = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }
  val current = navigator.current ?: Route.Today
  val layoutType = if (current.isFullScreenTask) {
    NavigationSuiteType.None
  } else {
    NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
  }

  RegimenTheme(darkTheme = useDarkTheme, dynamicColor = settings.dynamicColor) {
    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
      NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
          TopLevel.entries.forEach { tab ->
            item(
              selected = navigator.currentTab == tab,
              onClick = { navigator.selectTab(tab) },
              icon = {
                if (tab == TopLevel.TODAY && uiState.todayPendingCount > 0) {
                  BadgedBox(badge = { Badge { Text(uiState.todayPendingCount.toString()) } }) {
                    Icon(tab.icon, contentDescription = null)
                  }
                } else {
                  Icon(tab.icon, contentDescription = null)
                }
              },
              label = { Text(tab.label) },
            )
          }
        },
      ) {
        NavDisplay(
          backStack = backStack,
          onBack = { navigator.back() },
          entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
          entryProvider = entryProvider {
            entry<Route.Today> {
              TodayScreen(
                uiState = uiState,
                onPreviousDay = { viewModel.shiftDateByDays(-1) },
                onNextDay = { viewModel.shiftDateByDays(1) },
                onGoToToday = viewModel::goToToday,
                onOpenSettings = { navigator.navigate(Route.Settings) },
                onTake = viewModel::takeScheduledDose,
                onSkip = viewModel::skipScheduledDose,
                onSnooze = { viewModel.snoozeScheduledDose(it) },
                onClearLog = viewModel::clearScheduledDoseLog,
                onTakeAll = viewModel::takeAllPending,
                onLogPrn = { med, amount, site ->
                  viewModel.logPrnOrCustomDose(med, amount, med.defaultDoseUnit, site, uiState.selectedDateIso)
                },
                onDeleteLog = viewModel::deleteDoseLog,
                onOpenMedication = { navigator.navigate(Route.MedicationDetail(it)) },
                onOpenWindow = { navigator.navigate(Route.DoseWindowEditor(it)) },
                onAddMedication = { navigator.navigate(Route.MedicationEditor()) },
              )
            }

            entry<Route.Medications> {
              MedicationListScreen(
                snapshot = snapshot,
                onOpenMedication = { navigator.navigate(Route.MedicationDetail(it)) },
                onAddMedication = { navigator.navigate(Route.MedicationEditor()) },
              )
            }

            entry<Route.MedicationDetail> { key ->
              MedicationDetailScreen(
                medicationId = key.id,
                snapshot = snapshot,
                nowMillis = uiState.nowMillis,
                todayIso = uiState.todayIso,
                promptForSchedule = key.promptForSchedule,
                onBack = navigator::back,
                onEdit = { navigator.navigate(Route.MedicationEditor(key.id)) },
                onDelete = {
                  viewModel.deleteMedication(key.id)
                  navigator.back()
                },
                onAddSchedule = { navigator.navigate(Route.ScheduleEditor(key.id)) },
                onEditSchedule = { navigator.navigate(Route.ScheduleEditor(key.id, it.id)) },
                onDeleteSchedule = viewModel::deleteSchedule,
                onLogDose = { med, site -> viewModel.logPrnOrCustomDose(med, siteUsed = site) },
                onOpenPrescriber = { navigator.navigate(Route.PrescriberDetail(it)) },
                onOpenCondition = { navigator.navigate(Route.ConditionDetail(it)) },
              )
            }

            entry<Route.MedicationEditor> { key ->
              val existing = key.id?.let { id -> snapshot.medications.find { it.id == id } }
              if (key.id != null && existing == null) {
                LaunchedEffect(key) { navigator.back() }
              } else {
                MedicationEditorScreen(
                  initialMedication = existing,
                  conditions = snapshot.conditions,
                  prescribers = snapshot.prescribers,
                  configuredPatientName = settings.patientName,
                  onDismiss = navigator::back,
                  onSave = { med ->
                    viewModel.saveMedication(med)
                    if (existing == null) {
                      navigator.replace(Route.MedicationDetail(med.id, promptForSchedule = true))
                    } else {
                      navigator.back()
                    }
                  },
                )
              }
            }

            entry<Route.ScheduleEditor> { key ->
              val medication = snapshot.medications.find { it.id == key.medicationId }
              val schedule = key.scheduleId?.let { id -> snapshot.schedules.find { it.id == id } }
              if (medication == null || (key.scheduleId != null && schedule == null)) {
                LaunchedEffect(key) { navigator.back() }
              } else {
                ScheduleEditorScreen(
                  medication = medication,
                  initialSchedule = schedule,
                  buckets = snapshot.buckets,
                  onDismiss = navigator::back,
                  onSave = {
                    viewModel.saveSchedule(it)
                    navigator.back()
                  },
                )
              }
            }

            entry<Route.Prescribers> {
              PrescriberListScreen(
                snapshot = snapshot,
                onOpenPrescriber = { navigator.navigate(Route.PrescriberDetail(it)) },
                onAddPrescriber = { navigator.navigate(Route.PrescriberEditor()) },
              )
            }

            entry<Route.PrescriberDetail> { key ->
              PrescriberDetailScreen(
                prescriberId = key.id,
                snapshot = snapshot,
                onBack = navigator::back,
                onEdit = { navigator.navigate(Route.PrescriberEditor(key.id)) },
                onDelete = {
                  viewModel.deletePrescriber(key.id)
                  navigator.back()
                },
                onOpenMedication = { navigator.navigate(Route.MedicationDetail(it)) },
              )
            }

            entry<Route.PrescriberEditor> { key ->
              val existing = key.id?.let { id -> snapshot.prescribers.find { it.id == id } }
              PrescriberEditorScreen(
                initialPrescriber = existing,
                onClose = navigator::back,
                onSave = { saved ->
                  viewModel.savePrescriber(saved)
                  if (existing == null) navigator.replace(Route.PrescriberDetail(saved.id)) else navigator.back()
                },
              )
            }

            entry<Route.Conditions> {
              ConditionListScreen(
                snapshot = snapshot,
                onOpenCondition = { navigator.navigate(Route.ConditionDetail(it)) },
                onAddCondition = { navigator.navigate(Route.ConditionEditor()) },
              )
            }

            entry<Route.ConditionDetail> { key ->
              ConditionDetailScreen(
                conditionId = key.id,
                snapshot = snapshot,
                onBack = navigator::back,
                onEdit = { navigator.navigate(Route.ConditionEditor(key.id)) },
                onDelete = {
                  viewModel.deleteCondition(key.id)
                  navigator.back()
                },
                onOpenMedication = { navigator.navigate(Route.MedicationDetail(it)) },
              )
            }

            entry<Route.ConditionEditor> { key ->
              val existing = key.id?.let { id -> snapshot.conditions.find { it.id == id } }
              ConditionEditorScreen(
                initialCondition = existing,
                onClose = navigator::back,
                onSave = { saved ->
                  viewModel.saveCondition(saved)
                  if (existing == null) navigator.replace(Route.ConditionDetail(saved.id)) else navigator.back()
                },
              )
            }

            entry<Route.Settings> {
              // A dose due today to exercise test notifications with (prefer one still pending).
              val testItem = remember(snapshot, uiState.todayIso) {
                DoseEngine.buildDailyBoard(
                  dateIso = uiState.todayIso,
                  medications = snapshot.medications,
                  schedules = snapshot.schedules,
                  buckets = snapshot.buckets,
                  logs = snapshot.doseLogs,
                  conditions = snapshot.conditions,
                  prescribers = snapshot.prescribers,
                ).bucketGroups.flatMap { it.items }.let { items -> items.firstOrNull { !it.isCompleted } ?: items.firstOrNull() }
              }
              SettingsScreen(
                settings = settings,
                buckets = snapshot.buckets,
                diagnosticsTarget = testItem?.let {
                  DiagnosticsTarget("${it.medication.primaryName} (${it.bucket.name})")
                },
                onBack = navigator::back,
                onSaveSettings = viewModel::saveSettings,
                onOpenWindow = { navigator.navigate(Route.DoseWindowEditor(it)) },
                onResetWindows = viewModel::resetBucketsToDefault,
                onTestNotification = { stage ->
                  val item = testItem ?: return@SettingsScreen
                  val posted = viewModel.sendTestReminder(item, stage)
                  val text = when {
                    !posted -> "Allow notifications to test reminders"
                    stage == DoseNotificationStage.WINDOW_END_ALARM -> "Test alarm sent"
                    else -> "Test reminder sent"
                  }
                  Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                },
                onExportJson = viewModel::exportBackupJson,
                onImportJson = viewModel::importBackupJson,
                onClearAllData = viewModel::clearAllUserData,
                onResetSampleData = viewModel::resetToSampleData,
                onPermissionsChanged = viewModel::refreshPermissionsAndAlarms,
              )
            }

            entry<Route.DoseWindowEditor> { key ->
              val existing = key.id?.let { id -> snapshot.buckets.find { it.id == id } }
              DoseWindowEditorScreen(
                initialBucket = existing,
                canDelete = snapshot.buckets.size > 1,
                globalNotificationsEnabled = settings.notificationsEnabled,
                globalAlarmsEnabled = settings.alarmsEnabled,
                onClose = navigator::back,
                onSave = {
                  viewModel.saveBucket(it)
                  navigator.back()
                },
                onDelete = {
                  existing?.let { viewModel.deleteBucket(it.id) }
                  navigator.back()
                },
              )
            }
          },
        )
      }

      val needsStartupPermissions = settings.permissionsPromptDismissedVersion < AppSettings.CURRENT_PERMISSIONS_VERSION
      if (needsStartupPermissions && current !is Route.Settings) {
        StartupPermissionsDialog(
          onDismiss = {
            viewModel.saveSettings(
              settings.copy(permissionsPromptDismissedVersion = AppSettings.CURRENT_PERMISSIONS_VERSION)
            )
          },
          onPermissionsChanged = viewModel::refreshPermissionsAndAlarms,
        )
      }
    }
  }
}
