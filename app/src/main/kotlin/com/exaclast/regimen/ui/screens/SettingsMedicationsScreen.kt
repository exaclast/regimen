@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.model.AppSettings
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.CenteredContentBox
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.SectionHeader
import com.exaclast.regimen.ui.components.SwitchRow

@Composable
fun SettingsMedicationsScreen(
  settings: AppSettings,
  tracksProviders: Boolean,
  tracksIndications: Boolean,
  onSaveSettings: (AppSettings) -> Unit,
  onBack: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_category_medications)) },
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
      ) {
      item(key = "tracking-section") {
        SectionHeader(stringResource(R.string.settings_tracking))
        GroupedColumn {
          GroupItem(0, 2) {
            SwitchRow(
              title = stringResource(R.string.providers_title),
              subtitle = stringResource(R.string.settings_providers_help),
              checked = tracksProviders,
              onCheckedChange = { onSaveSettings(settings.copy(trackProviders = it)) },
            )
          }
          GroupItem(1, 2) {
            SwitchRow(
              title = stringResource(R.string.indications_title),
              subtitle = stringResource(R.string.settings_indications_help),
              checked = tracksIndications,
              onCheckedChange = { onSaveSettings(settings.copy(trackIndications = it)) },
            )
          }
        }
        Spacer(Modifier.height(8.dp))
        Text(
          stringResource(R.string.settings_tracking_help),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      item(key = "privacy-section") {
        Spacer(Modifier.height(16.dp))
        SectionHeader(stringResource(R.string.settings_discreet))
        GroupedColumn {
          GroupItem(0, 1) {
            SwitchRow(
              title = stringResource(R.string.settings_discreet),
              subtitle = stringResource(R.string.settings_discreet_help),
              checked = settings.discreetNotifications,
              onCheckedChange = { onSaveSettings(settings.copy(discreetNotifications = it)) },
            )
          }
        }
        Spacer(Modifier.height(16.dp))
        PatientNameField(
          savedName = settings.patientName,
          onSave = { onSaveSettings(settings.copy(patientName = it)) },
        )
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
