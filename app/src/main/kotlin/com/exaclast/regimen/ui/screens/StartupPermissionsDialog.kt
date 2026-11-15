package com.exaclast.regimen.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exaclast.regimen.R
import com.exaclast.regimen.data.DrugBarcodeScanner
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.texts
import com.exaclast.regimen.model.userMessage
import com.exaclast.regimen.notifications.DoseNotificationManager
import com.exaclast.regimen.ui.components.SwitchRow
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.launch

@Composable
fun StartupPermissionsDialog(
  settings: AppSettings,
  tracksProviders: Boolean,
  tracksIndications: Boolean,
  onSaveSettings: (AppSettings) -> Unit,
  onDismiss: () -> Unit,
  onPermissionsChanged: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()
  var refreshTick by remember { mutableIntStateOf(0) }
  var nanoProgressMessage by remember { mutableStateOf<String?>(null) }
  val initiatingMessage = stringResource(R.string.nano_initiating)
  val downloadFailedMessage = stringResource(R.string.nano_download_failed)
  val nanoTexts = LocalResources.current.texts()

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        refreshTick++
        onPermissionsChanged()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val allCoreGranted = remember(refreshTick) {
    DoseNotificationManager.areCorePermissionsGranted(context)
  }
  val fullScreenGranted = remember(refreshTick) {
    DoseNotificationManager.canUseFullScreenIntent(context)
  }

  var nanoStatus by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(refreshTick) {
    nanoStatus = DrugBarcodeScanner.checkNanoStatus()
  }
  val isNanoSupported = nanoStatus != null && nanoStatus != FeatureStatus.UNAVAILABLE

  // Local until the dialog closes: saving per keystroke trimmed the text and reset the field
  // (no spaces could be typed) and re-armed every alarm on each character.
  var patientNameText by rememberSaveable { mutableStateOf(settings.patientName) }

  /** One write on close: the name and the "setup seen" flag together. */
  fun finish() {
    onSaveSettings(
      settings.copy(
        patientName = patientNameText.trim(),
        permissionsPromptDismissedVersion = AppSettings.CURRENT_PERMISSIONS_VERSION,
      )
    )
    onDismiss()
  }

  AlertDialog(
    onDismissRequest = ::finish,
    title = { Text(stringResource(R.string.setup_title)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          text = stringResource(if (isNanoSupported) R.string.setup_intro_nano else R.string.setup_intro),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = stringResource(R.string.setup_reminders_timing),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
          )
          PermissionsChecklistSection(
            onPermissionStateChanged = {
              refreshTick++
              onPermissionsChanged()
            },
          )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = stringResource(R.string.setup_track_question),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
          )
          Text(
            text = stringResource(R.string.setup_track_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          SwitchRow(
            title = stringResource(R.string.providers_title),
            subtitle = stringResource(R.string.setup_providers_help),
            checked = tracksProviders,
            onCheckedChange = { onSaveSettings(settings.copy(trackProviders = it)) },
          )
          SwitchRow(
            title = stringResource(R.string.indications_title),
            subtitle = stringResource(R.string.setup_indications_help),
            checked = tracksIndications,
            onCheckedChange = { onSaveSettings(settings.copy(trackIndications = it)) },
          )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = stringResource(if (isNanoSupported) R.string.setup_scanning_optional else R.string.setup_lookup_optional),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
          )
          if (isNanoSupported) {
            Text(
              text = stringResource(R.string.setup_nano_help),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            NanoAiCard(
              nanoStatus = nanoStatus,
              nanoProgressMessage = nanoProgressMessage,
              onDownloadClick = {
                coroutineScope.launch {
                  nanoProgressMessage = initiatingMessage
                  DrugBarcodeScanner.startNanoDownload(
                    onProgress = { msg -> nanoProgressMessage = msg.resolve(nanoTexts) }
                  ).fold(
                    onSuccess = {
                      nanoStatus = DrugBarcodeScanner.checkNanoStatus()
                      refreshTick++
                      onPermissionsChanged()
                    },
                    onFailure = { err ->
                      nanoProgressMessage = err.userMessage(nanoTexts) ?: downloadFailedMessage
                      DrugBarcodeScanner.openAiCoreSettings(context)
                    }
                  )
                }
              },
              onSettingsClick = {
                DrugBarcodeScanner.openAiCoreSettings(context)
              },
            )
          }

          FdaLookupCard(
            allowFdaLookup = settings.allowFdaLookup,
            onAllowFdaLookupChange = { onSaveSettings(settings.copy(allowFdaLookup = it)) },
          )

          if (isNanoSupported) {
            Text(
              text = stringResource(R.string.setup_name_title),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier
                .padding(top = 6.dp)
                .semantics { heading() },
            )
            Text(
              text = stringResource(R.string.setup_name_help),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
              value = patientNameText,
              onValueChange = { patientNameText = it },
              label = { Text(stringResource(R.string.setup_name_label)) },
              placeholder = { Text(stringResource(R.string.setup_name_hint)) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
              ),
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = ::finish) {
        Text(
          stringResource(
            if (allCoreGranted && fullScreenGranted) {
              R.string.setup_all_set
            } else if (allCoreGranted) {
              R.string.action_continue
            } else {
              R.string.setup_done_for_now
            },
          ),
        )
      }
    },
  )
}

@Composable
fun PermissionsChecklistSection(
  onPermissionStateChanged: () -> Unit = {},
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var refreshTick by remember { mutableIntStateOf(0) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        refreshTick++
        onPermissionStateChanged()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val notifGranted = remember(refreshTick) {
    DoseNotificationManager.hasNotificationPermission(context)
  }
  val exactAlarmsGranted = remember(refreshTick) {
    DoseNotificationManager.canScheduleExactAlarms(context)
  }
  val fullScreenGranted = remember(refreshTick) {
    DoseNotificationManager.canUseFullScreenIntent(context)
  }

  val notifPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    refreshTick++
    onPermissionStateChanged()
    if (!granted) {
      DoseNotificationManager.openAppNotificationSettings(context)
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PermissionItemCard(
      title = stringResource(R.string.perm_notifications),
      subtitle = stringResource(if (notifGranted) R.string.perm_notifications_granted else R.string.perm_notifications_needed),
      isGranted = notifGranted,
      actionLabel = stringResource(R.string.perm_allow),
      onActionClick = {
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      },
    )

    PermissionItemCard(
      title = stringResource(R.string.perm_exact_alarms),
      subtitle = stringResource(if (exactAlarmsGranted) R.string.perm_exact_alarms_granted else R.string.perm_exact_alarms_needed),
      isGranted = exactAlarmsGranted,
      actionLabel = stringResource(R.string.perm_allow),
      onActionClick = {
        DoseNotificationManager.openExactAlarmSettings(context)
      },
    )

    PermissionItemCard(
      title = stringResource(R.string.perm_full_screen),
      subtitle = stringResource(if (fullScreenGranted) R.string.perm_full_screen_granted else R.string.perm_full_screen_needed),
      isGranted = fullScreenGranted,
      actionLabel = stringResource(R.string.perm_allow),
      onActionClick = {
        DoseNotificationManager.openFullScreenIntentSettings(context)
      },
    )
  }
}

@Composable
fun PermissionItemCard(
  title: String,
  subtitle: String,
  isGranted: Boolean,
  actionLabel: String? = null,
  onActionClick: (() -> Unit)? = null,
) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = if (isGranted) {
      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    } else {
      MaterialTheme.colorScheme.surface
    },
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(end = 8.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = if (isGranted) {
            MaterialTheme.colorScheme.onSecondaryContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = if (isGranted) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
      if (isGranted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = stringResource(R.string.perm_active),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      } else if (actionLabel != null && onActionClick != null) {
        FilledTonalButton(onClick = onActionClick) {
          Text(actionLabel)
        }
      }
    }
  }
}
