@file:OptIn(ExperimentalMaterial3Api::class)

package com.exaclast.regimen.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.ui.NumberInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One snackbar host shared by every screen's Scaffold, so messages survive navigation. */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

@Composable
fun AppSnackbarHost() {
  SnackbarHost(LocalSnackbarHostState.current)
}

// region Grouped lists (Material 3 Expressive grouped-list look)

private val GroupOuterRadius = 20.dp
private val GroupInnerRadius = 4.dp

/** Shape for the [index]th of [count] rows in a group: large outer corners, small inner corners. */
fun groupItemShape(index: Int, count: Int): Shape {
  val top = if (index == 0) GroupOuterRadius else GroupInnerRadius
  val bottom = if (index == count - 1) GroupOuterRadius else GroupInnerRadius
  return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
fun SectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  action: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.weight(1f),
    )
    action?.invoke()
  }
}

/** A vertical group of rows separated by small gaps. Each row receives its index and the group size. */
@Composable
fun GroupedColumn(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(2.dp),
    content = content,
  )
}

@Composable
fun GroupItem(
  index: Int,
  count: Int,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val shape = groupItemShape(index, count)
  val color = MaterialTheme.colorScheme.surfaceContainerLow
  if (onClick != null) {
    Surface(onClick = onClick, shape = shape, color = color, modifier = modifier.fillMaxWidth()) { content() }
  } else {
    Surface(shape = shape, color = color, modifier = modifier.fillMaxWidth()) { content() }
  }
}

/** Transparent list-item colors for rows that sit on a group surface. */
@Composable
fun groupListItemColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

/** Label/value row for detail screens. */
@Composable
fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
  ListItem(
    overlineContent = { Text(label) },
    colors = groupListItemColors(),
    modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
  ) { Text(value) }
}

/** Renders label/value pairs as one group. */
@Composable
fun InfoGroup(rows: List<Pair<String, String>>, onRowClick: ((Int) -> Unit)? = null) {
  GroupedColumn {
    rows.forEachIndexed { i, (label, value) ->
      GroupItem(index = i, count = rows.size, onClick = onRowClick?.let { { it(i) } }) {
        InfoRow(label, value)
      }
    }
  }
}

/** Whole-row toggle: the row is the touch target and reads as one switch to TalkBack. */
@Composable
fun SwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  enabled: Boolean = true,
) {
  ListItem(
    supportingContent = subtitle?.let { { Text(it) } },
    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    colors = groupListItemColors(),
    modifier = modifier.toggleable(
      value = checked,
      enabled = enabled,
      role = Role.Switch,
      onValueChange = onCheckedChange,
    ),
  ) { Text(title) }
}

// endregion

@Composable
fun ConfirmDialog(
  title: String,
  text: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  destructive: Boolean = false,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(text) },
    confirmButton = {
      Button(
        onClick = onConfirm,
        colors = if (destructive) {
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
          )
        } else {
          ButtonDefaults.buttonColors()
        },
      ) { Text(confirmLabel) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * Full-screen editor: close (×) on the left, Save in the app bar, scrolling content that stays above the
 * keyboard. Replaces the old full-screen dialogs.
 */
@Composable
fun EditorScaffold(
  title: String,
  onClose: () -> Unit,
  onSave: () -> Unit,
  saveEnabled: Boolean,
  modifier: Modifier = Modifier,
  saveLabel: String = "Save",
  banner: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
          IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
        },
        actions = {
          TextButton(onClick = onSave, enabled = saveEnabled) { Text(saveLabel) }
        },
      )
    },
    snackbarHost = { AppSnackbarHost() },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .imePadding(),
    ) {
      banner?.invoke()
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
      )
    }
  }
}

/**
 * Read-only date field that opens a date picker. Value is an ISO date (`yyyy-MM-dd`) or blank.
 * DatePicker works in UTC-midnight millis, so conversion goes through the epoch day to avoid zone shifts.
 */
@Composable
fun DatePickerField(
  label: String,
  valueIso: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  allowClear: Boolean = true,
  supportingText: String? = null,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  val parsed = NumberInput.parseIsoDate(valueIso)
  Box(modifier = modifier) {
    OutlinedTextField(
      value = parsed?.let { UiFormat.date(it) } ?: valueIso,
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(label) },
      supportingText = supportingText?.let { { Text(it) } },
      trailingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
      modifier = Modifier.fillMaxWidth(),
    )
    // Overlay so the whole field opens the picker (read-only text fields don't report clicks).
    Box(
      modifier = Modifier
        .matchParentSize()
        .clickable(onClickLabel = "Choose $label") { open = true },
    )
  }
  if (open) {
    val state = rememberDatePickerState(
      initialSelectedDateMillis = (parsed ?: LocalDate.now()).toEpochDay() * 86_400_000L,
    )
    DatePickerDialog(
      onDismissRequest = { open = false },
      confirmButton = {
        TextButton(onClick = {
          state.selectedDateMillis?.let { millis ->
            onValueChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
          }
          open = false
        }) { Text("OK") }
      },
      dismissButton = {
        Row {
          if (allowClear && valueIso.isNotBlank()) {
            TextButton(onClick = {
              onValueChange("")
              open = false
            }) { Text("Clear") }
          }
          TextButton(onClick = { open = false }) { Text("Cancel") }
        }
      },
    ) {
      DatePicker(state = state)
    }
  }
}

/**
 * Dialog for selecting a date range (start to end inclusive).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerDialog(
  onDismissRequest: () -> Unit,
  onRangeSelected: (LocalDate, LocalDate) -> Unit,
) {
  val state = rememberDateRangePickerState()
  DatePickerDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = {
      TextButton(
        onClick = {
          val start = state.selectedStartDateMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
          }
          val end = state.selectedEndDateMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
          } ?: start
          if (start != null && end != null) {
            onRangeSelected(start, end)
          }
          onDismissRequest()
        },
        enabled = state.selectedStartDateMillis != null,
      ) {
        Text("Add range")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismissRequest) {
        Text("Cancel")
      }
    },
  ) {
    DateRangePicker(
      state = state,
      title = {
        Text(
          text = "Select date range",
          modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
          style = MaterialTheme.typography.titleMedium,
        )
      },
    )
  }
}

/** Read-only time field that opens the Material time picker (honors the device 12/24-hour setting). */
@Composable
fun TimePickerField(
  label: String,
  hour: Int,
  minute: Int,
  onTimeChange: (hour: Int, minute: Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  val context = LocalContext.current
  Box(modifier = modifier) {
    OutlinedTextField(
      value = TimeOfDayBucket.formatClockTime(hour, minute),
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(label) },
      trailingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) },
      modifier = Modifier.fillMaxWidth(),
    )
    Box(
      modifier = Modifier
        .matchParentSize()
        .clickable(onClickLabel = "Choose $label") { open = true },
    )
  }
  if (open) {
    val state = rememberTimePickerState(
      initialHour = hour,
      initialMinute = minute,
      is24Hour = DateFormat.is24HourFormat(context),
    )
    TimePickerDialog(
      onDismissRequest = { open = false },
      title = { Text(label) },
      confirmButton = {
        TextButton(onClick = {
          onTimeChange(state.hour, state.minute)
          open = false
        }) { Text("OK") }
      },
      dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    ) {
      TimePicker(state = state)
    }
  }
}

/** Standard content padding for list screens (room for an extended FAB at the bottom). */
val ListContentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)
