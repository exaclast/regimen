package com.exaclast.regimen.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.exaclast.regimen.R
import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.Indication
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.ItemSource
import com.exaclast.regimen.model.KeyIngredient
import com.exaclast.regimen.model.Medication
import com.exaclast.regimen.model.MedicationForm
import com.exaclast.regimen.model.Provider
import com.exaclast.regimen.model.SiteMode
import com.exaclast.regimen.model.Supplement
import com.exaclast.regimen.model.formatExact
import com.exaclast.regimen.model.formatNum
import com.exaclast.regimen.model.texts
import com.exaclast.regimen.ui.checkOptionalPositiveDecimal
import com.exaclast.regimen.ui.checkRequiredPositiveDecimal
import com.exaclast.regimen.ui.components.AdaptiveFieldPair
import com.exaclast.regimen.ui.components.AdaptiveSingleChoiceRow
import com.exaclast.regimen.ui.components.DatePickerField
import com.exaclast.regimen.ui.components.EditorScaffold
import com.exaclast.regimen.ui.components.label
import kotlin.math.roundToInt

/**
 * Full-screen editor for adding or editing a [Supplement], separate from [MedicationEditorScreen].
 */
@Composable
fun SupplementEditorScreen(
  initialSupplement: Supplement?,
  indications: List<Indication>,
  providers: List<Provider>,
  // Optional tracking: hidden fields keep any existing link untouched.
  showIndications: Boolean = true,
  showProviders: Boolean = true,
  onDismiss: () -> Unit,
  onSave: (Supplement) -> Unit,
) {
  val resources = LocalResources.current

  var source by rememberSaveable { mutableStateOf(initialSupplement?.source ?: ItemSource.OTC) }
  var name by rememberSaveable { mutableStateOf(initialSupplement?.name.orEmpty()) }
  var strength by rememberSaveable { mutableStateOf(initialSupplement?.strength.orEmpty()) }
  var form by rememberSaveable { mutableStateOf(initialSupplement?.form ?: MedicationForm.CAPSULE) }
  var defaultDoseAmountText by rememberSaveable {
    mutableStateOf(initialSupplement?.defaultDoseAmount?.let { formatExact(it) } ?: "1")
  }
  var defaultDoseUnit by rememberSaveable {
    mutableStateOf(initialSupplement?.defaultDoseUnit ?: DoseUnit.CAPSULE)
  }

  val ingredients = rememberSaveable(
    saver = listSaver<SnapshotStateList<KeyIngredient>, String>(
      save = { list -> list.flatMap { listOf(it.name, it.amount.orEmpty()) } },
      restore = { flat ->
        flat.chunked(2).mapNotNull { pair ->
          pair.getOrNull(0)?.let { ingName -> KeyIngredient(ingName, pair.getOrNull(1)?.takeIf { it.isNotBlank() }) }
        }.toMutableStateList()
      },
    ),
  ) {
    initialSupplement?.ingredients.orEmpty().toMutableStateList()
  }
  var newIngredientName by rememberSaveable { mutableStateOf("") }
  var newIngredientAmount by rememberSaveable { mutableStateOf("") }

  var siteMode by rememberSaveable {
    mutableStateOf(initialSupplement?.siteMode ?: (initialSupplement?.form ?: MedicationForm.CAPSULE).defaultSiteMode)
  }
  var siteModeChosen by rememberSaveable { mutableStateOf(initialSupplement?.form?.hasSites == true) }
  val sites = rememberSaveable(
    saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() }),
  ) {
    (initialSupplement?.sites ?: emptyList()).toMutableStateList()
  }
  var sitesChosen by rememberSaveable { mutableStateOf(initialSupplement?.form?.hasSites == true) }
  var newCustomSite by rememberSaveable { mutableStateOf("") }

  LaunchedEffect(form) {
    if (!siteModeChosen) siteMode = form.defaultSiteMode
    if (!sitesChosen) {
      sites.clear()
      sites.addAll(Medication.defaultSites(form, InjectionRoute.IM))
    }
  }

  var minIntervalHoursText by rememberSaveable {
    mutableStateOf(initialSupplement?.minIntervalMinutes?.let { formatNum(it / 60.0) }.orEmpty())
  }
  var maxDailyDosesText by rememberSaveable {
    mutableStateOf(initialSupplement?.maxDailyDoses?.let { formatExact(it) }.orEmpty())
  }
  var maxDailyAmountText by rememberSaveable {
    mutableStateOf(initialSupplement?.maxDailyAmount?.let { formatExact(it) }.orEmpty())
  }

  var selectedIndicationIds by rememberSaveable { mutableStateOf(initialSupplement?.indicationIds.orEmpty()) }
  var indicationText by rememberSaveable { mutableStateOf(initialSupplement?.indicationText.orEmpty()) }
  var selectedPrescriberId by rememberSaveable { mutableStateOf(initialSupplement?.prescriberId) }
  var startedDateIso by rememberSaveable { mutableStateOf(initialSupplement?.startedDateIso.orEmpty()) }
  var manufacturer by rememberSaveable { mutableStateOf(initialSupplement?.manufacturer.orEmpty()) }
  var notes by rememberSaveable { mutableStateOf(initialSupplement?.notes.orEmpty()) }

  val hasInitialPacing = initialSupplement?.let {
    it.minIntervalMinutes != null || it.maxDailyDoses != null || it.maxDailyAmount != null
  } ?: false
  val hasInitialOptionalDetails = initialSupplement?.let {
    !it.manufacturer.isNullOrBlank() ||
      it.prescriberId != null ||
      !it.startedDateIso.isNullOrBlank() ||
      it.indicationText.isNotBlank() ||
      !it.notes.isNullOrBlank()
  } ?: false

  val doseCheck = checkRequiredPositiveDecimal(defaultDoseAmountText)
  val minIntervalCheck = checkOptionalPositiveDecimal(minIntervalHoursText)
  val maxDailyDosesCheck = checkOptionalPositiveDecimal(maxDailyDosesText)
  val maxDailyAmountCheck = checkOptionalPositiveDecimal(maxDailyAmountText)

  val invalidFieldLabels = buildList {
    if (!doseCheck.isValid) add(stringResource(R.string.editor_default_dose))
    if (form.hasSites && siteMode != SiteMode.OFF && sites.isEmpty()) add(stringResource(R.string.med_sites))
    if (!minIntervalCheck.isValid) add(stringResource(R.string.editor_min_interval))
    if (!maxDailyDosesCheck.isValid) add(stringResource(R.string.editor_max_daily_doses))
    if (!maxDailyAmountCheck.isValid) add(stringResource(R.string.editor_max_daily_amount))
  }

  fun saveSupplement() {
    val doseAmt = doseCheck.validOrNull() ?: return
    val minIntervalMins = minIntervalCheck.validOrNull()
      ?.let { (it * 60.0).roundToInt().coerceAtLeast(1) }
    val pendingIng = newIngredientName.trim().takeIf { it.isNotEmpty() }?.let {
      KeyIngredient(it, newIngredientAmount.trim().takeIf { a -> a.isNotEmpty() })
    }
    val allIngredients = (ingredients.toList() + listOfNotNull(pendingIng)).filter { it.name.isNotBlank() }
    val saved = Supplement(
      id = initialSupplement?.id ?: java.util.UUID.randomUUID().toString(),
      name = name.trim(),
      strength = strength.trim(),
      form = form,
      source = source,
      defaultDoseAmount = doseAmt,
      defaultDoseUnit = defaultDoseUnit,
      ingredients = allIngredients,
      siteMode = if (form.hasSites) siteMode else SiteMode.OFF,
      sites = if (form.hasSites) sites.toList() else emptyList(),
      minIntervalMinutes = minIntervalMins,
      maxDailyDoses = maxDailyDosesCheck.validOrNull(),
      maxDailyAmount = maxDailyAmountCheck.validOrNull(),
      indicationIds = selectedIndicationIds,
      indicationText = indicationText.trim(),
      prescriberId = selectedPrescriberId,
      startedDateIso = startedDateIso.trim().takeIf { it.isNotBlank() },
      manufacturer = manufacturer.trim().takeIf { it.isNotBlank() },
      notes = notes.trim().takeIf { it.isNotBlank() },
      createdAtEpochMillis = initialSupplement?.createdAtEpochMillis ?: System.currentTimeMillis(),
    )
    onSave(saved)
  }

  val initialForm = initialSupplement?.form ?: MedicationForm.CAPSULE
  val initialSiteMode = initialSupplement?.siteMode ?: initialForm.defaultSiteMode
  val initialSites = remember(initialSupplement?.id) {
    initialSupplement?.sites ?: Medication.defaultSites(initialForm, InjectionRoute.IM)
  }
  val initialDoseAmountText = remember(initialSupplement?.id) {
    initialSupplement?.defaultDoseAmount?.let { formatExact(it) } ?: "1"
  }
  val initialDoseUnit = initialSupplement?.defaultDoseUnit ?: DoseUnit.CAPSULE
  val initialMinIntervalHoursText = remember(initialSupplement?.id) {
    initialSupplement?.minIntervalMinutes?.let { formatNum(it / 60.0) }.orEmpty()
  }
  val initialSource = initialSupplement?.source ?: ItemSource.OTC
  val hasUnsavedChanges =
    source != initialSource ||
      name != initialSupplement?.name.orEmpty() ||
      manufacturer != initialSupplement?.manufacturer.orEmpty() ||
      strength != initialSupplement?.strength.orEmpty() ||
      form != initialForm ||
      defaultDoseAmountText != initialDoseAmountText ||
      defaultDoseUnit != initialDoseUnit ||
      ingredients.toList() != initialSupplement?.ingredients.orEmpty() ||
      newIngredientName.isNotEmpty() ||
      newIngredientAmount.isNotEmpty() ||
      siteMode != initialSiteMode ||
      sites.toList() != initialSites ||
      newCustomSite.isNotEmpty() ||
      minIntervalHoursText != initialMinIntervalHoursText ||
      maxDailyDosesText != initialSupplement?.maxDailyDoses?.let { formatExact(it) }.orEmpty() ||
      maxDailyAmountText != initialSupplement?.maxDailyAmount?.let { formatExact(it) }.orEmpty() ||
      selectedIndicationIds != initialSupplement?.indicationIds.orEmpty() ||
      indicationText != initialSupplement?.indicationText.orEmpty() ||
      selectedPrescriberId != initialSupplement?.prescriberId ||
      startedDateIso != initialSupplement?.startedDateIso.orEmpty() ||
      notes != initialSupplement?.notes.orEmpty()

  val editorTitle = if (initialSupplement == null) {
    R.string.editor_supplement_title_add
  } else {
    R.string.editor_supplement_title_edit
  }

  EditorScaffold(
    title = stringResource(editorTitle),
    onClose = onDismiss,
    onSave = { saveSupplement() },
    saveEnabled = name.isNotBlank() && invalidFieldLabels.isEmpty(),
    hasUnsavedChanges = hasUnsavedChanges,
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
    AdaptiveSingleChoiceRow(
      options = ItemSource.entries,
      selected = source,
      onSelect = { source = it },
      label = { resources.label(it) },
    )

    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text(stringResource(R.string.editor_supplement_name)) },
      placeholder = { Text(stringResource(R.string.editor_supplement_name_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    AdaptiveFieldPair(
      firstWeight = 0.45f,
      first = { fieldModifier ->
        OutlinedTextField(
          value = strength,
          onValueChange = { strength = it },
          label = { Text(stringResource(R.string.editor_strength_short)) },
          placeholder = { Text(stringResource(R.string.editor_strength_optional_hint)) },
          singleLine = true,
          modifier = fieldModifier,
        )
      },
      second = { fieldModifier ->
        DropdownSelectorField(
          label = stringResource(R.string.editor_form),
          selectedText = form.label(),
          options = MedicationForm.SUPPLEMENT_FORMS,
          optionLabel = { resources.label(it) },
          onOptionSelected = { selectedForm ->
            form = selectedForm
            defaultDoseUnit = when (selectedForm) {
              MedicationForm.TOPICAL -> DoseUnit.APPLICATION
              MedicationForm.CAPSULE -> DoseUnit.CAPSULE
              MedicationForm.SOFTGEL -> DoseUnit.SOFTGEL
              MedicationForm.GUMMY -> DoseUnit.GUMMY
              MedicationForm.POWDER -> DoseUnit.SCOOP
              MedicationForm.TABLET, MedicationForm.CHEWABLE -> DoseUnit.TABLET
              MedicationForm.LIQUID -> DoseUnit.ML
              MedicationForm.DROPS -> DoseUnit.DROP
              MedicationForm.INHALER -> DoseUnit.PUFF
              MedicationForm.INJECTABLE_VIAL, MedicationForm.INJECTABLE_PEN, MedicationForm.OTHER -> defaultDoseUnit
            }
          },
          modifier = fieldModifier,
        )
      },
    )

    AdaptiveFieldPair(
      firstWeight = 0.45f,
      first = { fieldModifier ->
        ValidatedNumberField(
          check = doseCheck,
          keyboardType = KeyboardType.Decimal,
          value = defaultDoseAmountText,
          onValueChange = { defaultDoseAmountText = it },
          label = { Text(stringResource(R.string.editor_default_dose)) },
          singleLine = true,
          modifier = fieldModifier,
        )
      },
      second = { fieldModifier ->
        DropdownSelectorField(
          label = stringResource(R.string.editor_unit),
          selectedText = "${defaultDoseUnit.symbol} (${defaultDoseUnit.label()})",
          options = DoseUnit.entries,
          optionLabel = { "${it.symbol} — ${resources.label(it)}" },
          onOptionSelected = { defaultDoseUnit = it },
          modifier = fieldModifier,
        )
      },
    )

    if (form == MedicationForm.LIQUID) {
      volumeHint(resources.texts(), strength, doseCheck.validOrNull(), defaultDoseUnit)?.let { (text, isProblem) ->
        Text(
          text = text,
          style = MaterialTheme.typography.bodySmall,
          color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    val ingredientsSummary = if (ingredients.isEmpty()) {
      stringResource(R.string.editor_key_ingredients_none)
    } else {
      pluralStringResource(R.plurals.editor_key_ingredients_count, ingredients.size, ingredients.size)
    }
    CollapsibleFormSection(
      title = stringResource(R.string.editor_key_ingredients),
      subtitle = ingredientsSummary,
      initiallyExpanded = ingredients.isNotEmpty(),
    ) {
      ingredients.forEachIndexed { index, ing ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(ing.name, style = MaterialTheme.typography.bodyLarge)
            ing.amount?.takeIf { it.isNotBlank() }?.let { amt ->
              Text(
                amt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          IconButton(onClick = { ingredients.removeAt(index) }) {
            Icon(
              Icons.Default.Close,
              contentDescription = stringResource(R.string.editor_remove_ingredient, ing.name),
            )
          }
        }
        if (index < ingredients.lastIndex) HorizontalDivider()
      }
      AdaptiveFieldPair(
        firstWeight = 0.55f,
        first = { m ->
          OutlinedTextField(
            value = newIngredientName,
            onValueChange = { newIngredientName = it },
            label = { Text(stringResource(R.string.editor_ingredient_name)) },
            placeholder = { Text(stringResource(R.string.editor_ingredient_name_hint)) },
            singleLine = true,
            modifier = m,
          )
        },
        second = { m ->
          OutlinedTextField(
            value = newIngredientAmount,
            onValueChange = { newIngredientAmount = it },
            label = { Text(stringResource(R.string.editor_ingredient_amount_short)) },
            placeholder = { Text(stringResource(R.string.editor_ingredient_amount_hint)) },
            singleLine = true,
            modifier = m,
          )
        },
      )
      OutlinedButton(
        onClick = {
          val trimmed = newIngredientName.trim()
          if (trimmed.isNotEmpty()) {
            ingredients.add(KeyIngredient(trimmed, newIngredientAmount.trim().takeIf { it.isNotEmpty() }))
            newIngredientName = ""
            newIngredientAmount = ""
          }
        },
        enabled = newIngredientName.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.editor_add_ingredient))
      }
    }

    if (showIndications && indications.isNotEmpty()) {
      IndicationSelectorSection(
        indications = indications,
        selectedIds = selectedIndicationIds,
        onSelectionChange = { selectedIndicationIds = it },
      )
    }

    if (form.hasSites) {
      SitesEditorSection(
        form = form,
        siteMode = siteMode,
        onSiteModeChange = { mode ->
          siteMode = mode
          siteModeChosen = true
        },
        sites = sites,
        onSitesChanged = { sitesChosen = true },
        newCustomSite = newCustomSite,
        onNewCustomSiteChange = { newCustomSite = it },
      )
    }

    PacingEditorSection(
      minIntervalHoursText = minIntervalHoursText,
      onMinIntervalHoursChange = { minIntervalHoursText = it },
      minIntervalCheck = minIntervalCheck,
      maxDailyDosesText = maxDailyDosesText,
      onMaxDailyDosesChange = { maxDailyDosesText = it },
      maxDailyDosesCheck = maxDailyDosesCheck,
      maxDailyAmountText = maxDailyAmountText,
      onMaxDailyAmountChange = { maxDailyAmountText = it },
      maxDailyAmountCheck = maxDailyAmountCheck,
      defaultDoseUnit = defaultDoseUnit,
      initiallyExpanded = hasInitialPacing,
    )

    val optionalSummary = listOfNotNull(
      manufacturer.takeIf { it.isNotBlank() },
      providers.find { it.id == selectedPrescriberId }?.name?.takeIf { showProviders },
    ).joinToString(" • ").ifEmpty {
      if (showProviders) {
        stringResource(R.string.editor_supplement_optional_summary_providers)
      } else {
        stringResource(R.string.editor_supplement_optional_summary)
      }
    }

    CollapsibleFormSection(
      title = stringResource(R.string.editor_supplement_optional_title),
      subtitle = optionalSummary,
      initiallyExpanded = hasInitialOptionalDetails,
    ) {
      OutlinedTextField(
        value = manufacturer,
        onValueChange = { manufacturer = it },
        label = { Text(stringResource(R.string.editor_brand_manufacturer)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      if (showProviders) {
        val selectedDoc = providers.find { it.id == selectedPrescriberId }
        val noneSelected = stringResource(R.string.editor_none_selected)
        DropdownSelectorField(
          label = stringResource(R.string.editor_recommended_by),
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
        label = stringResource(R.string.editor_start_date),
        valueIso = startedDateIso,
        onValueChange = { startedDateIso = it },
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
}
