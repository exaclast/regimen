package com.exaclast.regimen.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.exaclast.regimen.data.CrossReferenceOutcome
import com.exaclast.regimen.data.CrossReferencedFdaCandidate
import com.exaclast.regimen.data.DrugBarcodeScanner
import com.exaclast.regimen.data.DrugCrossReferenceCriteria
import com.exaclast.regimen.data.FdaDrugLookupResult
import com.exaclast.regimen.data.FdaNdcLookupService
import com.exaclast.regimen.data.ParsedMedicationLabel
import com.exaclast.regimen.model.Condition
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectableDetails
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.MedicationSchedule
import com.exaclast.regimen.model.Prescriber
import com.exaclast.regimen.model.ScheduleFrequencyType
import com.exaclast.regimen.model.TimeOfDayBucket
import com.exaclast.regimen.model.chronological
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
          contentDescription = if (expanded) "Collapse" else "Expand",
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
  conditions: List<Condition>,
  prescribers: List<Prescriber>,
  // Optional tracking: hidden fields keep any existing link untouched.
  showConditions: Boolean = true,
  showPrescribers: Boolean = true,
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
    mutableStateOf(initialMedication?.defaultDoseAmount?.let { formatNum(it) } ?: "1")
  }
  var defaultDoseUnit by rememberSaveable {
    mutableStateOf(initialMedication?.defaultDoseUnit ?: DoseUnit.TABLET)
  }

  // Injectable fields
  val initInj = initialMedication?.injectableDetails
  var route by rememberSaveable { mutableStateOf(initInj?.route ?: InjectionRoute.IM) }
  var concentrationText by rememberSaveable {
    mutableStateOf(initInj?.concentrationMgPerMl?.let { formatNum(it) }.orEmpty())
  }
  var vialVolumeText by rememberSaveable {
    mutableStateOf(initInj?.vialVolumeMl?.let { formatNum(it) }.orEmpty())
  }
  var syringeVolumeText by rememberSaveable {
    mutableStateOf(initInj?.syringeVolumeMl?.let { formatNum(it) }.orEmpty())
  }
  var syringeDesc by rememberSaveable { mutableStateOf(initInj?.syringeDescription.orEmpty()) }
  var drawGaugeText by rememberSaveable { mutableStateOf(initInj?.drawNeedleGauge?.toString().orEmpty()) }
  var drawLengthText by rememberSaveable {
    mutableStateOf(initInj?.drawNeedleLengthInches?.let { formatNum(it) }.orEmpty())
  }
  var injectGaugeText by rememberSaveable { mutableStateOf(initInj?.injectNeedleGauge?.toString().orEmpty()) }
  var injectLengthText by rememberSaveable {
    mutableStateOf(initInj?.injectNeedleLengthInches?.let { formatNum(it) }.orEmpty())
  }

  // Site Rotation
  var siteRotationEnabled by rememberSaveable {
    mutableStateOf(initialMedication?.siteRotationEnabled ?: false)
  }
  val rotationSites = remember {
    mutableStateListOf<String>().apply {
      addAll(initialMedication?.rotationSites ?: Medication.PRESET_IM_GLUTE_SITES)
    }
  }
  var newCustomSite by rememberSaveable { mutableStateOf("") }

  // Pacing & Safety Limits (on Medication object)
  var minIntervalHoursText by rememberSaveable {
    mutableStateOf(
      initialMedication?.minIntervalMinutes?.let { formatNum(it / 60.0) }.orEmpty()
    )
  }
  var maxDailyDosesText by rememberSaveable {
    mutableStateOf(initialMedication?.maxDailyDoses?.let { formatNum(it) }.orEmpty())
  }
  var maxDailyAmountText by rememberSaveable {
    mutableStateOf(initialMedication?.maxDailyAmount?.let { formatNum(it) }.orEmpty())
  }

  // Indication & Prescriber
  var selectedConditionId by rememberSaveable { mutableStateOf(initialMedication?.indicationConditionId) }
  var indicationText by rememberSaveable { mutableStateOf(initialMedication?.indicationText.orEmpty()) }
  var selectedPrescriberId by rememberSaveable { mutableStateOf(initialMedication?.prescriberId) }
  var prescribedDateIso by rememberSaveable { mutableStateOf(initialMedication?.prescribedDateIso.orEmpty()) }
  var notes by rememberSaveable { mutableStateOf(initialMedication?.notes.orEmpty()) }

  // Gemini Nano (AICore) Label Scanner & Cross-Reference lookup state
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var isGeminiNanoSupported by remember { mutableStateOf(false) }
  var isLookingUpNdc by remember { mutableStateOf(false) }
  var ndcErrorText by remember { mutableStateOf<String?>(null) }
  var ndcStatusText by remember { mutableStateOf<String?>(null) }
  var ndcMatched by remember { mutableStateOf(false) }
  var showLabelScanDialog by remember { mutableStateOf(false) }
  var manualLabelInput by remember { mutableStateOf("") }
  var pendingLabelPhotoUri by remember { mutableStateOf<Uri?>(null) }
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
    fda.concentrationMgPerMl?.let { concentrationText = formatNum(it) }
    fda.vialVolumeMl?.let { vialVolumeText = formatNum(it) }
    if (fda.form.supportsSiteRotation) {
      siteRotationEnabled = true
    }
    val metaParts = mutableListOf<String>()
    fda.lotNumber?.let { metaParts.add("Lot: $it") }
    fda.expirationDateIso?.let { metaParts.add("Exp: $it") }
    if (metaParts.isNotEmpty()) {
      val metaLine = metaParts.joinToString(" • ")
      ndcStatusText = statusSummary ?: "Autofilled (${fda.ndc11} • $metaLine)"
      if (!notes.contains(metaLine)) {
        notes = if (notes.isBlank()) metaLine else "$notes\n$metaLine"
      }
    } else {
      ndcStatusText = statusSummary ?: "Autofilled from FDA NDC (${fda.ndc11})"
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
            statusSummary = "Verified via OpenFDA (${cand.result.ndc11} • Matched: $signals)",
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
      ndcErrorText = "Could not detect medication name or NDC on the label. Try moving closer or check lighting."
      return
    }
    ndcErrorText = null
    parsedLabel.extractedNdc?.let { ndc11 = FdaNdcLookupService.toCanonicalNdc11(it) }
    parsedLabel.brandName?.let { brandName = it }
    parsedLabel.formulaName?.let { formulaName = it }
    parsedLabel.strength?.let { strength = it }
    parsedLabel.form?.let { detectedForm ->
      form = detectedForm
      if (detectedForm.supportsSiteRotation) {
        siteRotationEnabled = true
      }
    }
    parsedLabel.defaultDoseUnit?.let { defaultDoseUnit = it }
    parsedLabel.defaultDoseAmount?.let { defaultDoseAmountText = formatNum(it) }
    parsedLabel.manufacturer?.let { manufacturer = it }

    // Auto-link prescriber if name matches an existing prescriber in snapshot
    parsedLabel.prescriberName?.let { scannedDoc ->
      val docTokens = scannedDoc.lowercase().split(" ", ",").filter { it.length >= 3 }
      val matchedPrescriber = prescribers.firstOrNull { p ->
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
      parsedLabel.lotNumber?.let { "Lot: $it" },
      parsedLabel.expirationDateIso?.let { "Exp: $it" },
      parsedLabel.prescriberName?.takeIf { selectedPrescriberId == null }?.let { "Prescriber: $it" },
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
      ndcStatusText = "Parsed compounded medication label (no FDA NDC applicable)"
    } else if (allowFdaLookup) {
      ndcStatusText = "Parsed label text — cross-referencing OpenFDA..."
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
      ndcStatusText = "Parsed label text on-device"
    }
  }

  fun runGeminiLabelImageAnalysis(imageUri: Uri) {
    isLookingUpNdc = true
    ndcErrorText = null
    ndcStatusText = "Analyzing label with on-device Gemini Nano..."
    coroutineScope.launch {
      val res = DrugBarcodeScanner.analyzeLabelFromImageUri(
        context = context,
        imageUri = imageUri,
        userConfiguredPatientName = configuredPatientName.takeIf { it.isNotBlank() },
        onStatusUpdate = { status -> ndcStatusText = status },
      )
      isLookingUpNdc = false
      res.fold(
        onSuccess = { parsed -> applyParsedMedicationLabel(parsed) },
        onFailure = { err -> ndcErrorText = err.localizedMessage ?: "Failed to analyze label." },
      )
    }
  }

  fun runGeminiLabelTextAnalysis(rawText: String) {
    isLookingUpNdc = true
    ndcErrorText = null
    ndcStatusText = "Analyzing label text with on-device Gemini Nano..."
    coroutineScope.launch {
      val res = DrugBarcodeScanner.analyzeLabelFromText(
        rawLabelText = rawText,
        userConfiguredPatientName = configuredPatientName.takeIf { it.isNotBlank() },
        onStatusUpdate = { status -> ndcStatusText = status },
      )
      isLookingUpNdc = false
      res.fold(
        onSuccess = { parsed -> applyParsedMedicationLabel(parsed) },
        onFailure = { err -> ndcErrorText = err.localizedMessage ?: "Failed to analyze label text." },
      )
    }
  }

  val cameraLabelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
  ) { success ->
    val uri = pendingLabelPhotoUri
    if (success && uri != null) {
      runGeminiLabelImageAnalysis(uri)
    }
  }

  val galleryLabelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) {
      runGeminiLabelImageAnalysis(uri)
    }
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
          ndcErrorText = err.message ?: "NDC not found"
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
  val concentrationCheck = checkOptionalPositiveDecimal(concentrationText)
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
    if (!doseCheck.isValid) add("Default Dose")
    if (form.isInjectable) {
      if (!concentrationCheck.isValid) add("Concentration")
      if (!vialVolumeCheck.isValid) add("Vial Size")
      if (!syringeVolumeCheck.isValid) add("Syringe")
      if (!drawGaugeCheck.isValid) add("Draw Gauge")
      if (!drawLengthCheck.isValid) add("Draw Length")
      if (!injectGaugeCheck.isValid) add("Inject Gauge")
      if (!injectLengthCheck.isValid) add("Inject Length")
    }
    if (!minIntervalCheck.isValid) add("Min Interval")
    if (!maxDailyDosesCheck.isValid) add("Max Daily Doses")
    if (!maxDailyAmountCheck.isValid) add("Max Daily Amount")
  }

  fun saveMedication() {
    // Save is disabled unless every check is valid, so these are the user's exact values.
    val doseAmt = doseCheck.validOrNull() ?: return
    val minIntervalMins = minIntervalCheck.validOrNull()
      ?.let { (it * 60.0).roundToInt().coerceAtLeast(1) }
    val injDetails = if (form.isInjectable) {
      InjectableDetails(
        route = route,
        concentrationMgPerMl = concentrationCheck.validOrNull(),
        vialVolumeMl = vialVolumeCheck.validOrNull(),
        syringeVolumeMl = syringeVolumeCheck.validOrNull(),
        syringeDescription = syringeDesc.takeIf { it.isNotBlank() },
        drawNeedleGauge = drawGaugeCheck.validOrNull(),
        drawNeedleLengthInches = drawLengthCheck.validOrNull(),
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
      siteRotationEnabled = form.supportsSiteRotation && siteRotationEnabled,
      rotationSites = if (form.supportsSiteRotation && siteRotationEnabled) {
        rotationSites.toList()
      } else emptyList(),
      minIntervalMinutes = minIntervalMins,
      maxDailyDoses = maxDailyDosesCheck.validOrNull(),
      maxDailyAmount = maxDailyAmountCheck.validOrNull(),
      indicationConditionId = selectedConditionId,
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
    title = if (initialMedication == null) "Add medication" else "Edit medication",
    onClose = onDismiss,
    onSave = { saveMedication() },
    saveEnabled = formulaName.isNotBlank() && strength.isNotBlank() && invalidFieldLabels.isEmpty(),
    banner = if (invalidFieldLabels.isNotEmpty()) {
      {
        Text(
          text = "Fix to save: ${invalidFieldLabels.joinToString(", ")}",
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
                        Text("Scan Label")
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
                        Text("Label Photo")
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
                        Text("Match FDA")
                      }
                    }
                  }
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
            label = { Text("Brand Name (Optional)") },
            placeholder = { Text("e.g. Augmentin, Tylenol, Advil") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )

          OutlinedTextField(
            value = formulaName,
            onValueChange = { formulaName = it },
            label = { Text("Formula / Generic Name *") },
            placeholder = { Text("e.g. Amoxicillin / Clavulanate, Acetaminophen") },
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
              label = { Text("Strength *") },
              placeholder = { Text("e.g. 200 mg/mL") },
              singleLine = true,
              modifier = Modifier.weight(0.45f),
            )
            DropdownSelectorField(
              label = "Form",
              selectedText = form.displayName,
              options = MedicationForm.entries,
              optionLabel = { it.displayName },
              onOptionSelected = { selectedForm ->
                form = selectedForm
                if (selectedForm.isInjectable && defaultDoseUnit == DoseUnit.TABLET) {
                  defaultDoseUnit = DoseUnit.MG
                  siteRotationEnabled = true
                } else if (selectedForm == MedicationForm.TOPICAL) {
                  defaultDoseUnit = DoseUnit.APPLICATION
                  siteRotationEnabled = true
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
              label = { Text("Default Dose") },
              singleLine = true,
              modifier = Modifier.weight(0.45f),
            )
            DropdownSelectorField(
              label = "Unit",
              selectedText = "${defaultDoseUnit.symbol} (${defaultDoseUnit.displayName})",
              options = DoseUnit.entries,
              optionLabel = { "${it.symbol} — ${it.displayName}" },
              onOptionSelected = { defaultDoseUnit = it },
              modifier = Modifier.weight(0.55f),
            )
          }

          // Indication (Condition selector + optional notes)
          if (showConditions) {
            val selectedConditionObj = conditions.find { it.id == selectedConditionId }
            DropdownSelectorField(
              label = "Indication (Condition)",
              selectedText = selectedConditionObj?.name ?: "None linked",
              options = listOf<Condition?>(null) + conditions,
              optionLabel = { it?.name ?: "None linked" },
              onOptionSelected = { selectedConditionId = it?.id },
              modifier = Modifier.fillMaxWidth(),
            )
          }

          // 2. Injectable / Vial Specifications (only when Form is Injectable)
          if (form.isInjectable) {
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
                  text = "Injectable & Syringe / Needle Details",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )

                DropdownSelectorField(
                  label = "Administration Route (IM vs SC)",
                  selectedText = route.displayName,
                  options = InjectionRoute.entries,
                  optionLabel = { it.displayName },
                  onOptionSelected = { route = it },
                  modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  ValidatedNumberField(
                    check = concentrationCheck,
                    keyboardType = KeyboardType.Decimal,
                    value = concentrationText,
                    onValueChange = { concentrationText = it },
                    label = { Text("Conc. (mg/mL)") },
                    placeholder = { Text("200") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  ValidatedNumberField(
                    check = vialVolumeCheck,
                    keyboardType = KeyboardType.Decimal,
                    value = vialVolumeText,
                    onValueChange = { vialVolumeText = it },
                    label = { Text("Vial Size (mL)") },
                    placeholder = { Text("10") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  ValidatedNumberField(
                    check = syringeVolumeCheck,
                    keyboardType = KeyboardType.Decimal,
                    value = syringeVolumeText,
                    onValueChange = { syringeVolumeText = it },
                    label = { Text("Syringe (mL)") },
                    placeholder = { Text("1.0") },
                    singleLine = true,
                    modifier = Modifier.weight(0.45f),
                  )
                  OutlinedTextField(
                    value = syringeDesc,
                    onValueChange = { syringeDesc = it },
                    label = { Text("Syringe Type") },
                    placeholder = { Text("1 mL Luer-Lok") },
                    singleLine = true,
                    modifier = Modifier.weight(0.55f),
                  )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  ValidatedNumberField(
                    check = drawGaugeCheck,
                    keyboardType = KeyboardType.Number,
                    value = drawGaugeText,
                    onValueChange = { drawGaugeText = it },
                    label = { Text("Draw Gauge (G)") },
                    placeholder = { Text("18") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  ValidatedNumberField(
                    check = drawLengthCheck,
                    keyboardType = KeyboardType.Decimal,
                    value = drawLengthText,
                    onValueChange = { drawLengthText = it },
                    label = { Text("Draw Length (in)") },
                    placeholder = { Text("1.0") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  ValidatedNumberField(
                    check = injectGaugeCheck,
                    keyboardType = KeyboardType.Number,
                    value = injectGaugeText,
                    onValueChange = { injectGaugeText = it },
                    label = { Text("Inject Gauge (G)") },
                    placeholder = { Text("25") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  ValidatedNumberField(
                    check = injectLengthCheck,
                    keyboardType = KeyboardType.Decimal,
                    value = injectLengthText,
                    onValueChange = { injectLengthText = it },
                    label = { Text("Inject Length (in)") },
                    placeholder = { Text("1.0") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
          }

          // 3. Site Rotation Card (only when Form is Injectable or Topical)
          if (form.supportsSiteRotation) {
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "Automated Site Rotation",
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                      text = "Advances automatically from the actual site used",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Switch(
                    checked = siteRotationEnabled,
                    onCheckedChange = { siteRotationEnabled = it },
                  )
                }

                if (siteRotationEnabled) {
                  val presets = listOf(
                    "L/R Ventrogluteal (IM)" to Medication.PRESET_IM_GLUTE_SITES,
                    "L/R Deltoid (IM)" to Medication.PRESET_IM_DELT_SITES,
                    "L/R Thigh (IM)" to Medication.PRESET_IM_THIGH_SITES,
                    "6-Site Full Rotation (IM)" to Medication.PRESET_IM_FULL_ROTATION,
                    "4-Quadrant Abdomen (SC)" to Medication.PRESET_SC_ABDOMEN_SITES,
                    "L/R Arms & Shoulders (Topical)" to Medication.PRESET_TOPICAL_SITES,
                  )
                  DropdownSelectorField(
                    label = "Load Site Preset",
                    selectedText = "${rotationSites.size} sites configured",
                    options = presets,
                    optionLabel = { it.first },
                    onOptionSelected = { (_, siteList) ->
                      rotationSites.clear()
                      rotationSites.addAll(siteList)
                    },
                    modifier = Modifier.fillMaxWidth(),
                  )

                  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rotationSites.forEachIndexed { idx, site ->
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Text(
                          text = "${idx + 1}. $site",
                          style = MaterialTheme.typography.bodyMedium,
                          modifier = Modifier.weight(1f),
                        )
                        IconButton(
                          onClick = {
                            if (idx > 0) {
                              val item = rotationSites.removeAt(idx)
                              rotationSites.add(idx - 1, item)
                            }
                          },
                          enabled = idx > 0,
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                          onClick = {
                            if (idx < rotationSites.lastIndex) {
                              val item = rotationSites.removeAt(idx)
                              rotationSites.add(idx + 1, item)
                            }
                          },
                          enabled = idx < rotationSites.lastIndex,
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                          onClick = { rotationSites.removeAt(idx) },
                          modifier = Modifier.size(32.dp),
                        ) {
                          Icon(Icons.Default.Close, contentDescription = "Remove site", modifier = Modifier.size(16.dp))
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
                      label = { Text("Add Custom Site") },
                      singleLine = true,
                      modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                      onClick = {
                        if (newCustomSite.isNotBlank()) {
                          rotationSites.add(newCustomSite.trim())
                          newCustomSite = ""
                        }
                      },
                    ) {
                      Text("Add")
                    }
                  }
                }
              }
            }
          }

          // 4. Collapsible Pacing & Daily Limits Section
          val pacingSummary = listOfNotNull(
            minIntervalHoursText.takeIf { it.isNotBlank() }?.let { "Min ${it}h interval" },
            maxDailyDosesText.takeIf { it.isNotBlank() }?.let { "Max $it doses/day" },
          ).joinToString(" • ").ifEmpty { "Minimum time between doses & daily limits" }

          CollapsibleFormSection(
            title = "Pacing & Daily Limits (Optional)",
            subtitle = pacingSummary,
            initiallyExpanded = hasInitialPacing,
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              ValidatedNumberField(
                check = minIntervalCheck,
                keyboardType = KeyboardType.Decimal,
                value = minIntervalHoursText,
                onValueChange = { minIntervalHoursText = it },
                label = { Text("Min Interval (Hours)") },
                placeholder = { Text("e.g. 4") },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
              ValidatedNumberField(
                check = maxDailyDosesCheck,
                keyboardType = KeyboardType.Decimal,
                value = maxDailyDosesText,
                onValueChange = { maxDailyDosesText = it },
                label = { Text("Max Daily Doses") },
                placeholder = { Text("e.g. 2") },
                singleLine = true,
                modifier = Modifier.weight(1f),
              )
            }
            ValidatedNumberField(
              check = maxDailyAmountCheck,
              keyboardType = KeyboardType.Decimal,
              value = maxDailyAmountText,
              onValueChange = { maxDailyAmountText = it },
              label = { Text("Max Daily Amount (${defaultDoseUnit.symbol})") },
              placeholder = { Text("Optional total daily limit") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          // 5. Collapsible Optional Rx & Drug Details Section (NDC-11, Manufacturer, Prescriber, Notes)
          val optionalSummary = listOfNotNull(
            ndc11.takeIf { it.isNotBlank() }?.let { "NDC $it" },
            manufacturer.takeIf { it.isNotBlank() },
            prescribers.find { it.id == selectedPrescriberId }?.name?.takeIf { showPrescribers },
          ).joinToString(" • ").ifEmpty {
            if (showPrescribers) "NDC-11, manufacturer, prescriber, prescribed date & notes"
            else "NDC-11, manufacturer, prescribed date & notes"
          }

          CollapsibleFormSection(
            title = "Optional Rx & Drug Details",
            subtitle = optionalSummary,
            initiallyExpanded = hasInitialOptionalDetails,
          ) {
            OutlinedTextField(
              value = ndc11,
              onValueChange = {
                ndc11 = it
                ndcErrorText = null
                ndcStatusText = null
                ndcMatched = false
              },
              label = { Text("NDC-11 (Optional)") },
              placeholder = { Text("e.g. 00781-1852-20") },
              singleLine = true,
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
                    Text("Lookup")
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = manufacturer,
              onValueChange = { manufacturer = it },
              label = { Text("Manufacturer") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )

            if (showPrescribers) {
              val selectedDoc = prescribers.find { it.id == selectedPrescriberId }
              DropdownSelectorField(
                label = "Prescriber",
                selectedText = selectedDoc?.let { if (it.isPcp) "${it.name} (PCP)" else it.name } ?: "None selected",
                options = listOf<Prescriber?>(null) + prescribers,
                optionLabel = { doc ->
                  doc?.let { if (it.isPcp) "${it.name} (PCP)" else it.name } ?: "None selected"
                },
                onOptionSelected = { selectedPrescriberId = it?.id },
                modifier = Modifier.fillMaxWidth(),
              )
            }

            DatePickerField(
              label = "Prescribed date",
              valueIso = prescribedDateIso,
              onValueChange = { prescribedDateIso = it },
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = indicationText,
              onValueChange = { indicationText = it },
              label = { Text("Indication Notes") },
              placeholder = { Text("Supplemental indication details") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
              value = notes,
              onValueChange = { notes = it },
              label = { Text("Instructions / Notes") },
              modifier = Modifier.fillMaxWidth(),
            )
          }
  }

  // Label OCR Photo / Paste Dialog (for pharmacy vials, compounded bottles, and OTC labels)
  if (isGeminiNanoSupported && showLabelScanDialog) {
    AlertDialog(
      onDismissRequest = { showLabelScanDialog = false },
      title = { Text("Scan or Paste Medication Label Text") },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = "Pharmacy prescription vials, compounded bottles, and OTC containers print drug name, strength, manufacturer (MFG), NDC, and prescriber in text rather than an FDA barcode.",
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
              Text("Camera")
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
              Text("Photos")
            }
          }
          HorizontalDivider()
          OutlinedTextField(
            value = manualLabelInput,
            onValueChange = { manualLabelInput = it },
            label = { Text("Or Paste Printed Label Text") },
            placeholder = {
              Text("Paste text copied from a prescription label or pharmacy portal...")
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
          Text("Parse & Cross-Reference")
        }
      },
      dismissButton = {
        TextButton(onClick = { showLabelScanDialog = false }) {
          Text("Cancel")
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
          statusSummary = "Selected FDA match (${chosen.result.ndc11} • $signals)",
        )
      },
      onKeepWithoutNdc = {
        disambiguationPrompt = null
        ndcStatusText = "Kept entered/scanned fields without assigning a specific manufacturer NDC"
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
    title = { Text("Confirm Exact Medication Match") },
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
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = if (!res.brandName.isNullOrBlank()) {
                    "${res.brandName} (${res.formulaName})"
                  } else {
                    res.formulaName
                  },
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.weight(1f),
                )
                Text(
                  text = res.strength,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.SemiBold,
                )
              }
              Text(
                text = "${res.form.displayName} • Mfg: ${res.manufacturer ?: "Unspecified"} • NDC: ${res.ndc11}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (cand.matchedSignals.isNotEmpty()) {
                Text(
                  text = "Matched: ${cand.matchedSignals.joinToString(" • ")}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
              if (cand.missingOrDifferentSignals.isNotEmpty()) {
                Text(
                  text = "Note: ${cand.missingOrDifferentSignals.joinToString(" • ")}",
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
        Text("Keep Fields Only (No Specific NDC)")
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
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous month")
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
          Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next month")
        }
      }

      TextButton(onClick = { showRangePicker = true }) {
        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Add range")
      }
    }

    // Weekday headers: S M T W T F S
    val daysOfWeekLabels = remember { listOf("S", "M", "T", "W", "T", "F", "S") }
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
        text = if (sortedDates.isEmpty()) "No dates selected" else "${sortedDates.size} date${if (sortedDates.size == 1) "" else "s"} selected",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (sortedDates.isNotEmpty()) {
        TextButton(onClick = { selectedDates.clear() }) {
          Text("Clear all")
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
              Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
            },
          )
        }
        if (sortedDates.size > displayLimit) {
          Text(
            text = "+${sortedDates.size - displayLimit} more",
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
  val selectedBucketIds = remember {
    mutableStateListOf<String>().apply {
      addAll(initialSchedule?.bucketIds ?: listOfNotNull(orderedBuckets.firstOrNull()?.id))
    }
  }
  var doseAmountText by rememberSaveable {
    mutableStateOf(formatNum(initialSchedule?.doseAmount ?: medication.defaultDoseAmount))
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
  val selectedDaysOfWeek = remember {
    mutableStateListOf<Int>().apply { addAll(initialSchedule?.daysOfWeek ?: setOf(1, 2, 3, 4, 5, 6, 7)) }
  }
  val selectedSpecificDates = remember {
    mutableStateListOf<String>().apply { addAll(initialSchedule?.specificDatesIso?.sorted().orEmpty()) }
  }

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
  val startError = if (startDate == null) "Pick a start date" else null
  val endError = if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
    "End date can't be before the start date"
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
    "On a single-day schedule the last dose can't come before the first"
  } else {
    null
  }
  val bucketError = if (frequencyType != ScheduleFrequencyType.AS_NEEDED && validBucketIds.isEmpty()) {
    "Select at least one dose window"
  } else {
    null
  }
  val daysError = if (frequencyType == ScheduleFrequencyType.DAYS_OF_WEEK && selectedDaysOfWeek.isEmpty()) {
    "Select at least one day"
  } else {
    null
  }
  val specificDatesError = if (frequencyType == ScheduleFrequencyType.SPECIFIC_DATES && selectedSpecificDates.isEmpty()) {
    "Select at least one date"
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

  EditorScaffold(
    title = if (initialSchedule == null) "Add schedule" else "Edit schedule",
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
      label = "How often",
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
        label = { Text("Dose") },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      DropdownSelectorField(
        label = "Unit",
        selectedText = doseUnit.symbol,
        options = DoseUnit.entries,
        optionLabel = { "${it.symbol} — ${it.displayName}" },
        onOptionSelected = { doseUnit = it },
        modifier = Modifier.weight(1f),
      )
    }

    if (usesInterval) {
      ValidatedNumberField(
        check = intervalCheck,
        keyboardType = KeyboardType.Number,
        value = intervalDaysText,
        onValueChange = { intervalDaysText = it },
        label = { Text("Every how many days") },
        placeholder = { Text("7") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    if (usesDateRange) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DatePickerField(
          label = "Start date",
          valueIso = startDateIso,
          onValueChange = { startDateIso = it },
          allowClear = false,
          supportingText = startError ?: if (usesInterval) "First dose of the cycle" else null,
          modifier = Modifier.weight(1f),
        )
        DatePickerField(
          label = "End date",
          valueIso = endDateIso,
          onValueChange = { endDateIso = it },
          supportingText = endError ?: if (endDateIso.isBlank()) "Ongoing" else "Last day, inclusive",
          modifier = Modifier.weight(1f),
        )
      }
    }

    if (frequencyType == ScheduleFrequencyType.DAYS_OF_WEEK) {
      Text("Days", style = MaterialTheme.typography.titleSmall)
      val dayNames = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
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
      Text("Dates", style = MaterialTheme.typography.titleSmall)
      SpecificDatesPicker(
        selectedDates = selectedSpecificDates,
        modifier = Modifier.fillMaxWidth(),
      )
      specificDatesError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    if (frequencyType != ScheduleFrequencyType.AS_NEEDED) {
      Text("Dose windows", style = MaterialTheme.typography.titleSmall)
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        orderedBuckets.forEach { b ->
          val isSelected = selectedBucketIds.contains(b.id)
          FilterChip(
            selected = isSelected,
            onClick = { if (isSelected) selectedBucketIds.remove(b.id) else selectedBucketIds.add(b.id) },
            label = { Text("${b.name} · ${b.formattedTime()}") },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        bucketError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
      }
    }

    if (showBoundaryWindows && firstWindow != null && lastWindow != null) {
      Text("First and last day", style = MaterialTheme.typography.titleSmall)
      Text(
        "For courses that start or finish partway through a day, like an antibiotic picked up in the evening.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DropdownSelectorField(
          label = "First dose (start date)",
          selectedText = firstWindow.name,
          options = chosenWindows,
          optionLabel = { "${it.name} · ${it.formattedStartTime()}" },
          onOptionSelected = { startBucketId = it.id },
          modifier = Modifier.weight(1f),
        )
        if (endDate != null) {
          DropdownSelectorField(
            label = "Last dose (end date)",
            selectedText = lastWindow.name,
            options = chosenWindows,
            optionLabel = { "${it.name} · ${it.formattedStartTime()}" },
            onOptionSelected = { endBucketId = it.id },
            modifier = Modifier.weight(1f),
          )
        }
      }
      boundaryError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
  }
}
