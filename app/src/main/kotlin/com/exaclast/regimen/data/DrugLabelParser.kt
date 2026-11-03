package com.exaclast.regimen.data

import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.MedicationForm

/**
 * Structured data extracted from printed text on a medication bottle/vial label
 * (pharmacy amber vials, compounded medications, OTC bottles, or manufacturer cartons).
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

object DrugLabelParser {

  private val IGNORE_LINE_PATTERNS = listOf(
    Regex("""^\s*CASEY\b""", RegexOption.IGNORE_CASE),
    Regex("""\bREFILLS?\b""", RegexOption.IGNORE_CASE),
    Regex("""\bQTY\b|\bQUANTITY\b""", RegexOption.IGNORE_CASE),
    Regex("""\bDISPENSED\b|\bUSE BY\b""", RegexOption.IGNORE_CASE),
    Regex("""\bWARNING\b|\bCAUTION\b|\bFEDERAL LAW\b""", RegexOption.IGNORE_CASE),
    Regex("""\bRX\s*ONLY\b|\bRX\s*#""", RegexOption.IGNORE_CASE),
    Regex("""\bPEEL\s*HERE\b|\bREAD AND KEEP\b""", RegexOption.IGNORE_CASE),
    Regex("""\bCONTAINS\s+\d+\s+MEDICINES\b""", RegexOption.IGNORE_CASE),
    Regex("""\bNON-DROWSY\b|\bPAIN RELIEVER\b|\bANTIHISTAMINE\b""", RegexOption.IGNORE_CASE),
  )

  private val KNOWN_BRANDS = mapOf(
    "ALLEGRA" to ("Allegra" to "Fexofenadine HCl"),
    "ADVIL DUAL ACTION" to ("Advil Dual Action" to "Acetaminophen / Ibuprofen"),
    "ADVIL" to ("Advil" to "Ibuprofen"),
    "TYLENOL" to ("Tylenol" to "Acetaminophen"),
    "ZYRTEC" to ("Zyrtec" to "Cetirizine HCl"),
    "CLARITIN" to ("Claritin" to "Loratadine"),
    "FLONASE" to ("Flonase" to "Fluticasone Propionate"),
    "DESCOVY" to ("Descovy" to "Emtricitabine / Tenofovir Alafenamide"),
    "VYVANSE" to ("Vyvanse" to "Lisdexamfetamine Dimesylate"),
    "ZENZEDI" to ("Zenzedi" to "Dextroamphetamine Sulfate"),
    "OZEMPIC" to ("Ozempic" to "Semaglutide"),
    "WEGOVY" to ("Wegovy" to "Semaglutide"),
    "MOUNJARO" to ("Mounjaro" to "Tirzepatide"),
    "ZEPBOUND" to ("Zepbound" to "Tirzepatide"),
    "DEPO-TESTOSTERONE" to ("Depo-Testosterone" to "Testosterone Cypionate"),
  )

  fun parseLabelText(rawText: String): ParsedMedicationLabel {
    val lines = rawText
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() }

    val fullText = lines.joinToString("\n")

    // 1. Extract explicit hyphenated NDC if printed on label (e.g. "NDC 41167-4122-2" or "68382-0205-05")
    val ndcRegex = Regex("""(?:NDC[:#\s]*)?(\b\d{4,5}-\d{3,4}-\d{1,2}\b)""", RegexOption.IGNORE_CASE)
    val extractedNdc = ndcRegex.find(fullText)?.groupValues?.getOrNull(1)

    // 2. Detect compound status (e.g. "THIS IS A COMPOUND PRODUCT")
    val explicitCompound = fullText.contains("COMPOUND PRODUCT", ignoreCase = true) ||
      fullText.contains("COMPOUNDED", ignoreCase = true)

    // 3. Extract Manufacturer / Labeler ("MFG ZYDUS", "MFR ...", "Dist. By Chattem, Inc.", "XeCare LLC")
    val mfgRegex = Regex(
      """(?:MFG|MFR|MANUFACTURER|DIST\.?[ \t]*BY)[: \t]+([A-Za-z0-9 \t,&.-]{2,35})""",
      RegexOption.IGNORE_CASE,
    )
    var manufacturer = mfgRegex.find(fullText)?.groupValues?.getOrNull(1)
      ?.lineSequence()?.firstOrNull()?.trim()?.trimEnd(',', '.')

    if (manufacturer == null) {
      val pharmacyLlcMatch = Regex("""\b([A-Z][A-Za-z0-9]+[ \t]+LLC)\b""").find(fullText)
      manufacturer = pharmacyLlcMatch?.groupValues?.getOrNull(1)?.trim()
    }

    // 4. Extract Prescriber ("PRESCRIBED BY: Vacek, Jeffrey" or "LENA OTTOSEN, APN")
    val prescriberByRegex = Regex(
      """(?:PRESCRIBED[ \t]*BY|PRESCRIBER|DR\.?)[: \t]+([A-Za-z, \t.]{3,35})""",
      RegexOption.IGNORE_CASE,
    )
    var prescriberName = prescriberByRegex.find(fullText)?.groupValues?.getOrNull(1)
      ?.lineSequence()?.firstOrNull()?.trim()

    if (prescriberName == null) {
      val credentialRegex = Regex("""\b([A-Z][A-Za-z]+[ \t]+[A-Z][A-Za-z]+),[ \t]*(?:MD|DO|APN|NP|PA-C|PA)\b""")
      prescriberName = credentialRegex.find(fullText)?.groupValues?.getOrNull(1)?.trim()
    }
    if (prescriberName != null && prescriberName.contains(",")) {
      // Convert "Vacek, Jeffrey" -> "Dr. Jeffrey Vacek" if formatted as Last, First
      val parts = prescriberName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
      if (parts.size == 2 && !parts[1].uppercase().let { it in setOf("MD", "DO", "APN", "NP", "PA") }) {
        prescriberName = "${parts[1]} ${parts[0]}"
      }
    }

    // 5. Extract Lot Number ("LOT #: DPTSESKSLIEE" or "LOT 43336")
    val lotRegex = Regex("""\bLOT\s*#?[:\s]+([A-Z0-9-]{3,20})""", RegexOption.IGNORE_CASE)
    val lotNumber = lotRegex.find(fullText)?.groupValues?.getOrNull(1)?.trim()

    // 6. Extract Expiration Date ("EXP 10/2027", "EXP 03/28", "USE BY: 11/07/2026")
    val expRegex = Regex(
      """\b(?:EXP(?:IRES|IRATION)?|USE\s*BY)[:\s]*(\d{1,2}/\d{2,4}(?:/\d{2,4})?)""",
      RegexOption.IGNORE_CASE,
    )
    val rawExp = expRegex.find(fullText)?.groupValues?.getOrNull(1)?.trim()
    val expirationDateIso = rawExp?.let { normalizeLabelDateToIso(it) }

    // 7. Extract Instructions / Sig ("TAKE 1 TABLET BY MOUTH...", "Chew or swallow 1 tablet by mouth daily")
    val sigRegex = Regex(
      """^((?:TAKE|CHEW(?:\s+OR\s+SWALLOW)?|SWALLOW|INJECT|APPLY|INHALE|INSTILL|USE)\s+\d+.*)$""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    val instructions = sigRegex.find(fullText)?.groupValues?.getOrNull(1)?.trim()
    val doseAmtFromSig = instructions?.let {
      Regex("""(?:TAKE|CHEW(?:\s+OR\s+SWALLOW)?|SWALLOW|INJECT|APPLY)\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    // 8. Detect Dosage Form across the label
    val form = detectFormFromText(fullText)
    val defaultUnit = form?.let { defaultUnitForForm(it) }

    // 9. Detect Multi-Ingredient Compound or Combination Drug (e.g. XeCare compound or Advil Dual Action)
    val ingredientStrengthMatches = Regex(
      """([A-Za-z][A-Za-z0-9 -]{2,28}?)[ \t]*(?:\([A-Za-z \t]+\)[ \t]*)?(\d+(?:\.\d+)?[ \t]*(?:mg|mcg|g|mL|IU|%)(?:[ \t]*/[ \t]*\d*(?:\.\d+)?[ \t]*mL)?)""",
      RegexOption.IGNORE_CASE,
    ).findAll(fullText).toList()

    // Filter out non-drug matches like "ALLERGY 24", "QTY 12"
    val validIngredientPairs = ingredientStrengthMatches.mapNotNull { m ->
      val rawName = m.groupValues[1]
        .replace(Regex("""\b(?:CONTAINS|WITH|AND|TABLET|CHEWABLE|CAPSULE|CAP|TAB)\b""", RegexOption.IGNORE_CASE), "")
        .trim()
      val rawStr = normalizeStrengthSpacing(m.groupValues[2])
      val upperName = rawName.uppercase()
      if (rawName.length >= 3 &&
        !upperName.contains("TAKE ") &&
        !upperName.contains("CHEW ") &&
        !upperName.contains("QTY") &&
        !upperName.contains("REFILL") &&
        !upperName.contains("ALLERGY")
      ) {
        formatDrugTitle(rawName) to rawStr
      } else {
        null
      }
    }.distinctBy { it.first.lowercase() }

    val isMultiIngredientCompound = explicitCompound || validIngredientPairs.size >= 3

    // Check known OTC / Rx brands in text
    var detectedBrand: String? = null
    var knownGenericForBrand: String? = null
    val upperFull = fullText.uppercase()
    for ((brandKey, pair) in KNOWN_BRANDS) {
      if (upperFull.contains(brandKey)) {
        detectedBrand = pair.first
        knownGenericForBrand = pair.second
        break
      }
    }

    var formulaName: String? = null
    var strength: String? = null

    if (isMultiIngredientCompound && validIngredientPairs.isNotEmpty()) {
      // For compounded multi-ingredient formulas (like Finasteride 1.2mg / Minoxidil 3mg / Vitamin B5 11mg...):
      // Put the full ingredient + strength breakdown in formulaName and summary strength in strength
      formulaName = validIngredientPairs.joinToString(" / ") { (ing, _) -> ing }
      strength = validIngredientPairs.joinToString(" / ") { (_, str) -> str }
    } else if (validIngredientPairs.size == 2) {
      // Combination product like Advil Dual Action (Acetaminophen 250 mg / Ibuprofen 125 mg)
      formulaName = validIngredientPairs.joinToString(" / ") { it.first }
      strength = validIngredientPairs.joinToString(" / ") { it.second }
    } else if (validIngredientPairs.size == 1) {
      val (singleName, singleStr) = validIngredientPairs.first()
      // Check if singleName is actually the brand name
      if (detectedBrand != null && singleName.equals(detectedBrand, ignoreCase = true) && knownGenericForBrand != null) {
        formulaName = knownGenericForBrand
      } else {
        formulaName = singleName
      }
      strength = singleStr
    }

    // Fallback: look for standalone strength and drug name lines (e.g. Allegra where "fexofenadine HCl tablet" and "180 mg" are on separate lines)
    if (strength == null) {
      val standaloneStrength = Regex(
        """\b(\d+(?:\.\d+)?\s*(?:mg|mcg|g|mL|IU)(?:\s*/\s*\d*(?:\.\d+)?\s*mL)?)\b""",
        RegexOption.IGNORE_CASE,
      ).find(fullText)?.groupValues?.getOrNull(1)
      if (standaloneStrength != null) {
        strength = normalizeStrengthSpacing(standaloneStrength)
      }
    }

    if (formulaName == null) {
      // Look for lines containing HCl, Sulfate, Sodium, Dimesylate, Cypionate, or known generic
      val genericLineMatch = Regex(
        """\b([A-Za-z]{4,}(?:\s+(?:HCl|Hydrochloride|Sulfate|Sodium|Dimesylate|Cypionate|Enanthate|Propionate|Acetate| Tartrate|Succinate|Maleate|Mesylate|Besylate|Citrate))?)\s+(?:tablet|capsule|gelcap|injection|solution|cream|gel|ointment)s?\b""",
        RegexOption.IGNORE_CASE,
      ).find(fullText)
      formulaName = genericLineMatch?.groupValues?.getOrNull(1)?.let { formatDrugTitle(it) }
        ?: knownGenericForBrand
        ?: findCandidateDrugLine(lines)
    }

    return ParsedMedicationLabel(
      rawText = rawText,
      extractedNdc = extractedNdc,
      formulaName = formulaName,
      brandName = detectedBrand,
      strength = strength,
      form = form,
      defaultDoseUnit = defaultUnit,
      defaultDoseAmount = doseAmtFromSig,
      manufacturer = manufacturer,
      prescriberName = prescriberName,
      lotNumber = lotNumber,
      expirationDateIso = expirationDateIso,
      instructions = instructions,
      isCompound = isMultiIngredientCompound,
    )
  }

  private fun detectFormFromText(text: String): MedicationForm? {
    val upper = text.uppercase()
    return when {
      upper.contains("INJECTION") || upper.contains("VIAL") || upper.contains("INTRAMUSCULAR") ||
        upper.contains("SUBCUTANEOUS") -> MedicationForm.INJECTABLE_VIAL
      upper.contains("PEN") || upper.contains("AUTO-INJECTOR") -> MedicationForm.INJECTABLE_PEN
      upper.contains("TABLET") || upper.contains("CHEWABLE") || upper.contains("CAPLET") ||
        upper.contains("GELCAP") -> MedicationForm.TABLET
      upper.contains("CAPSULE") -> MedicationForm.CAPSULE
      upper.contains("CREAM") || upper.contains("OINTMENT") || upper.contains("TOPICAL") ||
        upper.contains("PATCH") || upper.contains("LOTION") -> MedicationForm.TOPICAL
      upper.contains("INHALER") || upper.contains("NASAL SPRAY") -> MedicationForm.INHALER
      upper.contains("OPHTHALMIC") || upper.contains("OTIC") || upper.contains("DROPS") -> MedicationForm.DROPS
      upper.contains("ORAL SOLUTION") || upper.contains("SYRUP") || upper.contains("SUSPENSION") ->
        MedicationForm.LIQUID
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

  private fun normalizeStrengthSpacing(raw: String): String {
    return raw.trim()
      .replace(Regex("""(\d)\s*(mg|mcg|g|mL|IU|%)""", RegexOption.IGNORE_CASE)) { m ->
        val unit = when (m.groupValues[2].lowercase()) {
          "mg" -> "mg"
          "mcg" -> "mcg"
          "g" -> "g"
          "ml" -> "mL"
          "iu" -> "IU"
          else -> m.groupValues[2]
        }
        "${m.groupValues[1]} $unit"
      }
  }

  private fun normalizeLabelDateToIso(rawDate: String): String? {
    val parts = rawDate.split("/").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size == 2) {
      // MM/YY or MM/YYYY
      val mm = parts[0].coerceIn(1, 12)
      val yy = if (parts[1] < 100) 2000 + parts[1] else parts[1]
      return String.format("%04d-%02d-28", yy, mm)
    }
    if (parts.size == 3) {
      // MM/DD/YY or MM/DD/YYYY
      val mm = parts[0].coerceIn(1, 12)
      val dd = parts[1].coerceIn(1, 31)
      val yy = if (parts[2] < 100) 2000 + parts[2] else parts[2]
      return String.format("%04d-%02d-%02d", yy, mm, dd)
    }
    return null
  }

  private fun findCandidateDrugLine(lines: List<String>): String? {
    return lines.firstOrNull { line ->
      line.length in 4..40 &&
        line.any { it.isLetter() } &&
        IGNORE_LINE_PATTERNS.none { it.containsMatchIn(line) } &&
        !line.startsWith("MFG", ignoreCase = true) &&
        !line.startsWith("LOT", ignoreCase = true) &&
        !line.startsWith("EXP", ignoreCase = true)
    }?.let { formatDrugTitle(it) }
  }

  private fun formatDrugTitle(raw: String): String {
    return raw.trim().split(Regex("\\s+")).joinToString(" ") { word ->
      val upper = word.uppercase()
      when {
        upper == "HCL" -> "HCl"
        upper in setOf("XR", "ER", "SR", "DR", "USP", "B5", "B6", "B12", "D3") -> upper
        else -> word.lowercase().replaceFirstChar { it.uppercase() }
      }
    }
  }
}
