package com.exaclast.regimen.data

import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.MedicationForm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Structured data extracted from a medication bottle/vial label by on-device Gemini Nano (AICore).
 */
data class ParsedMedicationLabel(
  val rawText: String,
  val extractedNdc: String? = null,
  val formulaName: String? = null,
  val brandName: String? = null,
  val strength: String? = null,
  val form: MedicationForm? = null,
  val defaultDoseUnit: DoseUnit? = null,
  val defaultDoseAmount: Double? = null,
  val manufacturer: String? = null,
  val prescriberName: String? = null,
  val lotNumber: String? = null,
  val expirationDateIso: String? = null,
  val instructions: String? = null,
  val isCompound: Boolean = false,
) {
  val hasAnyClinicalSignal: Boolean
    get() = !extractedNdc.isNullOrBlank() ||
      !formulaName.isNullOrBlank() ||
      !brandName.isNullOrBlank()
}

/**
 * JSON schema returned by the on-device Gemini Nano prompt.
 */
@Serializable
data class GeminiLabelExtraction(
  val ndc: String? = null,
  val formulaName: String? = null,
  val brandName: String? = null,
  val strength: String? = null,
  val form: String? = null,
  val defaultDoseAmount: Double? = null,
  val manufacturer: String? = null,
  val prescriberName: String? = null,
  val lotNumber: String? = null,
  val expirationDateIso: String? = null,
  val instructions: String? = null,
  val isCompound: Boolean = false,
)

object DrugLabelParser {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  private val PRESERVED_ALPHANUMERIC_TOKENS = setOf(
    "B1", "B2", "B3", "B5", "B6", "B7", "B9", "B12",
    "D2", "D3", "K1", "K2", "Q10", "U100", "24HR", "12HR",
  )

  /**
   * Builds the system/extraction prompt for on-device Gemini Nano (via ML Kit GenAI Prompt API).
   */
  fun buildLabelExtractionPrompt(additionalLabelText: String? = null): String {
    val textSection = if (!additionalLabelText.isNullOrBlank()) {
      "\nLabel text to analyze:\n\"\"\"\n${additionalLabelText.trim()}\n\"\"\"\n"
    } else {
      ""
    }
    return """
      Analyze this medication container label and extract the medication details into a single JSON object.
      Do not include the patient's name as the medication or brand name.
      Return ONLY valid JSON matching this exact structure (use null for any field not present on the label):
      {
        "ndc": "10- or 11-digit National Drug Code if printed (e.g. \"41167-4122-2\"), or null (ignore 6-digit packaging codes or pharmacy store Rx numbers)",
        "formulaName": "Generic / active ingredient name(s) in Title Case without strength (e.g. \"Anastrozole\", \"Fexofenadine HCl\", \"Acetaminophen / Ibuprofen\", or \"Finasteride / Minoxidil / Biotin / Vitamin B5 / Vitamin B6 / Vitamin C\")",
        "brandName": "Brand name if printed on the label (e.g. \"Allegra\", \"Advil Dual Action\"), or null for generic prescriptions",
        "strength": "Strength per unit or concentration (e.g. \"1 mg\", \"180 mg\", \"250 mg / 125 mg\", \"200 mg/mL\"), or null",
        "form": "One of: TABLET, CAPSULE, INJECTABLE_VIAL, INJECTABLE_PEN, TOPICAL, LIQUID, INHALER, DROPS, OTHER",
        "defaultDoseAmount": 1.0,
        "manufacturer": "Manufacturer, labeler (after MFG / Dist. By), or compounding pharmacy LLC name, or null",
        "prescriberName": "Prescriber / doctor name formatted as \"First Last\" without credentials, or null",
        "lotNumber": "Lot number if printed, or null",
        "expirationDateIso": "Expiration or use-by date formatted as \"YYYY-MM-DD\", or null",
        "instructions": "Dosing instructions (sig) if printed (e.g. \"Take 1 tablet by mouth once daily\"), or null",
        "isCompound": false
      }
      Set "isCompound" to true if the label says "COMPOUND PRODUCT" or lists 3 or more compounded active ingredients.
      $textSection
    """.trimIndent()
  }

