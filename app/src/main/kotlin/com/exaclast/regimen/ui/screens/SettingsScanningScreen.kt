@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.exaclast.regimen.R
import com.exaclast.regimen.data.DrugBarcodeScanner
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.model.texts
import com.exaclast.regimen.model.userMessage
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.CenteredContentBox
import com.exaclast.regimen.ui.components.SectionHeader
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.launch

@Composable
fun SettingsScanningScreen(
  settings: AppSettings,
  onSaveSettings: (AppSettings) -> Unit,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()
  var nanoStatus by remember { mutableStateOf<Int?>(null) }
  var nanoProgressMessage by remember { mutableStateOf<String?>(null) }
  val initiatingMessage = stringResource(R.string.nano_initiating)
  val downloadFailedMessage = stringResource(R.string.nano_download_failed)
  val nanoTexts = LocalResources.current.texts()

  LaunchedEffect(Unit) {
    nanoStatus = DrugBarcodeScanner.checkNanoStatus()
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        coroutineScope.launch {
          nanoStatus = DrugBarcodeScanner.checkNanoStatus()
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_scanning)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
          }
        },
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
        contentPadding = PaddingValues(start = 16.dp + extra, end = 16.dp + extra, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
      item(key = "scanning-section") {
        SectionHeader(stringResource(R.string.settings_scanning))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
          FdaLookupCard(
            allowFdaLookup = settings.allowFdaLookup,
            onAllowFdaLookupChange = { onSaveSettings(settings.copy(allowFdaLookup = it)) },
          )
          Spacer(Modifier.height(6.dp))
          PatientNameField(
            savedName = settings.patientName,
            onSave = { onSaveSettings(settings.copy(patientName = it)) },
          )
        }
      }
    }
    }
  }
}

/** Saves when editing finishes (Done or focus leaves), not on every keystroke. */
@Composable
fun PatientNameField(savedName: String, onSave: (String) -> Unit) {
  var text by rememberSaveable(savedName) { mutableStateOf(savedName) }
  val focusManager = LocalFocusManager.current
  fun commit() {
    if (text.trim() != savedName) onSave(text.trim())
  }
  Column {
    OutlinedTextField(
      value = text,
      onValueChange = { text = it },
      label = { Text(stringResource(R.string.settings_label_name)) },
      supportingText = { Text(stringResource(R.string.settings_label_name_help)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = {
        commit()
        focusManager.clearFocus()
      }),
      modifier = Modifier
        .fillMaxWidth()
        .onFocusChanged { if (!it.isFocused) commit() },
    )
  }
}

@Composable
fun NanoAiCard(
  nanoStatus: Int?,
  nanoProgressMessage: String?,
  onDownloadClick: () -> Unit,
  onSettingsClick: () -> Unit,
) {
  val isNanoReady = nanoStatus == FeatureStatus.AVAILABLE
  val nanoSubtitle = when (nanoStatus) {
    FeatureStatus.AVAILABLE -> stringResource(R.string.nano_ready)
    FeatureStatus.DOWNLOADING -> nanoProgressMessage ?: stringResource(R.string.nano_downloading)
    FeatureStatus.DOWNLOADABLE -> nanoProgressMessage ?: stringResource(R.string.nano_downloadable)
    FeatureStatus.UNAVAILABLE -> stringResource(R.string.nano_unsupported)
    else -> stringResource(R.string.nano_checking)
  }
  val nanoActionLabel = when (nanoStatus) {
    FeatureStatus.DOWNLOADING -> stringResource(R.string.nano_aicore_settings)
    FeatureStatus.DOWNLOADABLE -> stringResource(R.string.nano_download)
    else -> null
  }

  PermissionItemCard(
    title = stringResource(R.string.nano_title),
    subtitle = nanoSubtitle,
    isGranted = isNanoReady,
    actionLabel = nanoActionLabel,
    onActionClick = if (nanoActionLabel != null) {
      {
        if (nanoStatus == FeatureStatus.DOWNLOADING) {
          onSettingsClick()
        } else if (nanoStatus == FeatureStatus.DOWNLOADABLE) {
          onDownloadClick()
        }
      }
    } else null,
  )
}

@Composable
fun FdaLookupCard(
  allowFdaLookup: Boolean,
  onAllowFdaLookupChange: (Boolean) -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.small,
    color = if (allowFdaLookup) {
      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    } else {
      MaterialTheme.colorScheme.surface
    },
    modifier = Modifier
      .fillMaxWidth()
      .toggleable(
        value = allowFdaLookup,
        role = Role.Checkbox,
        onValueChange = onAllowFdaLookupChange,
      ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(
        checked = allowFdaLookup,
        onCheckedChange = null,
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.fda_title),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = if (allowFdaLookup) {
            MaterialTheme.colorScheme.onSecondaryContainer
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        Text(
          text = stringResource(R.string.fda_help),
          style = MaterialTheme.typography.bodySmall,
          color = if (allowFdaLookup) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
    }
  }
}
