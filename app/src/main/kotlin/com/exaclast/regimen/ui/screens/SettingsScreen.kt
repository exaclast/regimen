@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.CenteredContentBox
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.GroupedColumn
import com.exaclast.regimen.ui.components.groupListItemColors

@Composable
fun SettingsScreen(
  showDiagnostics: Boolean,
  onNavigateReminders: () -> Unit,
  onNavigateMedications: () -> Unit,
  onNavigateBackup: () -> Unit,
  onNavigateAppearance: () -> Unit,
  onNavigateScanning: () -> Unit,
  onNavigateDiagnostics: () -> Unit,
  onBack: () -> Unit,
) {
  val itemCount = if (showDiagnostics) 6 else 5

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
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
        contentPadding = PaddingValues(start = 16.dp + extra, end = 16.dp + extra, bottom = 32.dp, top = 8.dp),
      ) {
      item(key = "categories") {
        GroupedColumn {
          GroupItem(0, itemCount) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_category_reminders_desc)) },
              trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
              colors = groupListItemColors(),
              modifier = Modifier.clickable(onClick = onNavigateReminders),
            ) {
              Text(stringResource(R.string.settings_category_reminders))
            }
          }
          GroupItem(1, itemCount) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_category_medications_desc)) },
              trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
              colors = groupListItemColors(),
              modifier = Modifier.clickable(onClick = onNavigateMedications),
            ) {
              Text(stringResource(R.string.settings_category_medications))
            }
          }
          GroupItem(2, itemCount) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_category_backup_desc)) },
              trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
              colors = groupListItemColors(),
              modifier = Modifier.clickable(onClick = onNavigateBackup),
            ) {
              Text(stringResource(R.string.settings_category_backup))
            }
          }
          GroupItem(3, itemCount) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_category_appearance_desc)) },
              trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
              colors = groupListItemColors(),
              modifier = Modifier.clickable(onClick = onNavigateAppearance),
            ) {
              Text(stringResource(R.string.settings_appearance))
            }
          }
          GroupItem(4, itemCount) {
            ListItem(
              supportingContent = { Text(stringResource(R.string.settings_category_scanning_desc)) },
              trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
              colors = groupListItemColors(),
              modifier = Modifier.clickable(onClick = onNavigateScanning),
            ) {
              Text(stringResource(R.string.settings_scanning))
            }
          }
          if (showDiagnostics) {
            GroupItem(5, itemCount) {
              ListItem(
                supportingContent = { Text(stringResource(R.string.settings_category_diagnostics_desc)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                colors = groupListItemColors(),
                modifier = Modifier.clickable(onClick = onNavigateDiagnostics),
              ) {
                Text(stringResource(R.string.settings_diagnostics))
              }
            }
          }
        }
      }
    }
    }
  }
}
