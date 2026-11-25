package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.model.Labeled
import com.exaclast.regimen.ui.components.AdaptiveSingleChoiceRow
import com.exaclast.regimen.ui.components.AppSnackbarHost
import com.exaclast.regimen.ui.components.tabContentInsets
import com.exaclast.regimen.ui.components.label

private enum class CareSubTab(override val labelRes: Int) : Labeled {
  INDICATIONS(R.string.indications_title),
  PROVIDERS(R.string.providers_title),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareScreen(
  snapshot: RegimenSnapshot,
  onOpenIndication: (String) -> Unit,
  onAddIndication: () -> Unit,
  onOpenProvider: (String) -> Unit,
  onAddProvider: () -> Unit,
  onOpenSummary: () -> Unit,
) {
  val tracksIndications = snapshot.tracksIndications
  val tracksProviders = snapshot.tracksProviders
  if (tracksIndications && !tracksProviders) {
    IndicationListScreen(
      snapshot = snapshot,
      onOpenIndication = onOpenIndication,
      onAddIndication = onAddIndication,
    )
    return
  }
  if (tracksProviders && !tracksIndications) {
    ProviderListScreen(
      snapshot = snapshot,
      onOpenProvider = onOpenProvider,
      onAddProvider = onAddProvider,
    )
    return
  }

  var selectedTab by rememberSaveable { mutableStateOf(CareSubTab.INDICATIONS) }
  val indicationsListState = rememberLazyListState()
  val providersListState = rememberLazyListState()
  val fabExpanded by remember {
    derivedStateOf {
      when (selectedTab) {
        CareSubTab.INDICATIONS -> indicationsListState.firstVisibleItemIndex == 0
        CareSubTab.PROVIDERS -> providersListState.firstVisibleItemIndex == 0
      }
    }
  }

  Scaffold(
    contentWindowInsets = tabContentInsets(),
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.care_title)) },
        actions = {
          IconButton(onClick = onOpenSummary) {
            Icon(Icons.Default.Description, contentDescription = stringResource(R.string.summary_title))
          }
        },
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = {
          when (selectedTab) {
            CareSubTab.INDICATIONS -> onAddIndication()
            CareSubTab.PROVIDERS -> onAddProvider()
          }
        },
        expanded = fabExpanded,
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = {
          Text(
            stringResource(
              when (selectedTab) {
                CareSubTab.INDICATIONS -> R.string.indications_add
                CareSubTab.PROVIDERS -> R.string.providers_add
              },
            ),
          )
        },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    val switcher: @Composable () -> Unit = {
      AdaptiveSingleChoiceRow(
        options = CareSubTab.entries,
        selected = selectedTab,
        onSelect = { selectedTab = it },
        label = { it.label() },
        modifier = Modifier.padding(bottom = 12.dp),
      )
    }
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      when (selectedTab) {
        CareSubTab.INDICATIONS -> IndicationListBody(
          snapshot = snapshot,
          listState = indicationsListState,
          onOpenIndication = onOpenIndication,
          topContent = switcher,
        )
        CareSubTab.PROVIDERS -> ProviderListBody(
          snapshot = snapshot,
          listState = providersListState,
          onOpenProvider = onOpenProvider,
          topContent = switcher,
        )
      }
    }
  }
}
