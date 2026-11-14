package com.exaclast.regimen.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.exaclast.regimen.R
import com.exaclast.regimen.data.CrossReferenceOutcome
import com.exaclast.regimen.data.CrossReferencedFdaCandidate
import com.exaclast.regimen.data.DrugBarcodeScanner
import com.exaclast.regimen.data.DrugCrossReferenceCriteria
import com.exaclast.regimen.data.FdaDrugLookupResult
import com.exaclast.regimen.data.FdaNdcLookupService
import com.exaclast.regimen.data.ParsedMedicationLabel
import com.exaclast.regimen.domain.IndicationTimeline
import com.exaclast.regimen.model.Concentration
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.NeedleHub
import com.exaclast.regimen.model.SiteMode
import com.exaclast.regimen.model.SyringeType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
import com.exaclast.regimen.model.formatExact
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.ui.components.DatePickerField
import com.exaclast.regimen.ui.components.DateRangePickerDialog
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.UiFormat
import com.exaclast.regimen.ui.NumberInput
import com.exaclast.regimen.ui.checkOptionalPositiveDecimal
import com.exaclast.regimen.ui.checkOptionalPositiveInt
import com.exaclast.regimen.ui.checkRequiredPositiveDecimal
import com.exaclast.regimen.ui.checkRequiredPositiveInt
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The line under the strength for forms measured by volume: what one mL holds and how much the default
 * dose is, or how to write the strength so volumes can be worked out. The flag marks a problem.
 */
internal fun volumeHint(strength: String, dose: Double?, doseUnit: DoseUnit): Pair<String, Boolean>? {
  if (strength.isBlank()) return null
  val concentration = Concentration.parse(strength)
    ?: return "To show volumes, write the strength per mL, like 200 mg/mL or 400 mg/5 mL." to false
  val perMl = "${concentration.formatted()}."
  if (dose == null || doseUnit == DoseUnit.ML) return perMl to false
  val volume = concentration.volumeMl(dose, doseUnit)
  return when {
    volume != null -> "$perMl ${formatNum(dose)} ${doseUnit.symbol} is ${formatNum(volume)} mL." to false
    doseUnit in listOf(DoseUnit.MG, DoseUnit.MCG, DoseUnit.UNITS, DoseUnit.IU) ->
      "$perMl A dose in ${doseUnit.symbol} can't be converted to mL from ${concentration.unit.symbol}." to true
    else -> perMl to false
  }
}

/** The standard hub color for a needle gauge, so the right needle is easy to pick out. */
@Composable
internal fun HubColorHint(gauge: Int?) {
  val hub = NeedleHub.colorFor(gauge) ?: return
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(
      modifier = Modifier
        .size(14.dp)
        .background(Color(hub.argb), CircleShape)
        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
    Text(
      text = "${gauge}G: ${hub.name.lowercase()} hub (standard color; some brands differ)",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownSelectorField(
  label: String,
  selectedText: String,
  options: List<T>,
  optionLabel: (T) -> String,
  onOptionSelected: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier,
  ) {
    OutlinedTextField(
      value = selectedText,
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
      modifier = Modifier
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        .fillMaxWidth(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(optionLabel(option)) },
          onClick = {
            onOptionSelected(option)
            expanded = false
          },
          contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
        )
      }
    }
  }
}

@Composable
private fun CollapsibleFormSection(
  title: String,
  subtitle: String? = null,
  initiallyExpanded: Boolean = false,
  content: @Composable () -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded }
          .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
          if (!subtitle.isNullOrBlank()) {
            Text(
              text = subtitle,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Icon(
          imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          HorizontalDivider()
          content()
        }
      }
    }
  }
}

/**
 * Streamlined Medication Editor Dialog:
 * - Clean vertical hierarchy with full-width long fields and 2-column short fields
 * - ExposedDropdownMenuBox selectors instead of sprawling chip grids
 * - Inline, non-verbose NDC-11 lookup inside the NDC text field trailing icon
 * - Collapsible sections for Pacing Limits & Optional Rx/Drug Metadata
 * - Separated from Schedule configuration
 */
