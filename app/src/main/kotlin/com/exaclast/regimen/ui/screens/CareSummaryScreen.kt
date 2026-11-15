@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.exaclast.regimen.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.R
import com.exaclast.regimen.data.RegimenSnapshot
import com.exaclast.regimen.domain.CareSummary
import com.exaclast.regimen.ui.components.AdaptiveMediumTopAppBar
import com.exaclast.regimen.ui.components.CenteredContentBox
import com.exaclast.regimen.ui.components.GroupItem
import com.exaclast.regimen.ui.components.SectionHeader
import androidx.compose.ui.platform.LocalResources
import com.exaclast.regimen.model.texts
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.components.addHorizontal
import com.exaclast.regimen.ui.components.groupListItemColors
import java.time.LocalDate

/**
 * What a provider or first responder would want at a glance, with a share button that sends the same
 * content as plain text. Recomputed whenever the data changes.
 */
@Composable
fun CareSummaryScreen(snapshot: RegimenSnapshot, onBack: () -> Unit) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val summary = remember(snapshot, resources) {
    CareSummary.build(
      resources.texts(),
      snapshot,
      formatTaken = UiFormat::dateTime,
      formatDate = { UiFormat.date(it, withWeekday = false) },
    )
  }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  val subject = stringResource(R.string.summary_title)
  val chooserTitle = stringResource(R.string.summary_share_chooser)

  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      AdaptiveMediumTopAppBar(
        title = { Text(subject, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        subtitle = { Text(summary.patientName.ifEmpty { stringResource(R.string.summary_as_of_today) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
        },
        actions = {
          IconButton(
            onClick = {
              val text = CareSummary.toText(resources.texts(), summary, asOf = UiFormat.date(LocalDate.now(), withWeekday = false))
              val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
              }
              context.startActivityOrIgnore(Intent.createChooser(send, chooserTitle))
            },
            enabled = !summary.isEmpty,
          ) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.summary_share)) }
        },
        scrollBehavior = scrollBehavior,
      )
    },
  ) { padding ->
    if (summary.isEmpty) {
      EmptyListMessage(stringResource(R.string.summary_empty), Modifier.padding(padding))
      return@Scaffold
    }
    CenteredContentBox(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) { extra ->
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp).addHorizontal(extra),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
      ) {
        summary.sections.forEach { section ->
          item(key = "header-${section.title}") { SectionHeader(section.title) }
          itemsIndexed(section.lines, key = { index, _ -> "${section.title}-$index" }) { index, line ->
            GroupItem(index = index, count = section.lines.size) {
              ListItem(
                colors = groupListItemColors(),
                supportingContent = line.secondary?.let { { Text(it) } },
              ) { Text(line.primary) }
            }
          }
        }
      }
    }
  }
}
