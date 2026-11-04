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
  val patientName: String? = null,
  val pharmacyName: String? = null,
  val rxNumber: String? = null,
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
  val patientName: String? = null,
  val pharmacyName: String? = null,
  val rxNumber: String? = null,
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
  fun buildLabelExtractionPrompt(
    additionalLabelText: String? = null,
    knownPatientName: String? = null,
  ): String {
    val textSection = if (!additionalLabelText.isNullOrBlank()) {
      "\nLabel text to analyze:\n\"\"\"\n${additionalLabelText.trim()}\n\"\"\"\n"
    } else {
      ""
    }
    val patientConstraint = if (!knownPatientName.isNullOrBlank()) {
      "\nCRITICAL: The patient/customer is \"${knownPatientName.trim()}\". NEVER output \"${knownPatientName.trim()}\" (or any part of the patient's name) as brandName, formulaName, or manufacturer.\n"
    } else {
      ""
    }
    return """
      Analyze this medication container label and extract the medication details into a single JSON object.
      Return ONLY valid JSON matching this exact structure (use null for any field not present on the label):
      {
        "patientName": "Patient or customer name printed on the prescription label (e.g. \"John Smith\"), or null",
        "pharmacyName": "Dispensing pharmacy or clinic name (e.g. \"CVS Pharmacy\", \"Walgreens\", \"Kroger\"), or null",
        "rxNumber": "Prescription / Rx number printed on label (e.g. \"Rx 1234567\"), or null",
        "ndc": "10- or 11-digit National Drug Code if printed (e.g. \"41167-4122-2\"), or null (ignore 6-digit packaging codes or store Rx numbers)",
        "formulaName": "Generic / active ingredient name(s) in Title Case without strength (e.g. \"Amoxicillin / Clavulanate\", \"Fexofenadine HCl\", \"Acetaminophen / Ibuprofen\"), or null. NEVER put the patient name or pharmacy here.",
        "brandName": "Proprietary commercial brand name if printed on the label (e.g. \"Allegra\", \"Advil Dual Action\"), or null for generic prescriptions. NEVER put the patient name, prescriber name, or pharmacy name here.",
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
      $patientConstraint$textSection
    """.trimIndent()
  }

  /**
   * Parses the JSON string returned by Gemini Nano into a [ParsedMedicationLabel].
   */
  fun parseGeminiJsonResponse(
    rawOutput: String,
    userConfiguredPatientName: String? = null,
  ): ParsedMedicationLabel {
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

    val sanitizedPatient = extracted.patientName?.cleanNullString()
    val sanitizedPharmacy = extracted.pharmacyName?.cleanNullString()
    val sanitizedPrescriber = extracted.prescriberName?.cleanNullString()
    val rawFormula = extracted.formulaName?.cleanNullString()
    val rawBrand = extracted.brandName?.cleanNullString()

    // Deterministic post-processing filter to prevent patient name, prescriber, or pharmacy
    // from ever leaking into brandName or formulaName
    val (cleanFormula, cleanBrand) = sanitizeNames(
      formula = rawFormula,
      brand = rawBrand,
      patientNameOnLabel = sanitizedPatient,
      userConfiguredPatientName = userConfiguredPatientName,
      prescriber = sanitizedPrescriber,
      pharmacy = sanitizedPharmacy,
    )

    return ParsedMedicationLabel(
      rawText = rawOutput,
      extractedNdc = canonicalNdc,
      formulaName = cleanFormula,
      brandName = cleanBrand,
      strength = extracted.strength?.cleanNullString(),
      form = mappedForm,
      defaultDoseUnit = mappedUnit,
      defaultDoseAmount = extracted.defaultDoseAmount?.takeIf { it > 0.0 },
      manufacturer = extracted.manufacturer?.cleanNullString(),
      prescriberName = sanitizedPrescriber,
      lotNumber = extracted.lotNumber?.cleanNullString(),
      expirationDateIso = extracted.expirationDateIso?.cleanNullString(),
      instructions = extracted.instructions?.cleanNullString(),
      isCompound = extracted.isCompound,
      patientName = sanitizedPatient,
      pharmacyName = sanitizedPharmacy,
      rxNumber = extracted.rxNumber?.cleanNullString(),
    )
  }

  internal fun sanitizeNames(
    formula: String?,
    brand: String?,
    patientNameOnLabel: String?,
    userConfiguredPatientName: String?,
    prescriber: String?,
    pharmacy: String?,
  ): Pair<String?, String?> {
    val patientTokens = buildSet {
      userConfiguredPatientName?.let { addAll(extractMeaningfulTokens(it)) }
      patientNameOnLabel?.let { addAll(extractMeaningfulTokens(it)) }
    }

    val otherDisallowedTokens = buildSet {
      prescriber?.let { addAll(extractMeaningfulTokens(it)) }
      pharmacy?.let { addAll(extractMeaningfulTokens(it)) }
      add("patient")
      add("customer")
      add("doctor")
      add("dr")
      add("take")
      add("capsule")
      add("tablet")
      add("refill")
      add("generic")
    }

    var cleanBrand = brand
    var cleanFormula = formula

    if (!cleanBrand.isNullOrBlank()) {
      val brandTokens = extractMeaningfulTokens(cleanBrand)
      val brandMatchesPatient = brandTokens.isNotEmpty() && brandTokens.any { bt ->
        patientTokens.any { pt -> tokensMatchFuzzy(bt, pt) }
      }
      val brandMatchesOtherDisallowed = brandTokens.isNotEmpty() && brandTokens.any { bt ->
        otherDisallowedTokens.any { dt -> tokensMatchFuzzy(bt, dt) }
      }
      if (brandMatchesPatient || brandMatchesOtherDisallowed) {
        cleanBrand = null
      }
    }

    if (!cleanFormula.isNullOrBlank()) {
      val formulaTokens = extractMeaningfulTokens(cleanFormula)
      val formulaAllMatchesPatient = formulaTokens.isNotEmpty() && formulaTokens.all { ft ->
        patientTokens.any { pt -> tokensMatchFuzzy(ft, pt) }
      }
      if (formulaAllMatchesPatient) {
        cleanFormula = null
      }
    }

    return Pair(cleanFormula, cleanBrand)
  }

  private fun extractMeaningfulTokens(input: String): List<String> {
    return input.lowercase()
      .split(" ", ",", "/", "-", ".", "(", ")")
      .map { it.filter { ch -> ch.isLetterOrDigit() }.trim() }
      .filter { it.length >= 3 }
  }

  internal fun tokensMatchFuzzy(a: String, b: String): Boolean {
    if (a == b || a.contains(b) || b.contains(a)) return true
    if (a.length >= 4 && b.length >= 4) {
      return levenshteinDistance(a, b) <= 1
    }
    return false
  }

  internal fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val dp = IntArray(b.length + 1) { it }
    for (i in a.indices) {
      var prev = dp[0]
      dp[0] = i + 1
      for (j in b.indices) {
        val temp = dp[j + 1]
        dp[j + 1] = if (a[i] == b[j]) {
          prev
        } else {
          1 + minOf(prev, dp[j + 1], dp[j])
        }
        prev = temp
      }
    }
    return dp[b.length]
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