@Composable
fun MedicationEditorScreen(
  initialMedication: Medication?,
  indications: List<Indication>,
  providers: List<Provider>,
  // Optional tracking: hidden fields keep any existing link untouched.
  showIndications: Boolean = true,
  showProviders: Boolean = true,
  configuredPatientName: String = "",
  allowFdaLookup: Boolean = false,
  onDismiss: () -> Unit,
  onSave: (Medication) -> Unit,
) {
  var formulaName by rememberSaveable { mutableStateOf(initialMedication?.formulaName.orEmpty()) }
  var brandName by rememberSaveable { mutableStateOf(initialMedication?.brandName.orEmpty()) }
  var manufacturer by rememberSaveable { mutableStateOf(initialMedication?.manufacturer.orEmpty()) }
  var ndc11 by rememberSaveable { mutableStateOf(initialMedication?.ndc11.orEmpty()) }
  var strength by rememberSaveable { mutableStateOf(initialMedication?.strength.orEmpty()) }
  var form by rememberSaveable { mutableStateOf(initialMedication?.form ?: MedicationForm.TABLET) }
  var defaultDoseAmountText by rememberSaveable {
    mutableStateOf(initialMedication?.defaultDoseAmount?.let { formatExact(it) } ?: "1")
  }
  var defaultDoseUnit by rememberSaveable {
    mutableStateOf(initialMedication?.defaultDoseUnit ?: DoseUnit.TABLET)
  }

  // Injectable fields
  val initInj = initialMedication?.injectableDetails
  var route by rememberSaveable { mutableStateOf(initInj?.route ?: InjectionRoute.IM) }
  var vialVolumeText by rememberSaveable {
    mutableStateOf(initInj?.vialVolumeMl?.let { formatExact(it) }.orEmpty())
  }
  var syringeVolumeText by rememberSaveable {
    mutableStateOf(initInj?.syringeVolumeMl?.let { formatExact(it) }.orEmpty())
  }
  var syringeType by rememberSaveable {
    // A size saved without a type shows as Other, so saving doesn't drop it.
    mutableStateOf(
      initInj?.syringeType
        ?: SyringeType.infer(initInj?.syringeDescription)
        ?: SyringeType.OTHER.takeIf { initInj?.syringeVolumeMl != null },
    )
  }
  var syringeDesc by rememberSaveable { mutableStateOf(initInj?.syringeDescription.orEmpty()) }
  val needleAttached = syringeType?.needleAttached == true && !form.isPrefilledInjector
  var drawGaugeText by rememberSaveable { mutableStateOf(initInj?.drawNeedleGauge?.toString().orEmpty()) }
  var drawLengthText by rememberSaveable {
    mutableStateOf(initInj?.drawNeedleLengthInches?.let { formatExact(it) }.orEmpty())
  }
  var injectGaugeText by rememberSaveable { mutableStateOf(initInj?.injectNeedleGauge?.toString().orEmpty()) }
  var injectLengthText by rememberSaveable {
    mutableStateOf(initInj?.injectNeedleLengthInches?.let { formatExact(it) }.orEmpty())
  }

  // Sites. Until the user picks a mode or edits the list, both follow the form (and route), so a
  // scanned topical starts with sites off and a subcutaneous pen on abdomen sites.
  var siteMode by rememberSaveable {
    mutableStateOf(initialMedication?.siteMode ?: (initialMedication?.form ?: MedicationForm.TABLET).defaultSiteMode)
  }
  // An existing medication's mode was chosen, unless its form had no sites to choose for.
  var siteModeChosen by rememberSaveable { mutableStateOf(initialMedication?.form?.hasSites == true) }
  val sites = rememberSaveable(
    saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() }),
  ) {
    (initialMedication?.sites ?: emptyList()).toMutableStateList()
  }
  var sitesChosen by rememberSaveable { mutableStateOf(initialMedication?.sites?.isNotEmpty() == true) }
  var newCustomSite by rememberSaveable { mutableStateOf("") }

  fun applyFormDefaults() {
    if (!siteModeChosen) siteMode = form.defaultSiteMode
    if (!sitesChosen) {
      sites.clear()
      sites.addAll(Medication.defaultSites(form, route))
    }
  }
  LaunchedEffect(form, route) { applyFormDefaults() }

  // Pacing & Safety Limits (on Medication object)
  var minIntervalHoursText by rememberSaveable {
    mutableStateOf(
      // Rounded for reading; converting back to whole minutes on save still gives the same value.
      initialMedication?.minIntervalMinutes?.let { formatNum(it / 60.0) }.orEmpty()
    )
  }
  var maxDailyDosesText by rememberSaveable {
    mutableStateOf(initialMedication?.maxDailyDoses?.let { formatExact(it) }.orEmpty())
  }
  var maxDailyAmountText by rememberSaveable {
    mutableStateOf(initialMedication?.maxDailyAmount?.let { formatExact(it) }.orEmpty())
  }

  // Indication & Provider
  var selectedIndicationIds by rememberSaveable { mutableStateOf(initialMedication?.indicationIds.orEmpty()) }
  var indicationText by rememberSaveable { mutableStateOf(initialMedication?.indicationText.orEmpty()) }
  var selectedPrescriberId by rememberSaveable { mutableStateOf(initialMedication?.prescriberId) }
  var prescribedDateIso by rememberSaveable { mutableStateOf(initialMedication?.prescribedDateIso.orEmpty()) }
  var notes by rememberSaveable { mutableStateOf(initialMedication?.notes.orEmpty()) }

  // Gemini Nano (AICore) Label Scanner & Cross-Reference lookup state
  val context = LocalContext.current
  val resources = LocalResources.current
  val coroutineScope = rememberCoroutineScope()
  var isGeminiNanoSupported by remember { mutableStateOf(false) }
  var isLookingUpNdc by remember { mutableStateOf(false) }
  var ndcErrorText by remember { mutableStateOf<String?>(null) }
  var ndcStatusText by remember { mutableStateOf<String?>(null) }
  var ndcMatched by remember { mutableStateOf(false) }
  var showLabelScanDialog by remember { mutableStateOf(false) }
  var manualLabelInput by remember { mutableStateOf("") }
  var pendingLabelPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
  // Photos taken so far of one container; a small bottle's label wraps around it, so it takes several.
  val capturedLabelPhotos = rememberSavableList<Uri> { emptyList() }
  var showCapturedPhotosDialog by rememberSaveable { mutableStateOf(false) }
  var disambiguationPrompt by remember {
    mutableStateOf<CrossReferenceOutcome.NeedsUserSelection?>(null)
  }

  LaunchedEffect(Unit) {
    isGeminiNanoSupported = DrugBarcodeScanner.isGeminiNanoAvailable()
  }

  fun applyFdaResultToForm(fda: FdaDrugLookupResult, statusSummary: String? = null) {
    ndc11 = fda.ndc11
    formulaName = fda.formulaName
    brandName = fda.brandName.orEmpty()
    fda.manufacturer?.let { manufacturer = it }
    strength = fda.strength
    form = fda.form
    defaultDoseUnit = fda.defaultDoseUnit
    fda.route?.let { route = it }
    fda.vialVolumeMl?.let { vialVolumeText = formatExact(it) }
    val metaParts = mutableListOf<String>()
    fda.lotNumber?.let { metaParts.add(resources.getString(R.string.label_lot, it)) }
    fda.expirationDateIso?.let { metaParts.add(resources.getString(R.string.label_exp, it)) }
    if (metaParts.isNotEmpty()) {
      val metaLine = metaParts.joinToString(" • ")
      ndcStatusText = statusSummary ?: resources.getString(R.string.editor_autofilled_meta, fda.ndc11, metaLine)
      if (!notes.contains(metaLine)) {
        notes = if (notes.isBlank()) metaLine else "$notes\n$metaLine"
      }
    } else {
      ndcStatusText = statusSummary ?: resources.getString(R.string.editor_autofilled, fda.ndc11)
    }
    ndcErrorText = null
    ndcMatched = true
  }

  fun runCrossReferenceLookup(criteria: DrugCrossReferenceCriteria) {
    if (!allowFdaLookup) return
    isLookingUpNdc = true
    ndcErrorText = null
    coroutineScope.launch {
      val outcome = FdaNdcLookupService.searchAndCrossReference(criteria)
      isLookingUpNdc = false
      when (outcome) {
        is CrossReferenceOutcome.UnambiguousMatch -> {
          val cand = outcome.candidate
          val signals = cand.matchedSignals.joinToString(", ")
          applyFdaResultToForm(
            fda = cand.result,
            statusSummary = resources.getString(R.string.editor_verified, cand.result.ndc11, signals),
          )
        }
        is CrossReferenceOutcome.NeedsUserSelection -> {
          disambiguationPrompt = outcome
        }
        is CrossReferenceOutcome.NoMatch -> {
          if (formulaName.isBlank() && brandName.isBlank()) {
            ndcErrorText = outcome.message
          } else {
            ndcStatusText = outcome.message
          }
        }
      }
    }
  }

  fun applyParsedMedicationLabel(parsedLabel: ParsedMedicationLabel) {
    if (!parsedLabel.hasAnyClinicalSignal) {
      ndcErrorText = resources.getString(R.string.editor_label_not_detected)
      return
    }
    ndcErrorText = null
    parsedLabel.extractedNdc?.let { ndc11 = FdaNdcLookupService.toCanonicalNdc11(it) }
    parsedLabel.brandName?.let { brandName = it }
    parsedLabel.formulaName?.let { formulaName = it }
    parsedLabel.strength?.let { strength = it }
    parsedLabel.form?.let { form = it }
    parsedLabel.defaultDoseUnit?.let { defaultDoseUnit = it }
    parsedLabel.defaultDoseAmount?.let { defaultDoseAmountText = formatExact(it) }
    parsedLabel.manufacturer?.let { manufacturer = it }

    // Auto-link prescriber if name matches an existing prescriber in snapshot
    parsedLabel.prescriberName?.let { scannedDoc ->
      val docTokens = scannedDoc.lowercase().split(" ", ",").filter { it.length >= 3 }
      val matchedPrescriber = providers.firstOrNull { p ->
        val pLower = p.name.lowercase()
        docTokens.isNotEmpty() && docTokens.all { t -> pLower.contains(t) }
      }
      if (matchedPrescriber != null) {
        selectedPrescriberId = matchedPrescriber.id
      }
    }

    val noteAdditions = mutableListOf<String>()
    parsedLabel.instructions?.let { noteAdditions.add(it) }
    val lotExp = listOfNotNull(
      parsedLabel.lotNumber?.let { resources.getString(R.string.label_lot, it) },
      parsedLabel.expirationDateIso?.let { resources.getString(R.string.label_exp, it) },
      parsedLabel.prescriberName?.takeIf { selectedPrescriberId == null }?.let { resources.getString(R.string.label_prescriber, it) },
    ).joinToString(" • ")
    if (lotExp.isNotEmpty()) {
      noteAdditions.add(lotExp)
    }
    noteAdditions.forEach { line ->
      if (!notes.contains(line)) {
        notes = if (notes.isBlank()) line else "$notes\n$line"
      }
    }

    if (parsedLabel.isCompound) {
      ndcMatched = true
      ndcStatusText = resources.getString(R.string.editor_parsed_compound)
    } else if (allowFdaLookup) {
      ndcStatusText = resources.getString(R.string.editor_parsed_cross_referencing)
      runCrossReferenceLookup(
        DrugCrossReferenceCriteria(
          ndc = parsedLabel.extractedNdc ?: ndc11.takeIf { it.isNotBlank() },
          formulaName = parsedLabel.formulaName ?: formulaName.takeIf { it.isNotBlank() },
          brandName = parsedLabel.brandName ?: brandName.takeIf { it.isNotBlank() },
          strength = parsedLabel.strength ?: strength.takeIf { it.isNotBlank() },
          form = parsedLabel.form ?: form,
          manufacturer = parsedLabel.manufacturer ?: manufacturer.takeIf { it.isNotBlank() },
        )
      )
    } else {
      ndcStatusText = resources.getString(R.string.editor_parsed_on_device)
    }
  }

  fun runLabelPhotoAnalysis(imageUris: List<Uri>) {
    isLookingUpNdc = true
    ndcErrorText = null
    ndcStatusText = resources.getString(R.string.editor_reading_label)
    coroutineScope.launch {
      val res = DrugBarcodeScanner.analyzeLabelFromPhotos(
        context = context,
        imageUris = imageUris,
        userConfiguredPatientName = configuredPatientName.takeIf { it.isNotBlank() },
        onStatusUpdate = { status -> ndcStatusText = status },
      )
      DrugBarcodeScanner.clearLabelPhotos(context)
      isLookingUpNdc = false
      res.fold(
        onSuccess = { parsed -> applyParsedMedicationLabel(parsed) },
        onFailure = { err -> ndcErrorText = err.localizedMessage ?: resources.getString(R.string.editor_analyze_failed) },
      )
    }
  }

  fun runGeminiLabelTextAnalysis(rawText: String) {
    isLookingUpNdc = true
    ndcErrorText = null
    ndcStatusText = resources.getString(R.string.editor_analyzing_text)
    coroutineScope.launch {
      val res = DrugBarcodeScanner.analyzeLabelFromText(
        rawLabelText = rawText,
        userConfiguredPatientName = configuredPatientName.takeIf { it.isNotBlank() },
        onStatusUpdate = { status -> ndcStatusText = status },
      )
      isLookingUpNdc = false
      res.fold(
        onSuccess = { parsed -> applyParsedMedicationLabel(parsed) },
        onFailure = { err -> ndcErrorText = err.localizedMessage ?: resources.getString(R.string.editor_analyze_text_failed) },
      )
    }
  }

  val cameraLabelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
  ) { success ->
    val uri = pendingLabelPhotoUri
    if (success && uri != null) {
      capturedLabelPhotos.add(uri)
    }
    // Also covers a cancelled shot after "Add another": the photos already taken stay reachable.
    if (capturedLabelPhotos.isNotEmpty()) showCapturedPhotosDialog = true
  }

  val galleryLabelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetMultipleContents()
  ) { uris: List<Uri> ->
    if (uris.isNotEmpty()) {
      runLabelPhotoAnalysis(uris)
    }
  }

  fun captureLabelPhoto() {
    val uri = runCatching { DrugBarcodeScanner.createTempLabelPhotoUri(context) }.getOrNull() ?: return
    pendingLabelPhotoUri = uri
    cameraLabelLauncher.launch(uri)
  }

  fun runNdcOrBarcodeLookup(rawQuery: String) {
    if (!allowFdaLookup) return
    val query = rawQuery.trim()
    if (query.isEmpty()) return
    isLookingUpNdc = true
    ndcErrorText = null
    ndcStatusText = null
    coroutineScope.launch {
      val res = FdaNdcLookupService.lookupByNdc(query)
      isLookingUpNdc = false
      res.fold(
        onSuccess = { fda ->
          applyFdaResultToForm(fda)
        },
        onFailure = { err ->
          ndcMatched = false
          ndcErrorText = err.message ?: resources.getString(R.string.editor_ndc_not_found)
        },
      )
    }
  }

  val hasInitialPacing = initialMedication?.let {
    it.minIntervalMinutes != null || it.maxDailyDoses != null || it.maxDailyAmount != null
  } ?: false

  val hasInitialOptionalDetails = initialMedication?.let {
    !it.ndc11.isNullOrBlank() ||
      !it.manufacturer.isNullOrBlank() ||
      it.prescriberId != null ||
      !it.prescribedDateIso.isNullOrBlank() ||
      !it.notes.isNullOrBlank()
  } ?: false

  // Strict numeric validation: unparseable input blocks Save instead of silently becoming a
  // default (e.g. "0,5" → 1.0 or "4h" → no interval limit).
  val doseCheck = checkRequiredPositiveDecimal(defaultDoseAmountText)
  val vialVolumeCheck = checkOptionalPositiveDecimal(vialVolumeText)
  val syringeVolumeCheck = checkOptionalPositiveDecimal(syringeVolumeText)
  val drawGaugeCheck = checkOptionalPositiveInt(drawGaugeText)
  val drawLengthCheck = checkOptionalPositiveDecimal(drawLengthText)
  val injectGaugeCheck = checkOptionalPositiveInt(injectGaugeText)
  val injectLengthCheck = checkOptionalPositiveDecimal(injectLengthText)
  val minIntervalCheck = checkOptionalPositiveDecimal(minIntervalHoursText)
  val maxDailyDosesCheck = checkOptionalPositiveDecimal(maxDailyDosesText)
  val maxDailyAmountCheck = checkOptionalPositiveDecimal(maxDailyAmountText)

  val invalidFieldLabels = buildList {
    if (!doseCheck.isValid) add(stringResource(R.string.editor_default_dose))
    if (form.isInjectable) {
      val pen = form.isPrefilledInjector
      if (!vialVolumeCheck.isValid) add(stringResource(if (pen) R.string.editor_device_volume else R.string.editor_vial_size))
      // Syringe and draw-needle fields are hidden for prefilled devices, so they can't block Save.
      if (!pen) {
        if (!syringeVolumeCheck.isValid) add(stringResource(R.string.med_syringe))
        if (!needleAttached && !drawGaugeCheck.isValid) add(stringResource(R.string.editor_draw_gauge))
        if (!needleAttached && !drawLengthCheck.isValid) add(stringResource(R.string.editor_draw_length))
      }
      if (!injectGaugeCheck.isValid) add(stringResource(if (pen) R.string.editor_needle_gauge else R.string.editor_inject_gauge))
      if (!injectLengthCheck.isValid) add(stringResource(if (pen) R.string.editor_needle_length else R.string.editor_inject_length))
    }
    if (form.hasSites && siteMode != SiteMode.OFF && sites.isEmpty()) add(stringResource(R.string.med_sites))
    if (!minIntervalCheck.isValid) add(stringResource(R.string.editor_min_interval))
    if (!maxDailyDosesCheck.isValid) add(stringResource(R.string.editor_max_daily_doses))
    if (!maxDailyAmountCheck.isValid) add(stringResource(R.string.editor_max_daily_amount))
  }

  fun saveMedication() {
    // Save is disabled unless every check is valid, so these are the user's exact values.
    val doseAmt = doseCheck.validOrNull() ?: return
    val minIntervalMins = minIntervalCheck.validOrNull()
      ?.let { (it * 60.0).roundToInt().coerceAtLeast(1) }
    val pen = form.isPrefilledInjector
    val injDetails = if (form.isInjectable) {
      InjectableDetails(
        route = route,
        vialVolumeMl = vialVolumeCheck.validOrNull(),
        // Hidden fields keep nothing: a pen has no syringe, an attached needle no draw needle.
        syringeType = syringeType.takeUnless { pen },
        syringeVolumeMl = syringeVolumeCheck.validOrNull().takeUnless { pen || syringeType == null },
        syringeDescription = syringeDesc.trim().takeIf { it.isNotEmpty() && !pen && syringeType == SyringeType.OTHER },
        drawNeedleGauge = drawGaugeCheck.validOrNull().takeUnless { pen || needleAttached },
        drawNeedleLengthInches = drawLengthCheck.validOrNull().takeUnless { pen || needleAttached },
        injectNeedleGauge = injectGaugeCheck.validOrNull(),
        injectNeedleLengthInches = injectLengthCheck.validOrNull(),
      )
    } else null

    val medId = initialMedication?.id ?: java.util.UUID.randomUUID().toString()
    val savedMed = Medication(
      id = medId,
      formulaName = formulaName.trim(),
      strength = strength.trim(),
      form = form,
      defaultDoseAmount = doseAmt,
      defaultDoseUnit = defaultDoseUnit,
      injectableDetails = injDetails,
      siteMode = if (form.hasSites) siteMode else SiteMode.OFF,
      sites = if (form.hasSites) sites.toList() else emptyList(),
      minIntervalMinutes = minIntervalMins,
      maxDailyDoses = maxDailyDosesCheck.validOrNull(),
      maxDailyAmount = maxDailyAmountCheck.validOrNull(),
      indicationIds = selectedIndicationIds,
      indicationText = indicationText.trim(),
      prescriberId = selectedPrescriberId,
      prescribedDateIso = prescribedDateIso.trim().takeIf { it.isNotBlank() },
      ndc11 = ndc11.trim().takeIf { it.isNotBlank() },
      brandName = brandName.trim().takeIf { it.isNotBlank() },
      manufacturer = manufacturer.trim().takeIf { it.isNotBlank() },
      notes = notes.trim().takeIf { it.isNotBlank() },
    )
    onSave(savedMed)
  }

  EditorScaffold(
    title = stringResource(if (initialMedication == null) R.string.medications_add else R.string.med_edit),
    onClose = onDismiss,
    onSave = { saveMedication() },
    saveEnabled = formulaName.isNotBlank() && strength.isNotBlank() && invalidFieldLabels.isEmpty(),
    banner = if (invalidFieldLabels.isNotEmpty()) {
      {
        Text(
          text = stringResource(R.string.editor_fix_to_save, invalidFieldLabels.joinToString(", ")),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
    } else {
      null
    },
  ) {
          // 1. Top Label OCR Scanner & OpenFDA Cross-Reference Bar + Core Medication Identity
          val activeFeedback = ndcErrorText ?: ndcStatusText
          val showActionHeader = isGeminiNanoSupported || allowFdaLookup || !activeFeedback.isNullOrBlank()
          if (showActionHeader) {
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                if (isGeminiNanoSupported || allowFdaLookup) {
                  FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    if (isGeminiNanoSupported) {
                      FilledTonalButton(
                        onClick = {
                          val uri = runCatching { DrugBarcodeScanner.createTempLabelPhotoUri(context) }.getOrNull()
                          if (uri != null) {
                            pendingLabelPhotoUri = uri
                            runCatching {
                              cameraLabelLauncher.launch(uri)
                            }.onFailure {
                              showLabelScanDialog = true
                            }
                          } else {
                            showLabelScanDialog = true
                          }
                        },
                        enabled = !isLookingUpNdc,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                      ) {
                        Icon(
                          Icons.Default.DocumentScanner,
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.editor_scan_label))
                      }
                      OutlinedButton(
                        onClick = { showLabelScanDialog = true },
                        enabled = !isLookingUpNdc,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                      ) {
                        Icon(
                          Icons.Default.PhotoLibrary,
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.editor_label_photo))
                      }
                    }
                    if (allowFdaLookup) {
                      OutlinedButton(
                        onClick = {
                          runCrossReferenceLookup(
                            DrugCrossReferenceCriteria(
                              ndc = ndc11.takeIf { it.isNotBlank() },
                              formulaName = formulaName.takeIf { it.isNotBlank() },
                              brandName = brandName.takeIf { it.isNotBlank() },
                              strength = strength.takeIf { it.isNotBlank() },
                              form = form,
                              manufacturer = manufacturer.takeIf { it.isNotBlank() },
                            )
                          )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                      ) {
                        if (isLookingUpNdc) {
                          CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                          )
                        } else {
                          Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                          )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.editor_match_fda))
                      }
                    }
                  }
                }

                if (isGeminiNanoSupported) {
                  Text(
                    text = stringResource(R.string.editor_scanning_help),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }

                if (!activeFeedback.isNullOrBlank()) {
                  Text(
                    text = activeFeedback,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ndcErrorText != null) {
                      MaterialTheme.colorScheme.error
                    } else {
                      MaterialTheme.colorScheme.primary
                    },
                  )
                }
              }
            }
          }

          OutlinedTextField(
            value = brandName,
            onValueChange = { brandName = it },
            label = { Text(stringResource(R.string.editor_brand_name)) },
            placeholder = { Text(stringResource(R.string.editor_brand_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          OutlinedTextField(
            value = formulaName,
            onValueChange = { formulaName = it },
            label = { Text(stringResource(R.string.editor_formula_name)) },
            placeholder = { Text(stringResource(R.string.editor_formula_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = strength,
              onValueChange = { strength = it },
              label = { Text(stringResource(R.string.editor_strength)) },
              placeholder = { Text(stringResource(R.string.editor_strength_hint)) },
              singleLine = true,
              modifier = Modifier.weight(0.45f),
            )
            DropdownSelectorField(
              label = stringResource(R.string.editor_form),
              selectedText = form.displayName,
              options = MedicationForm.entries,
              optionLabel = { it.displayName },
              onOptionSelected = { selectedForm ->
                form = selectedForm
                if (selectedForm.isInjectable && defaultDoseUnit == DoseUnit.TABLET) {
                  defaultDoseUnit = DoseUnit.MG
                } else if (selectedForm == MedicationForm.TOPICAL) {
                  defaultDoseUnit = DoseUnit.APPLICATION
                } else if (selectedForm == MedicationForm.CAPSULE && defaultDoseUnit == DoseUnit.TABLET) {
                  defaultDoseUnit = DoseUnit.CAPSULE
                }
              },
              modifier = Modifier.weight(0.55f),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            ValidatedNumberField(
              check = doseCheck,
              keyboardType = KeyboardType.Decimal,
              value = defaultDoseAmountText,
              onValueChange = { defaultDoseAmountText = it },
              label = { Text(stringResource(R.string.editor_default_dose)) },
              singleLine = true,
              modifier = Modifier.weight(0.45f),
            )
            DropdownSelectorField(
              label = stringResource(R.string.editor_unit),
              selectedText = "${defaultDoseUnit.symbol} (${defaultDoseUnit.displayName})",
              options = DoseUnit.entries,
              optionLabel = { "${it.symbol} — ${it.displayName}" },
              onOptionSelected = { defaultDoseUnit = it },
              modifier = Modifier.weight(0.55f),
            )
          }

          // Volumes come from the strength, so there's no separate concentration to keep in step.
          if (form == MedicationForm.INJECTABLE_VIAL || form == MedicationForm.LIQUID) {
            volumeHint(strength, doseCheck.validOrNull(), defaultDoseUnit)?.let { (text, isProblem) ->
              Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          // Indications (any number, grouped by kind + optional notes)
          if (showIndications && indications.isNotEmpty()) {
            Text(stringResource(R.string.indications_title), style = MaterialTheme.typography.titleSmall)
            IndicationTimeline.sections(indications).forEach { (title, group) ->
              Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                group.forEach { indication ->
                  val selected = indication.id in selectedIndicationIds
                  FilterChip(
                    selected = selected,
                    onClick = {
                      selectedIndicationIds =
                        if (selected) selectedIndicationIds - indication.id else selectedIndicationIds + indication.id
                    },
                    label = { Text(indication.name) },
                    leadingIcon = if (selected) {
                      { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                  )
                }
              }
            }
          }

          // 2. Injectable specifications (only when Form is Injectable). Prefilled devices
          // (pens, auto-injectors, prefilled syringes) have no vial to draw from, so the
          // syringe and draw-needle fields are hidden; their stored values are kept.
          if (form.isInjectable) {
            val pen = form.isPrefilledInjector
            val notSetLabel = stringResource(R.string.value_not_set)
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Text(
                  text = stringResource(if (pen) R.string.editor_pen_details else R.string.editor_vial_details),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )

                DropdownSelectorField(
                  label = stringResource(R.string.editor_route),
                  selectedText = route.displayName,
                  options = InjectionRoute.entries,
                  optionLabel = { it.displayName },
                  onOptionSelected = { route = it },
                  modifier = Modifier.fillMaxWidth(),
                )

                ValidatedNumberField(
                  check = vialVolumeCheck,
                  keyboardType = KeyboardType.Decimal,
                  value = vialVolumeText,
                  onValueChange = { vialVolumeText = it },
                  label = { Text(stringResource(if (pen) R.string.editor_device_volume_ml else R.string.editor_vial_size_ml)) },
                  placeholder = { Text(if (pen) "3" else "10") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth(),
                )

                // Syringe: a type, then one of its usual sizes. Only an unlisted type takes free text.
                if (!pen) {
                  DropdownSelectorField(
                    label = stringResource(R.string.med_syringe),
                    selectedText = syringeType?.displayName ?: stringResource(R.string.value_not_set),
                    options = listOf<SyringeType?>(null) + SyringeType.entries,
                    optionLabel = { it?.displayName ?: notSetLabel },
                    onOptionSelected = { type ->
                      syringeType = type
                      val sizes = type?.volumesMl.orEmpty()
                      val current = syringeVolumeCheck.validOrNull()
                      if (sizes.isNotEmpty() && current !in sizes) {
                        syringeVolumeText = if (sizes.size == 1) formatExact(sizes.single()) else ""
                      }
                    },
                    modifier = Modifier.fillMaxWidth(),
                  )
                  val sizes = syringeType?.volumesMl.orEmpty()
                  if (sizes.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      sizes.forEach { size ->
                        val selected = syringeVolumeCheck.validOrNull() == size
                        FilterChip(
                          selected = selected,
                          onClick = { syringeVolumeText = if (selected) "" else formatExact(size) },
                          label = { Text("${formatNum(size)} mL") },
                        )
                      }
                    }
                  } else if (syringeType != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                      ValidatedNumberField(
                        check = syringeVolumeCheck,
                        keyboardType = KeyboardType.Decimal,
                        value = syringeVolumeText,
                        onValueChange = { syringeVolumeText = it },
                        label = { Text(stringResource(R.string.editor_size_ml)) },
                        placeholder = { Text("1.0") },
                        singleLine = true,
                        modifier = Modifier.weight(0.4f),
                      )
                      OutlinedTextField(
                        value = syringeDesc,
                        onValueChange = { syringeDesc = it },
                        label = { Text(stringResource(R.string.editor_what_kind)) },
                        placeholder = { Text(stringResource(R.string.editor_what_kind_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f),
                      )
                    }
                  }
                }

                // Insulin syringes have the needle attached, so there's nothing separate to draw with.
                if (!pen && !needleAttached) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  ValidatedNumberField(
                    check = drawGaugeCheck,
                    keyboardType = KeyboardType.Number,
                    value = drawGaugeText,
                    onValueChange = { drawGaugeText = it },
                    label = { Text(stringResource(R.string.editor_draw_gauge_label)) },
                    placeholder = { Text("18") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  ValidatedNumberField(
                    check = drawLengthCheck,
                    keyboardType = KeyboardType.Decimal,
                    value = drawLengthText,
                    onValueChange = { drawLengthText = it },
                    label = { Text(stringResource(R.string.editor_draw_length_label)) },
                    placeholder = { Text("1.0") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                }

                if (!pen && !needleAttached) HubColorHint(drawGaugeCheck.validOrNull())

                if (pen) {
                  Text(
                    text = stringResource(R.string.editor_pen_needle_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                } else if (needleAttached) {
                  Text(
                    text = stringResource(R.string.editor_needle_attached_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  ValidatedNumberField(
                    check = injectGaugeCheck,
                    keyboardType = KeyboardType.Number,
                    value = injectGaugeText,
                    onValueChange = { injectGaugeText = it },
                    label = { Text(stringResource(if (pen || needleAttached) R.string.editor_needle_gauge_label else R.string.editor_inject_gauge_label)) },
                    placeholder = { Text(if (pen) "32" else "25") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  ValidatedNumberField(
                    check = injectLengthCheck,
                    keyboardType = KeyboardType.Decimal,
                    value = injectLengthText,
                    onValueChange = { injectLengthText = it },
                    label = { Text(stringResource(if (pen || needleAttached) R.string.editor_needle_length_label else R.string.editor_inject_length_label)) },
                    placeholder = { Text(if (pen) "0.16" else "1.0") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                }
                HubColorHint(injectGaugeCheck.validOrNull())
              }
            }
          }

          // 3. Sites (any form a dose goes somewhere on the body): off, record, or rotate.
          if (form.hasSites) {
            val rotating = siteMode == SiteMode.ROTATE
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Text(
                  text = stringResource(R.string.med_sites),
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                  SiteMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                      selected = siteMode == mode,
                      onClick = {
                        siteMode = mode
                        siteModeChosen = true
                      },
                      shape = SegmentedButtonDefaults.itemShape(index = i, count = SiteMode.entries.size),
                      icon = {},
                      label = { Text(mode.displayName, maxLines = 1) },
                    )
                  }
                }
                Text(
                  text = siteMode.description,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (siteMode != SiteMode.OFF) {
                  if (sites.isEmpty()) {
                    Text(
                      text = stringResource(R.string.editor_sites_required),
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.error,
                    )
                  }
                  DropdownSelectorField(
                    label = stringResource(R.string.editor_site_preset),
                    selectedText = if (sites.isEmpty()) {
                      stringResource(R.string.editor_no_sites)
                    } else {
                      pluralStringResource(R.plurals.editor_site_count, sites.size, sites.size)
                    },
                    options = Medication.sitePresets(form),
                    optionLabel = { it.first },
                    onOptionSelected = { (_, siteList) ->
                      sites.clear()
                      sites.addAll(siteList)
                      sitesChosen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                  )

                  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    sites.forEachIndexed { idx, site ->
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        // Order only matters when rotating.
                        Text(
                          text = if (rotating) stringResource(R.string.editor_site_numbered, idx + 1, site) else site,
                          style = MaterialTheme.typography.bodyMedium,
                          modifier = Modifier.weight(1f),
                        )
                        if (rotating) {
                          IconButton(
                            onClick = {
                              if (idx > 0) {
                                val item = sites.removeAt(idx)
                                sites.add(idx - 1, item)
                                sitesChosen = true
                              }
                            },
                            enabled = idx > 0,
                            modifier = Modifier.size(32.dp),
                          ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.editor_move_up), modifier = Modifier.size(16.dp))
                          }
                          IconButton(
                            onClick = {
                              if (idx < sites.lastIndex) {
                                val item = sites.removeAt(idx)
                                sites.add(idx + 1, item)
                                sitesChosen = true
                              }
                            },
                            enabled = idx < sites.lastIndex,
                            modifier = Modifier.size(32.dp),
                          ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.editor_move_down), modifier = Modifier.size(16.dp))
                          }
                        }
                        IconButton(
                          onClick = {
                            sites.removeAt(idx)
                            sitesChosen = true
                          },
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(Icons.Default.Close, contentDescription = stringResource(R.string.editor_remove_site), modifier = Modifier.size(16.dp))
                        }
                      }
                    }
                  }

                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    OutlinedTextField(
                      value = newCustomSite,
                      onValueChange = { newCustomSite = it },
                      label = { Text(stringResource(R.string.editor_add_custom_site)) },
                      singleLine = true,
                      modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                      onClick = {
                        if (newCustomSite.isNotBlank()) {
                          sites.add(newCustomSite.trim())
                          sitesChosen = true
                          newCustomSite = ""
                        }
                      },
                    ) {
                      Text(stringResource(R.string.action_add))
                    }
                  }
                }
              }
            }
          }

          // 4. Collapsible Pacing & Daily Limits Section
          val pacingSummary = listOfNotNull(
            minIntervalHoursText.takeIf { it.isNotBlank() }?.let { stringResource(R.string.editor_summary_interval, it) },
            maxDailyDosesText.takeIf { it.isNotBlank() }?.let { stringResource(R.string.editor_summary_max_doses, it) },
          ).joinToString(" • ").ifEmpty { stringResource(R.string.editor_pacing_summary_empty) }

          CollapsibleFormSection(
            title = stringResource(R.string.editor_pacing_title),
            subtitle = pacingSummary,
            initiallyExpanded = hasInitialPacing,
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              ValidatedNumberField(
                check = minIntervalCheck,
                keyboardType = KeyboardType.Decimal,
                value = minIntervalHoursText,
                onValueChange = { minIntervalHoursText = it },
                label = { Text(stringResource(R.string.editor_min_interval_hours)) },
                placeholder = { Text(stringResource(R.string.editor_min_interval_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
              ValidatedNumberField(
                check = maxDailyDosesCheck,
                keyboardType = KeyboardType.Decimal,
                value = maxDailyDosesText,
                onValueChange = { maxDailyDosesText = it },
                label = { Text(stringResource(R.string.editor_max_daily_doses)) },
                placeholder = { Text(stringResource(R.string.editor_max_daily_doses_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
            }
            ValidatedNumberField(
              check = maxDailyAmountCheck,
              keyboardType = KeyboardType.Decimal,
              value = maxDailyAmountText,
              onValueChange = { maxDailyAmountText = it },
              label = { Text(stringResource(R.string.editor_max_daily_amount_unit, defaultDoseUnit.symbol)) },
              placeholder = { Text(stringResource(R.string.editor_max_daily_amount_hint)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          // 5. Collapsible Optional Rx & Drug Details Section (NDC-11, Manufacturer, Provider, Notes)
          val optionalSummary = listOfNotNull(
            ndc11.takeIf { it.isNotBlank() }?.let { stringResource(R.string.editor_summary_ndc, it) },
            manufacturer.takeIf { it.isNotBlank() },
            providers.find { it.id == selectedPrescriberId }?.name?.takeIf { showProviders },
          ).joinToString(" • ").ifEmpty {
            stringResource(if (showProviders) R.string.editor_optional_summary_providers else R.string.editor_optional_summary)
          }

          CollapsibleFormSection(
            title = stringResource(R.string.editor_optional_title),
            subtitle = optionalSummary,
            initiallyExpanded = hasInitialOptionalDetails,
          ) {
            OutlinedTextField(
              value = ndc11,
              onValueChange = {
                ndc11 = NumberInput.filterNdc(it)
                ndcErrorText = null
                ndcStatusText = null
                ndcMatched = false
              },
              label = { Text(stringResource(R.string.adjust_ndc_label)) },
              placeholder = { Text(stringResource(R.string.editor_ndc_hint)) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              trailingIcon = {
                if (allowFdaLookup && ndc11.isNotBlank()) {
                  TextButton(
                    onClick = { runNdcOrBarcodeLookup(ndc11) }
                  ) {
                    if (ndcMatched) {
                      Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(stringResource(R.string.editor_lookup))
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = manufacturer,
              onValueChange = { manufacturer = it },
              label = { Text(stringResource(R.string.med_manufacturer)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )

            if (showProviders) {
              val selectedDoc = providers.find { it.id == selectedPrescriberId }
              val noneSelected = stringResource(R.string.editor_none_selected)
              DropdownSelectorField(
                label = stringResource(R.string.med_prescriber),
                selectedText = selectedDoc?.let { if (it.isPcp) resources.getString(R.string.med_name_pcp, it.name) else it.name } ?: noneSelected,
                options = listOf<Provider?>(null) + providers,
                optionLabel = { doc ->
                  doc?.let { if (it.isPcp) resources.getString(R.string.med_name_pcp, it.name) else it.name } ?: noneSelected
                },
                onOptionSelected = { selectedPrescriberId = it?.id },
                modifier = Modifier.fillMaxWidth(),
              )
            }

            DatePickerField(
              label = stringResource(R.string.editor_prescribed_date),
              valueIso = prescribedDateIso,
              onValueChange = { prescribedDateIso = it },
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = indicationText,
              onValueChange = { indicationText = it },
              label = { Text(stringResource(R.string.editor_indication_notes)) },
              placeholder = { Text(stringResource(R.string.editor_indication_notes_hint)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = notes,
              onValueChange = { notes = it },
              label = { Text(stringResource(R.string.editor_instructions)) },
              modifier = Modifier.fillMaxWidth(),
            )
          }
  }

  // Shown after each camera shot so the bottle can be turned and photographed again.
  if (showCapturedPhotosDialog && capturedLabelPhotos.isNotEmpty()) {
    AlertDialog(
      onDismissRequest = {},
      title = {
        Text(pluralStringResource(R.plurals.editor_photos_taken, capturedLabelPhotos.size, capturedLabelPhotos.size))
      },
      text = {
        Text(stringResource(R.string.editor_photos_help))
      },
      confirmButton = {
        Button(
          onClick = {
            val photos = capturedLabelPhotos.toList()
            capturedLabelPhotos.clear()
            showCapturedPhotosDialog = false
            runLabelPhotoAnalysis(photos)
          },
        ) {
          Text(stringResource(R.string.editor_read_label))
        }
      },
      dismissButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(
            onClick = {
              capturedLabelPhotos.clear()
              DrugBarcodeScanner.clearLabelPhotos(context)
              showCapturedPhotosDialog = false
            },
          ) {
            Text(stringResource(R.string.action_discard))
          }
          TextButton(
            onClick = {
              showCapturedPhotosDialog = false
              captureLabelPhoto()
            },
          ) {
            Text(stringResource(R.string.editor_add_another))
          }
        }
      },
    )
  }

  // Label OCR Photo / Paste Dialog (for pharmacy vials, compounded bottles, and OTC labels)
  if (isGeminiNanoSupported && showLabelScanDialog) {
    AlertDialog(
      onDismissRequest = { showLabelScanDialog = false },
      title = { Text(stringResource(R.string.editor_scan_title)) },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = stringResource(R.string.editor_scan_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilledTonalButton(
              onClick = {
                showLabelScanDialog = false
                val uri = runCatching { DrugBarcodeScanner.createTempLabelPhotoUri(context) }.getOrNull()
                if (uri != null) {
                  pendingLabelPhotoUri = uri
                  cameraLabelLauncher.launch(uri)
                }
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.editor_camera))
            }
            OutlinedButton(
              onClick = {
                showLabelScanDialog = false
                galleryLabelLauncher.launch("image/*")
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.editor_photos))
            }
          }
          HorizontalDivider()
          OutlinedTextField(
            value = manualLabelInput,
            onValueChange = { manualLabelInput = it },
            label = { Text(stringResource(R.string.editor_paste_label)) },
            placeholder = {
              Text(stringResource(R.string.editor_paste_hint))
            },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val raw = manualLabelInput.trim()
            showLabelScanDialog = false
            if (raw.isNotEmpty()) {
              runGeminiLabelTextAnalysis(raw)
            }
          },
          enabled = manualLabelInput.isNotBlank(),
        ) {
          Text(stringResource(R.string.editor_parse))
        }
      },
      dismissButton = {
        TextButton(onClick = { showLabelScanDialog = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  // Disambiguation Dialog when multiple OpenFDA packages/strengths/manufacturers match
  disambiguationPrompt?.let { prompt ->
    CrossReferenceDisambiguationDialog(
      reason = prompt.reason,
      candidates = prompt.candidates,
      onSelectCandidate = { chosen ->
        disambiguationPrompt = null
        val signals = chosen.matchedSignals.joinToString(", ")
        applyFdaResultToForm(
          fda = chosen.result,
          statusSummary = resources.getString(R.string.editor_selected_match, chosen.result.ndc11, signals),
        )
      },
      onKeepWithoutNdc = {
        disambiguationPrompt = null
        ndcStatusText = resources.getString(R.string.editor_kept_fields)
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrossReferenceDisambiguationDialog(
  reason: String,
  candidates: List<CrossReferencedFdaCandidate>,
  onSelectCandidate: (CrossReferencedFdaCandidate) -> Unit,
  onKeepWithoutNdc: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onKeepWithoutNdc,
    title = { Text(stringResource(R.string.editor_confirm_match)) },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = reason,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidates.forEach { cand ->
          val res = cand.result
          Card(
            onClick = { onSelectCandidate(cand) },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              // Stacked rather than side by side: multi-ingredient products (e.g. Adderall's four
              // salts) have long names *and* long strengths, and a shared row squeezes one to a sliver.
              val brand = res.brandName?.takeIf { it.isNotBlank() }
              Text(
                text = brand ?: res.formulaName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
              )
              if (brand != null) {
                Text(
                  text = res.formulaName,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Text(
                text = res.strength,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = stringResource(R.string.editor_candidate_line, res.form.displayName, res.manufacturer ?: stringResource(R.string.editor_unspecified), res.ndc11),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (cand.matchedSignals.isNotEmpty()) {
                Text(
                  text = stringResource(R.string.editor_matched, cand.matchedSignals.joinToString(" • ")),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
              if (cand.missingOrDifferentSignals.isNotEmpty()) {
                Text(
                  text = stringResource(R.string.editor_note, cand.missingOrDifferentSignals.joinToString(" • ")),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onKeepWithoutNdc) {
        Text(stringResource(R.string.editor_keep_fields))
      }
    },
  )
}

/**
 * Interactive month calendar and date-range selector for picking specific dates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpecificDatesPicker(
  selectedDates: MutableList<String>,
  modifier: Modifier = Modifier,
) {
  var displayedYearMonth by remember { mutableStateOf(YearMonth.now()) }
  var showRangePicker by rememberSaveable { mutableStateOf(false) }

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Header with navigation and action
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { displayedYearMonth = displayedYearMonth.minusMonths(1) }) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_previous_month))
        }
        val monthTitle = remember(displayedYearMonth) {
          displayedYearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        }
        Text(
          text = monthTitle,
          style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(onClick = { displayedYearMonth = displayedYearMonth.plusMonths(1) }) {
          Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.editor_next_month))
        }
      }

      TextButton(onClick = { showRangePicker = true }) {
        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.action_add_range))
      }
    }

    // Weekday headers, Sunday first, in the device language.
    val daysOfWeekLabels = remember {
      (0L..6L).map { DayOfWeek.SUNDAY.plus(it).getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
      daysOfWeekLabels.forEach { label ->
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.weight(1f),
        )
      }
    }

    // Month calendar grid
    val firstDayOfWeek = displayedYearMonth.atDay(1).dayOfWeek.value % 7 // 0 for Sunday
    val daysInMonth = displayedYearMonth.lengthOfMonth()
    val todayIso = remember { LocalDate.now().toString() }

    val totalCells = firstDayOfWeek + daysInMonth
    val numRows = (totalCells + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      for (row in 0 until numRows) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
          for (col in 0..6) {
            val cellIndex = row * 7 + col
            val dayNum = cellIndex - firstDayOfWeek + 1
            if (dayNum in 1..daysInMonth) {
              val date = displayedYearMonth.atDay(dayNum)
              val dateIso = date.toString()
              val isSelected = selectedDates.contains(dateIso)
              val isToday = dateIso == todayIso

              Box(
                modifier = Modifier
                  .weight(1f)
                  .aspectRatio(1f)
                  .padding(2.dp)
                  .clip(CircleShape)
                  .background(
                    when {
                      isSelected -> MaterialTheme.colorScheme.primary
                      isToday -> MaterialTheme.colorScheme.surfaceVariant
                      else -> Color.Transparent
                    }
                  )
                  .clickable {
                    if (isSelected) {
                      selectedDates.remove(dateIso)
                    } else {
                      selectedDates.add(dateIso)
                    }
                  },
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = dayNum.toString(),
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                  color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                  },
                )
              }
            } else {
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      }
    }

    // Selected count and clear button
    val sortedDates = remember(selectedDates.size, selectedDates.hashCode()) {
      selectedDates.sorted()
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (sortedDates.isEmpty()) {
          stringResource(R.string.editor_no_dates)
        } else {
          pluralStringResource(R.plurals.editor_dates_selected, sortedDates.size, sortedDates.size)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (sortedDates.isNotEmpty()) {
        TextButton(onClick = { selectedDates.clear() }) {
          Text(stringResource(R.string.editor_clear_all))
        }
      }
    }

    if (sortedDates.isNotEmpty()) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        val displayLimit = 14
        sortedDates.take(displayLimit).forEach { iso ->
          InputChip(
            selected = true,
            onClick = { selectedDates.remove(iso) },
            label = { Text(UiFormat.dateIso(iso, withWeekday = false) ?: iso) },
            trailingIcon = {
              Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove), modifier = Modifier.size(16.dp))
            },
          )
        }
        if (sortedDates.size > displayLimit) {
          Text(
            text = pluralStringResource(R.plurals.editor_more_dates, sortedDates.size - displayLimit, sortedDates.size - displayLimit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp),
          )
        }
      }
    }
  }

  if (showRangePicker) {
    DateRangePickerDialog(
      onDismissRequest = { showRangePicker = false },
      onRangeSelected = { start, end ->
        var curr = start
        while (!curr.isAfter(end)) {
          val iso = curr.toString()
          if (!selectedDates.contains(iso)) {
            selectedDates.add(iso)
          }
          curr = curr.plusDays(1)
        }
      },
    )
  }
}

/** A list of selections that survives rotation and process death (items must go in a Bundle). */
@Composable
private fun <T : Any> rememberSavableList(initial: () -> List<T>): SnapshotStateList<T> =
  rememberSaveable(
    saver = listSaver<SnapshotStateList<T>, T>(save = { it.toList() }, restore = { it.toMutableStateList() }),
  ) { initial().toMutableStateList() }

/**
 * Full-screen schedule editor (frequency, dose, windows, interval/start date, weekdays).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorScreen(
  medication: Medication,
  initialSchedule: MedicationSchedule?,
  buckets: List<TimeOfDayBucket>,
  onDismiss: () -> Unit,
  onSave: (MedicationSchedule) -> Unit,
) {
  var frequencyType by rememberSaveable {
    mutableStateOf(initialSchedule?.frequencyType ?: ScheduleFrequencyType.DAILY)
  }
  val orderedBuckets = remember(buckets) { buckets.chronological() }
  val selectedBucketIds = rememberSavableList {
    initialSchedule?.bucketIds ?: listOfNotNull(orderedBuckets.firstOrNull()?.id)
  }
  var doseAmountText by rememberSaveable {
    mutableStateOf(formatExact(initialSchedule?.doseAmount ?: medication.defaultDoseAmount))
  }
  var doseUnit by rememberSaveable { mutableStateOf(initialSchedule?.doseUnit ?: medication.defaultDoseUnit) }
  var intervalDaysText by rememberSaveable { mutableStateOf((initialSchedule?.intervalDays ?: 7).toString()) }
  // One start date serves as both the range start and an interval schedule's cycle anchor.
  var startDateIso by rememberSaveable {
    mutableStateOf(
      initialSchedule?.startDateIso
        ?: initialSchedule?.takeIf {
          it.frequencyType == ScheduleFrequencyType.EVERY_N_DAYS ||
            it.frequencyType == ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE
        }?.anchorDateIso
        ?: LocalDate.now().toString()
    )
  }
  var endDateIso by rememberSaveable { mutableStateOf(initialSchedule?.endDateIso.orEmpty()) }
  // Partial first/last day, e.g. a twice-daily course started with the evening dose.
  var startBucketId by rememberSaveable { mutableStateOf(initialSchedule?.startBucketId) }
  var endBucketId by rememberSaveable { mutableStateOf(initialSchedule?.endBucketId) }
  val selectedDaysOfWeek = rememberSavableList { (initialSchedule?.daysOfWeek ?: setOf(1, 2, 3, 4, 5, 6, 7)).toList() }
  val selectedSpecificDates = rememberSavableList { initialSchedule?.specificDatesIso?.sorted().orEmpty() }

  // Strict validation: invalid input blocks Save rather than silently becoming a default.
  // (An unparseable start date used to make an every-N-days schedule due every day.)
  val usesInterval = frequencyType == ScheduleFrequencyType.EVERY_N_DAYS ||
    frequencyType == ScheduleFrequencyType.RELATIVE_TO_LAST_DOSE
  val validBucketIds = selectedBucketIds.filter { id -> buckets.any { it.id == id } }
  val doseCheck = checkRequiredPositiveDecimal(doseAmountText)
  val intervalCheck = checkRequiredPositiveInt(intervalDaysText)
  val usesDateRange = frequencyType != ScheduleFrequencyType.SPECIFIC_DATES &&
    frequencyType != ScheduleFrequencyType.AS_NEEDED
  val startDate = NumberInput.parseIsoDate(startDateIso)
  val endDate = NumberInput.parseIsoDate(endDateIso)
  val startError = if (startDate == null) stringResource(R.string.schedule_pick_start) else null
  val endError = if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
    stringResource(R.string.schedule_end_before_start)
  } else {
    null
  }
  // Selected windows in time order; boundary windows only matter when there's more than one.
  val chosenWindows = orderedBuckets.filter { it.id in selectedBucketIds }
  val firstWindow = chosenWindows.find { it.id == startBucketId } ?: chosenWindows.firstOrNull()
  val lastWindow = chosenWindows.find { it.id == endBucketId } ?: chosenWindows.lastOrNull()
  val showBoundaryWindows = usesDateRange && chosenWindows.size > 1
  val boundaryError = if (
    showBoundaryWindows && startDate != null && startDate == endDate &&
    firstWindow != null && lastWindow != null &&
    chosenWindows.indexOf(lastWindow) < chosenWindows.indexOf(firstWindow)
  ) {
    stringResource(R.string.schedule_last_before_first)
  } else {
    null
  }
  val bucketError = if (frequencyType != ScheduleFrequencyType.AS_NEEDED && validBucketIds.isEmpty()) {
    stringResource(R.string.schedule_select_window)
  } else {
    null
  }
  val daysError = if (frequencyType == ScheduleFrequencyType.DAYS_OF_WEEK && selectedDaysOfWeek.isEmpty()) {
    stringResource(R.string.schedule_select_day)
  } else {
    null
  }
  val specificDatesError = if (frequencyType == ScheduleFrequencyType.SPECIFIC_DATES && selectedSpecificDates.isEmpty()) {
    stringResource(R.string.schedule_select_date)
  } else {
    null
  }
  val canSave = doseCheck.isValid &&
    (!usesInterval || intervalCheck.isValid) &&
    (!usesDateRange || (startError == null && endError == null && boundaryError == null)) &&
    bucketError == null &&
    daysError == null &&
    specificDatesError == null

  fun save() {
    // Save is disabled unless every check is valid, so these are the user's exact values.
    val doseAmt = doseCheck.validOrNull() ?: return
    onSave(
      MedicationSchedule(
        id = initialSchedule?.id ?: java.util.UUID.randomUUID().toString(),
        medicationId = medication.id,
        frequencyType = frequencyType,
        bucketIds = if (frequencyType == ScheduleFrequencyType.AS_NEEDED) emptyList() else validBucketIds,
        // Interval/anchor only matter for interval schedules; keep prior values otherwise.
        intervalDays = intervalCheck.validOrNull() ?: initialSchedule?.intervalDays ?: 7,
        anchorDateIso = startDate?.toString() ?: initialSchedule?.anchorDateIso ?: LocalDate.now().toString(),
        daysOfWeek = selectedDaysOfWeek.toSet().ifEmpty { setOf(1, 2, 3, 4, 5, 6, 7) },
        specificDatesIso = if (frequencyType == ScheduleFrequencyType.SPECIFIC_DATES) selectedSpecificDates.toSet() else emptySet(),
        doseAmount = doseAmt,
        doseUnit = doseUnit,
        enabled = initialSchedule?.enabled ?: true,
        startDateIso = if (usesDateRange) startDate?.toString() else null,
        endDateIso = if (usesDateRange) endDate?.toString() else null,
        // Stored only when it actually trims the day (null = every selected window).
        startBucketId = firstWindow?.id
          ?.takeIf { showBoundaryWindows && it != chosenWindows.first().id },
        endBucketId = lastWindow?.id
          ?.takeIf { showBoundaryWindows && endDate != null && it != chosenWindows.last().id },
        createdAtEpochMillis = initialSchedule?.createdAtEpochMillis ?: System.currentTimeMillis(),
      )
    )
  }

  val resources = LocalResources.current
  fun windowStartLabel(b: TimeOfDayBucket) =
    resources.getString(R.string.window_name_time, b.name, b.formattedStartTime())

  EditorScaffold(
    title = stringResource(if (initialSchedule == null) R.string.med_add_schedule else R.string.schedule_edit),
    onClose = onDismiss,
    onSave = { save() },
    saveEnabled = canSave,
  ) {
    Text(
      text = medication.primaryName,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    DropdownSelectorField(
      label = stringResource(R.string.schedule_how_often),
      selectedText = frequencyType.displayName,
      options = ScheduleFrequencyType.entries,
      optionLabel = { it.displayName },
      onOptionSelected = { frequencyType = it },
      modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ValidatedNumberField(
        check = doseCheck,
        value = doseAmountText,
        onValueChange = { doseAmountText = it },
        label = { Text(stringResource(R.string.schedule_dose)) },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      DropdownSelectorField(
        label = stringResource(R.string.editor_unit),
        selectedText = doseUnit.symbol,
        options = DoseUnit.entries,
        optionLabel = { "${it.symbol} — ${it.displayName}" },
        onOptionSelected = { doseUnit = it },
        modifier = Modifier.weight(1f),
      )
    }

    if (medication.maxDailyAmount != null && doseUnit != medication.defaultDoseUnit) {
      Text(
        stringResource(R.string.schedule_unit_mismatch, medication.defaultDoseUnit.symbol, doseUnit.symbol),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    if (usesInterval) {
      ValidatedNumberField(
        check = intervalCheck,
        keyboardType = KeyboardType.Number,
        value = intervalDaysText,
        onValueChange = { intervalDaysText = it },
        label = { Text(stringResource(R.string.schedule_every_days)) },
        placeholder = { Text("7") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    if (usesDateRange) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DatePickerField(
          label = stringResource(R.string.schedule_start_date),
          valueIso = startDateIso,
          onValueChange = { startDateIso = it },
          allowClear = false,
          supportingText = startError ?: if (usesInterval) stringResource(R.string.schedule_first_dose_cycle) else null,
          modifier = Modifier.weight(1f),
        )
        DatePickerField(
          label = stringResource(R.string.indication_end_date),
          valueIso = endDateIso,
          onValueChange = { endDateIso = it },
          supportingText = endError ?: stringResource(if (endDateIso.isBlank()) R.string.value_ongoing else R.string.schedule_last_day_inclusive),
          modifier = Modifier.weight(1f),
        )
      }
    }

    if (frequencyType == ScheduleFrequencyType.DAYS_OF_WEEK) {
      Text(stringResource(R.string.schedule_days), style = MaterialTheme.typography.titleSmall)
      val locale = LocalConfiguration.current.locales[0]
      val dayNames = (1..7).map { it to DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        dayNames.forEach { (dayNum, label) ->
          val isSelected = selectedDaysOfWeek.contains(dayNum)
          FilterChip(
            selected = isSelected,
            onClick = { if (isSelected) selectedDaysOfWeek.remove(dayNum) else selectedDaysOfWeek.add(dayNum) },
            label = { Text(label) },
          )
        }
      }
      daysError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    if (frequencyType == ScheduleFrequencyType.SPECIFIC_DATES) {
      Text(stringResource(R.string.schedule_dates), style = MaterialTheme.typography.titleSmall)
      SpecificDatesPicker(
        selectedDates = selectedSpecificDates,
        modifier = Modifier.fillMaxWidth(),
      )
      specificDatesError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    if (frequencyType != ScheduleFrequencyType.AS_NEEDED) {
      Text(stringResource(R.string.settings_windows), style = MaterialTheme.typography.titleSmall)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        orderedBuckets.forEach { b ->
          val isSelected = selectedBucketIds.contains(b.id)
          FilterChip(
            selected = isSelected,
            onClick = { if (isSelected) selectedBucketIds.remove(b.id) else selectedBucketIds.add(b.id) },
            label = { Text(stringResource(R.string.window_name_time, b.name, b.formattedTime())) },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        bucketError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
      }
    }

    if (showBoundaryWindows && firstWindow != null && lastWindow != null) {
      Text(stringResource(R.string.schedule_first_last_day), style = MaterialTheme.typography.titleSmall)
      Text(
        stringResource(R.string.schedule_first_last_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DropdownSelectorField(
          label = stringResource(R.string.schedule_first_dose),
          selectedText = firstWindow.name,
          options = chosenWindows,
          optionLabel = { windowStartLabel(it) },
          onOptionSelected = { startBucketId = it.id },
          modifier = Modifier.weight(1f),
        )
        if (endDate != null) {
          DropdownSelectorField(
            label = stringResource(R.string.schedule_last_dose),
            selectedText = lastWindow.name,
            options = chosenWindows,
            optionLabel = { windowStartLabel(it) },
            onOptionSelected = { endBucketId = it.id },
            modifier = Modifier.weight(1f),
          )
        }
      }
      boundaryError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
  }
}