  /**
   * Parses the JSON string returned by Gemini Nano into a [ParsedMedicationLabel].
   */
  fun parseGeminiJsonResponse(rawOutput: String): ParsedMedicationLabel {
    val trimmed = rawOutput.trim()
    val startIdx = trimmed.indexOf('{')
    val endIdx = trimmed.lastIndexOf('}')
    if (startIdx < 0 || endIdx <= startIdx) {
      throw IllegalArgumentException("Model did not return a valid JSON object.")
    }
    val jsonBlock = trimmed.substring(startIdx, endIdx + 1)
    val extracted = json.decodeFromString<GeminiLabelExtraction>(jsonBlock)

    val canonicalNdc = extracted.ndc
      ?.trim()
      ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
      ?.let { FdaNdcLookupService.toCanonicalNdc11(it) }
      ?.takeIf { it.count { ch -> ch.isDigit() } in 10..11 }

    val mappedForm = mapFormString(extracted.form)
    val mappedUnit = mappedForm?.let { defaultUnitForForm(it) }

    return ParsedMedicationLabel(
      rawText = rawOutput,
      extractedNdc = canonicalNdc,
      formulaName = extracted.formulaName?.cleanNullString(),
      brandName = extracted.brandName?.cleanNullString(),
      strength = extracted.strength?.cleanNullString(),
      form = mappedForm,
      defaultDoseUnit = mappedUnit,
      defaultDoseAmount = extracted.defaultDoseAmount?.takeIf { it > 0.0 },
      manufacturer = extracted.manufacturer?.cleanNullString(),
      prescriberName = extracted.prescriberName?.cleanNullString(),
      lotNumber = extracted.lotNumber?.cleanNullString(),
      expirationDateIso = extracted.expirationDateIso?.cleanNullString(),
      instructions = extracted.instructions?.cleanNullString(),
      isCompound = extracted.isCompound,
    )
  }

  private fun String.cleanNullString(): String? {
    val t = trim()
    return if (t.isEmpty() || t.equals("null", ignoreCase = true) || t.equals("none", ignoreCase = true)) {
      null
    } else {
      t
    }
  }

  private fun mapFormString(raw: String?): MedicationForm? {
    val upper = raw?.trim()?.uppercase() ?: return null
    return MedicationForm.entries.firstOrNull { it.name == upper }
      ?: when {
        upper.contains("VIAL") || upper.contains("INJECT") -> MedicationForm.INJECTABLE_VIAL
        upper.contains("PEN") -> MedicationForm.INJECTABLE_PEN
        upper.contains("TAB") || upper.contains("CHEW") || upper.contains("GELCAP") || upper.contains("CAPLET") ->
          MedicationForm.TABLET
        upper.contains("CAP") -> MedicationForm.CAPSULE
        upper.contains("TOPICAL") || upper.contains("CREAM") || upper.contains("GEL") || upper.contains("PATCH") ->
          MedicationForm.TOPICAL
        upper.contains("LIQUID") || upper.contains("SOLUTION") || upper.contains("SUSPENSION") ->
          MedicationForm.LIQUID
        upper.contains("INHAL") || upper.contains("SPRAY") -> MedicationForm.INHALER
        upper.contains("DROP") -> MedicationForm.DROPS
        else -> null
      }
  }

  private fun defaultUnitForForm(form: MedicationForm): DoseUnit {
    return when (form) {
      MedicationForm.TABLET -> DoseUnit.TABLET
      MedicationForm.CAPSULE -> DoseUnit.CAPSULE
      MedicationForm.INJECTABLE_VIAL, MedicationForm.INJECTABLE_PEN -> DoseUnit.MG
      MedicationForm.TOPICAL -> DoseUnit.APPLICATION
      MedicationForm.LIQUID -> DoseUnit.ML
      MedicationForm.INHALER -> DoseUnit.PUFF
      MedicationForm.DROPS -> DoseUnit.DROP
      MedicationForm.OTHER -> DoseUnit.MG
    }
  }

  /**
   * Normalizes OCR/typing digit-in-word confusions for fuzzy OpenFDA token matching.
   */
  internal fun deobfuscateOcrWord(word: String): String {
    val trimmed = word.trim()
    val upper = trimmed.uppercase()
    if (upper in PRESERVED_ALPHANUMERIC_TOKENS) return trimmed
    val letterCount = trimmed.count { it.isLetter() }
    val digitCount = trimmed.count { it.isDigit() }
    if (digitCount == 0 || letterCount < 3 || digitCount > letterCount) return trimmed

    return buildString(trimmed.length) {
      for (ch in trimmed) {
        val mapped = when (ch) {
          '0' -> 'o'
          '1' -> 'i'
          '5' -> 's'
          '8' -> 'b'
          '2' -> 'z'
          else -> ch
        }
        append(mapped)
      }
    }
  }
}
