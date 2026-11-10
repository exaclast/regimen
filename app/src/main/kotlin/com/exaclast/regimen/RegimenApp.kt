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
import com.exaclast.regimen.domain.IndicationTimeline
import com.exaclast.regimen.domain.DoseEngine
import com.exaclast.regimen.domain.DoseTimeline
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.ThemeMode
import com.exaclast.regimen.notifications.DoseNotificationStage
import com.exaclast.regimen.ui.RegimenViewModel
import com.exaclast.regimen.ui.components.LocalSnackbarHostState
import com.exaclast.regimen.ui.navigation.BackupEncryptionMode
import com.exaclast.regimen.ui.navigation.Navigator
import com.exaclast.regimen.ui.navigation.Route
import com.exaclast.regimen.ui.navigation.TopLevel
import com.exaclast.regimen.ui.navigation.isFullScreenTask
import com.exaclast.regimen.ui.screens.BackupEncryptionScreen
import com.exaclast.regimen.ui.screens.BackupRestoreScreen
import com.exaclast.regimen.ui.screens.IndicationDetailScreen
import com.exaclast.regimen.ui.screens.IndicationEditorScreen
import com.exaclast.regimen.ui.screens.IndicationListScreen
import com.exaclast.regimen.ui.screens.DiagnosticsTarget
import com.exaclast.regimen.ui.screens.DoseLogEditorScreen
import com.exaclast.regimen.ui.screens.DoseWindowEditorScreen
import com.exaclast.regimen.ui.screens.MedicationDetailScreen
import com.exaclast.regimen.ui.screens.MedicationEditorScreen
import com.exaclast.regimen.ui.screens.MedicationListScreen
import com.exaclast.regimen.ui.screens.ProviderDetailScreen
import com.exaclast.regimen.ui.screens.ProviderEditorScreen
import com.exaclast.regimen.ui.screens.ProviderListScreen
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
  val backupEncryptionConfigured by viewModel.backupEncryptionConfigured.collectAsStateWithLifecycle()
  val pendingExport by viewModel.pendingExport.collectAsStateWithLifecycle()
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
  val visibleTabs = TopLevel.entries.filter { tab ->
    when (tab) {
      TopLevel.PROVIDERS -> snapshot.tracksProviders
      TopLevel.INDICATIONS -> snapshot.tracksIndications
      else -> true
    }
  }
  // Tracking switched off while that tab's screens were underneath (e.g. from Settings): once
  // back on them, return to Today rather than leave the user on a hidden section.
  val hiddenTabShowing = navigator.currentTab !in visibleTabs && current !is Route.Settings
  LaunchedEffect(hiddenTabShowing) {
    if (hiddenTabShowing) navigator.selectTab(TopLevel.TODAY)
  }
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
          visibleTabs.forEach { tab ->
            item(
              selected = navigator.currentTab == tab,
              onClick = {
                if (tab == TopLevel.TODAY) viewModel.goToToday()
                navigator.selectTab(tab)
              },
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
                onLogPrn = { item, site, takenAtMillis, note ->
                  viewModel.logPrnOrCustomDose(
                    item.medication,
                    item.doseAmount,
                    item.doseUnit,
                    site,
                    uiState.selectedDateIso,
                    takenAtMillis,
                    note,
                  )
                },
                onDeleteLog = viewModel::deleteDoseLog,
                onEditLog = { navigator.navigate(Route.DoseLogEditor(it.id)) },
                onOpenMedication = { navigator.navigate(Route.MedicationDetail(it)) },
                onOpenWindow = { navigator.navigate(Route.DoseWindowEditor(it)) },
                onAddMedication = { navigator.navigate(Route.MedicationEditor()) },
                onReviewYesterday = { viewModel.selectDate(uiState.yesterdayIso) },
                onLoadDemoData = viewModel::resetToSampleData,
                onRestoreBackup = { uri ->
                  viewModel.openBackup(uri)
                  navigator.navigate(Route.BackupRestore)
                },
              )
            }

            entry<Route.Medications> {
              MedicationListScreen(
                snapshot = snapshot,
                grouping = settings.medicationGrouping,
                onGroupingChange = { viewModel.saveSettings(settings.copy(medicationGrouping = it)) },
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
                dueDose = DoseTimeline.nextDoseFor(key.id, uiState.carriedOver, uiState.todayBoard),
                onTakeDose = { item, site, takenAtMillis, note ->
                  viewModel.takeScheduledDose(item, site, takenAtMillis, note)
                },
                onBack = navigator::back,
                onEdit = { navigator.navigate(Route.MedicationEditor(key.id)) },
                onDelete = {
                  viewModel.deleteMedication(key.id)
                  navigator.back()
                },
                onAddSchedule = { navigator.navigate(Route.ScheduleEditor(key.id)) },
                onEditSchedule = { navigator.navigate(Route.ScheduleEditor(key.id, it.id)) },
                onDeleteSchedule = viewModel::deleteSchedule,
                onLogDose = { med, site, takenAtMillis, note ->
                  viewModel.logPrnOrCustomDose(med, siteUsed = site, takenAtMillis = takenAtMillis, note = note)
                },
                onEditLog = { navigator.navigate(Route.DoseLogEditor(it.id)) },
                onOpenProvider = { navigator.navigate(Route.ProviderDetail(it)) },
                onOpenIndication = { navigator.navigate(Route.IndicationDetail(it)) },
              )
            }

            entry<Route.MedicationEditor> { key ->
              val existing = key.id?.let { id -> snapshot.medications.find { it.id == id } }
              if (key.id != null && existing == null) {
                LaunchedEffect(key) { navigator.back() }
              } else {
                // Past indications and providers are only offered if this medication already uses them.
                val today = java.time.LocalDate.now()
                MedicationEditorScreen(
                  initialMedication = existing,
                  indications = snapshot.indications.filter {
                    !snapshot.isArchived(it, today) || it.id in existing?.indicationIds.orEmpty()
                  },
                  providers = snapshot.providers.filter {
                    !IndicationTimeline.isPast(it, today) || it.id == existing?.prescriberId
                  },
                  showIndications = snapshot.tracksIndications,
                  showProviders = snapshot.tracksProviders,
                  configuredPatientName = settings.patientName,
                  allowFdaLookup = settings.allowFdaLookup,
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

            entry<Route.Providers> {
              ProviderListScreen(
                snapshot = snapshot,
                onOpenProvider = { navigator.navigate(Route.ProviderDetail(it)) },
                onAddProvider = { navigator.navigate(Route.ProviderEditor()) },
              )
            }

            entry<Route.DoseLogEditor> { key ->
              val log = snapshot.doseLogs.find { it.id == key.id }
              val medication = log?.let { l -> snapshot.medications.find { it.id == l.medicationId } }
              if (log == null || medication == null) {
                LaunchedEffect(key) { navigator.back() }
              } else {
                DoseLogEditorScreen(
                  log = log,
                  medication = medication,
                  onClose = navigator::back,
                  onSave = { updated ->
                    viewModel.saveDoseLog(updated)
                    navigator.back()
                  },
                )
              }
            }

            entry<Route.ProviderDetail> { key ->
              ProviderDetailScreen(
                providerId = key.id,
                snapshot = snapshot,
                onBack = navigator::back,
                onEdit = { navigator.navigate(Route.ProviderEditor(key.id)) },
                onDelete = {
                  viewModel.deleteProvider(key.id)
                  navigator.back()
                },
                onOpenMedication = { navigator.navigate(Route.MedicationDetail(it)) },
                onOpenIndication = { navigator.navigate(Route.IndicationDetail(it)) },
                onSetIndications = { ids -> viewModel.setProviderIndications(key.id, ids) },
              )
            }

            entry<Route.ProviderEditor> { key ->
              val existing = key.id?.let { id -> snapshot.providers.find { it.id == id } }
              ProviderEditorScreen(
                initialProvider = existing,
                onClose = navigator::back,
                onSave = { saved ->
                  viewModel.saveProvider(saved)
                  if (existing == null) navigator.replace(Route.ProviderDetail(saved.id)) else navigator.back()
                },
              )
            }

            entry<Route.Indications> {
              IndicationListScreen(
                snapshot = snapshot,
                onOpenIndication = { navigator.navigate(Route.IndicationDetail(it)) },
                onAddIndication = { navigator.navigate(Route.IndicationEditor()) },
              )
            }

            entry<Route.IndicationDetail> { key ->
              IndicationDetailScreen(
                indicationId = key.id,
                snapshot = snapshot,
                onBack = navigator::back,
                onEdit = { navigator.navigate(Route.IndicationEditor(key.id)) },
                onDelete = {
                  viewModel.deleteIndication(key.id)
                  navigator.back()
                },
                onOpenMedication = { navigator.navigate(Route.MedicationDetail(it)) },
                onOpenProvider = { navigator.navigate(Route.ProviderDetail(it)) },
                onSetMedications = { ids -> viewModel.setIndicationMedications(key.id, ids) },
                onSetProviders = { ids -> viewModel.setIndicationProviders(key.id, ids) },
                onHadAgain = { navigator.navigate(Route.IndicationEditor(copyFromId = key.id)) },
              )
            }

            entry<Route.IndicationEditor> { key ->
              val existing = key.id?.let { id -> snapshot.indications.find { it.id == id } }
              val template = remember(key.copyFromId) {
                key.copyFromId
                  ?.let { id -> snapshot.indications.find { it.id == id } }
                  ?.let { IndicationTimeline.recurrence(it, java.time.LocalDate.now()) }
              }
              IndicationEditorScreen(
                initialIndication = existing,
                template = template,
                snapshot = snapshot,
                onClose = navigator::back,
                onSave = { saved ->
                  viewModel.saveIndication(saved)
                  if (existing == null) navigator.replace(Route.IndicationDetail(saved.id)) else navigator.back()
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
                  indications = snapshot.indications,
                  providers = snapshot.providers,
                ).bucketGroups.flatMap { it.items }.let { items -> items.firstOrNull { !it.isCompleted } ?: items.firstOrNull() }
              }
              SettingsScreen(
                settings = settings,
                tracksProviders = snapshot.tracksProviders,
                tracksIndications = snapshot.tracksIndications,
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
                backupEncryptionConfigured = backupEncryptionConfigured,
                pendingExport = pendingExport,
                onConsumePendingExport = viewModel::consumePendingExport,
                suggestedBackupFileName = viewModel::suggestedBackupFileName,
                onExportBackup = viewModel::exportBackup,
                onRestoreFilePicked = { uri ->
                  viewModel.openBackup(uri)
                  navigator.navigate(Route.BackupRestore)
                },
                onOpenBackupEncryption = { mode -> navigator.navigate(Route.BackupEncryption(mode)) },
                onRequestExportAfterSetup = viewModel::requestExportAfterSetup,
                onClearAllData = viewModel::clearAllUserData,
                onResetSampleData = viewModel::resetToSampleData,
                onPermissionsChanged = viewModel::refreshPermissionsAndAlarms,
              )
            }

            entry<Route.BackupEncryption> { key ->
              BackupEncryptionScreen(
                mode = key.mode,
                onSetUp = viewModel::setUpBackupEncryption,
                onChange = viewModel::changeBackupPassphrase,
                onReveal = viewModel::revealRecoveryCode,
                onReset = {
                  navigator.back()
                  navigator.navigate(Route.BackupEncryption(BackupEncryptionMode.RESET))
                },
                onDone = { changed ->
                  if (changed && key.mode == BackupEncryptionMode.CHANGE) {
                    Toast.makeText(context, "Backup passphrase changed", Toast.LENGTH_SHORT).show()
                  }
                  navigator.back()
                },
                onClose = {
                  // Leaving setup without a passphrase cancels a pending export.
                  if (!backupEncryptionConfigured) viewModel.consumePendingExport()
                  navigator.back()
                },
              )
            }

            entry<Route.BackupRestore> {
              val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()
              BackupRestoreScreen(
                state = restoreState,
                onUnlock = viewModel::unlockBackup,
                onApply = viewModel::applyRestore,
                onClose = {
                  viewModel.resetRestore()
                  if (navigator.current is Route.BackupRestore) navigator.back()
                },
              )
            }

            entry<Route.DoseWindowEditor> { key ->
              val existing = key.id?.let { id -> snapshot.buckets.find { it.id == id } }
              DoseWindowEditorScreen(
                initialBucket = existing,
                canDelete = snapshot.buckets.size > 1,
                allWindows = snapshot.buckets,
                schedules = snapshot.schedules,
                medications = snapshot.medications,
                globalNotificationsEnabled = settings.notificationsEnabled,
                globalAlarmsEnabled = settings.alarmsEnabled,
                onClose = navigator::back,
                onSave = {
                  viewModel.saveBucket(it)
                  navigator.back()
                },
                onDelete = { moveToId ->
                  existing?.let { viewModel.deleteBucket(it.id, moveToId) }
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
          settings = settings,
          tracksProviders = snapshot.tracksProviders,
          tracksIndications = snapshot.tracksIndications,
          onSaveSettings = viewModel::saveSettings,
          // The dialog saves its own "seen" flag together with the name typed into it; saving
          // here too (from an older settings copy) would overwrite that name.
          onDismiss = {},
          onPermissionsChanged = viewModel::refreshPermissionsAndAlarms,
        )
      }
    }
  }
}
