package com.exaclast.regimen.data

import com.exaclast.regimen.model.DoseUnit
import com.exaclast.regimen.model.InjectionRoute
import com.exaclast.regimen.model.MedicationForm
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ParsedDrugBarcode(
  val rawBarcode: String,
  val extractedNdc: String,
  val gtin14: String? = null,
  val expirationDateIso: String? = null,
  val lotNumber: String? = null,
  val nonNdcHint: String? = null,
)

data class FdaDrugLookupResult(
  val ndc11: String,
  val formulaName: String,
  val brandName: String?,
  val manufacturer: String?,
  val strength: String,
  val form: MedicationForm,
  val defaultDoseUnit: DoseUnit,
  val route: InjectionRoute? = null,
  val concentrationMgPerMl: Double? = null,
  val vialVolumeMl: Double? = null,
  val packageDescription: String? = null,
  val expirationDateIso: String? = null,
  val lotNumber: String? = null,
)

/**
 * Input signals available from the form or from an OCR label scan to cross-reference against OpenFDA.
 */
data class DrugCrossReferenceCriteria(
  val ndc: String? = null,
  val formulaName: String? = null,
  val brandName: String? = null,
  val strength: String? = null,
  val form: MedicationForm? = null,
  val manufacturer: String? = null,
)

/**
 * A single OpenFDA candidate scored and cross-referenced against [DrugCrossReferenceCriteria].
 */
data class CrossReferencedFdaCandidate(
  val result: FdaDrugLookupResult,
  val score: Int,
  val matchedSignals: List<String>,
  val missingOrDifferentSignals: List<String>,
  val matchesName: Boolean,
  /** The name's words are the same, not just close: look-alike drugs differ by a letter or two. */
  val exactNameMatch: Boolean,
  val matchesStrength: Boolean,
  val matchesForm: Boolean,
  val matchesManufacturer: Boolean,
)

sealed class CrossReferenceOutcome {
  data class UnambiguousMatch(val candidate: CrossReferencedFdaCandidate) : CrossReferenceOutcome()
  data class NeedsUserSelection(
    val reason: String,
    val candidates: List<CrossReferencedFdaCandidate>,
  ) : CrossReferenceOutcome()
  data class NoMatch(val message: String) : CrossReferenceOutcome()
}

@Serializable
internal data class OpenFdaNdcResponse(
  val results: List<OpenFdaDrugItem> = emptyList(),
)

@Serializable
internal data class OpenFdaDrugItem(
  @SerialName("product_ndc") val productNdc: String? = null,
  @SerialName("generic_name") val genericName: String? = null,
  @SerialName("brand_name") val brandName: String? = null,
  @SerialName("labeler_name") val labelerName: String? = null,
  @SerialName("dosage_form") val dosageForm: String? = null,
  val route: List<String> = emptyList(),
  @SerialName("active_ingredients") val activeIngredients: List<OpenFdaIngredient> = emptyList(),
  val packaging: List<OpenFdaPackaging> = emptyList(),
)

@Serializable
internal data class OpenFdaIngredient(
  val name: String? = null,
  val strength: String? = null,
)

@Serializable
internal data class OpenFdaPackaging(
  @SerialName("package_ndc") val packageNdc: String? = null,
  val description: String? = null,
)

object FdaNdcLookupService {

  private val BARE_PER_ONE_REGEX = Regex("""/\s*1(?![\d.])(?!\s*[A-Za-z\[])""")

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * Parses a scanned 2D GS1 DataMatrix, GTIN-14, EAN-13, UPC-A, or raw NDC string
   * and extracts the underlying NDC (10-digit unhyphenated or 11-digit/hyphenated),
   * plus optional GS1 expiration date `(17)` and lot number `(10)`.
   */
  fun parseDrugBarcode(rawInput: String): ParsedDrugBarcode {
    // Strip AIM symbology identifiers like "]d2", "]C1", "]e0" and leading/trailing whitespace
    val cleaned = rawInput
      .trim()
      .replace(Regex("^\\][A-Za-z][0-9]"), "")
      .trim()

    // 0. Check if there is an explicit hyphenated NDC inside the string (e.g. "41167-4122-2")
    val hyphenatedNdcMatch = Regex("""\b(\d{4,5}-\d{3,4}-\d{1,2})\b""").find(cleaned)
    if (hyphenatedNdcMatch != null) {
      return ParsedDrugBarcode(
        rawBarcode = rawInput,
        extractedNdc = hyphenatedNdcMatch.groupValues[1],
      )
    }

    // 1. Check parenthesized GS1 human-readable / scanner format: e.g. (01)00300090085106(17)271231(10)LOT123
    val parenGtinMatch = Regex("""\(01\)\s*(\d{14})""").find(cleaned)
    if (parenGtinMatch != null) {
      val gtin14 = parenGtinMatch.groupValues[1]
      val ndc10 = extractNdc10FromGtin14(gtin14) ?: gtin14
      val exp = Regex("""\(17\)\s*(\d{6})""").find(cleaned)?.groupValues?.getOrNull(1)?.let {
        parseGs1DateToIso(it)
      }
      val lot = Regex("""\(10\)\s*([^\(\u001D\s]+)""").find(cleaned)?.groupValues?.getOrNull(1)
      return ParsedDrugBarcode(
        rawBarcode = rawInput,
        extractedNdc = ndc10,
        gtin14 = gtin14,
        expirationDateIso = exp,
        lotNumber = lot,
      )
    }

    // 2. Check raw GS1 AI stream starting with "01" + 14-digit GTIN (e.g. 01003000900851061727123110LOT123)
    val rawGs1Match = Regex("""^(?:\u001D)?01(\d{14})(.*)$""").find(cleaned)
    if (rawGs1Match != null) {
      val gtin14 = rawGs1Match.groupValues[1]
      val remainder = rawGs1Match.groupValues[2]
      val ndc10 = extractNdc10FromGtin14(gtin14)
      if (ndc10 != null) {
        var expIso: String? = null
        var lot: String? = null
        val expMatch = Regex("""17(\d{6})""").find(remainder)
        if (expMatch != null) {
          expIso = parseGs1DateToIso(expMatch.groupValues[1])
          val afterExp = remainder.substring(expMatch.range.last + 1).trimStart('\u001D')
          if (afterExp.startsWith("10") && afterExp.length > 2) {
            lot = afterExp.substring(2).substringBefore('\u001D').take(20)
          }
        }
        return ParsedDrugBarcode(
          rawBarcode = rawInput,
          extractedNdc = ndc10,
          gtin14 = gtin14,
          expirationDateIso = expIso,
          lotNumber = lot,
        )
      }
    }

    val digitsOnly = cleaned.filter { it.isDigit() }
    val hasHyphens = cleaned.contains("-")

    if (!hasHyphens) {
      // 3. Pure 14-digit GTIN-14 (e.g. 00300090085106)
      if (digitsOnly.length == 14) {
        extractNdc10FromGtin14(digitsOnly)?.let { ndc10 ->
          return ParsedDrugBarcode(
            rawBarcode = rawInput,
            extractedNdc = ndc10,
            gtin14 = digitsOnly,
          )
        }
      }
      // 4. 13-digit EAN-13 starting with "03" (03 + 10-digit NDC + check digit)
      if (digitsOnly.length == 13 && digitsOnly.startsWith("03")) {
        return ParsedDrugBarcode(
          rawBarcode = rawInput,
          extractedNdc = digitsOnly.substring(2, 12),
        )
      }
      // 5. 12-digit UPC-A starting with "3" (3 + 10-digit NDC + check digit)
      if (digitsOnly.length == 12 && digitsOnly.startsWith("3")) {
        return ParsedDrugBarcode(
          rawBarcode = rawInput,
          extractedNdc = digitsOnly.substring(1, 11),
        )
      }

      // 6. Detect short packaging/artwork codes (e.g. 6-digit "824208" on OTC label corners)
      if (digitsOnly.length in 1..9) {
        return ParsedDrugBarcode(
          rawBarcode = rawInput,
          extractedNdc = cleaned,
          nonNdcHint = "Scanned code '$cleaned' is an internal packaging/lot code (${digitsOnly.length} digits), not an FDA NDC. Tap 'Scan Label' to read the printed drug text instead.",
        )
      }

      // 7. Detect pharmacy internal Rx refill barcodes (12+ digits not starting with US drug prefix 3 / 03)
      if (digitsOnly.length >= 12) {
        return ParsedDrugBarcode(
          rawBarcode = rawInput,
          extractedNdc = digitsOnly.take(11),
          nonNdcHint = "Pharmacy vial barcode ('$cleaned') encodes a store prescription refill number rather than an FDA NDC. Tap 'Scan Label' to read the printed medication text.",
        )
      }
    }

    return ParsedDrugBarcode(
      rawBarcode = rawInput,
      extractedNdc = cleaned,
    )
  }

  private fun extractNdc10FromGtin14(gtin14: String): String? {
    if (gtin14.length != 14) return null
    return if (gtin14.substring(1, 3) == "03") {
      gtin14.substring(3, 13)
    } else {
      null
    }
  }

  private fun parseGs1DateToIso(yymmdd: String): String? {
    if (yymmdd.length != 6) return null
    val yy = yymmdd.substring(0, 2).toIntOrNull() ?: return null
    val mm = yymmdd.substring(2, 4).toIntOrNull() ?: return null
    val dd = yymmdd.substring(4, 6).toIntOrNull() ?: return null
    if (mm !in 1..12) return null
    val year = 2000 + yy
    val day = if (dd == 0) 28 else dd.coerceIn(1, 31)
    return String.format("%04d-%02d-%02d", year, mm, day)
  }

  /**
   * Returns all possible canonical 11-digit NDCs (`XXXXX-XXXX-XX`) for a given input.
   * For an unhyphenated 10-digit NDC (from a barcode), returns all 3 zero-padded possibilities
   * (`0XXXX-XXXX-XX`, `XXXXX-0XXX-XX`, `XXXXX-XXXX-0X`).
   */
  fun candidateNdc11s(rawInput: String): List<String> {
    val parsed = parseDrugBarcode(rawInput)
    val target = parsed.extractedNdc.trim()
    val digits = target.filter { it.isDigit() }
    if (!target.contains("-") && digits.length == 10) {
      val c442 = "0${digits.substring(0, 4)}-${digits.substring(4, 8)}-${digits.substring(8, 10)}"
      val c532 = "${digits.substring(0, 5)}-0${digits.substring(5, 8)}-${digits.substring(8, 10)}"
      val c541 = "${digits.substring(0, 5)}-${digits.substring(5, 9)}-0${digits.substring(9, 10)}"
      return listOf(c442, c532, c541).distinct()
    }
    return listOf(toCanonicalNdc11(target))
  }

  /**
   * Normalizes a 10-digit hyphenated NDC (4-4-2, 5-3-2, 5-4-1), raw 11-digit string,
   * or barcode string into canonical HIPAA 11-digit format (XXXXX-XXXX-XX).
   */
  fun toCanonicalNdc11(raw: String): String {
    val parsed = parseDrugBarcode(raw)
    val trimmed = parsed.extractedNdc.trim()
    val parts = trimmed.split("-").filter { it.isNotEmpty() }
    if (parts.size == 3) {
      val s1 = parts[0].filter { it.isDigit() }
      val s2 = parts[1].filter { it.isDigit() }
      val s3 = parts[2].filter { it.isDigit() }
      return when {
        s1.length == 5 && s2.length == 4 && s3.length == 2 -> "$s1-$s2-$s3"
        s1.length == 4 && s2.length == 4 && s3.length == 2 -> "0$s1-$s2-$s3"
        s1.length == 5 && s2.length == 3 && s3.length == 2 -> "$s1-0$s2-$s3"
        s1.length == 5 && s2.length == 4 && s3.length == 1 -> "$s1-$s2-0$s3"
        else -> trimmed
      }
    }
    val digits = trimmed.filter { it.isDigit() }
    return when (digits.length) {
      11 -> "${digits.substring(0, 5)}-${digits.substring(5, 9)}-${digits.substring(9, 11)}"
      10 -> "0${digits.substring(0, 4)}-${digits.substring(4, 8)}-${digits.substring(8, 10)}"
      else -> trimmed
    }
  }

  /**
   * Generates candidate OpenFDA `package_ndc` and `product_ndc` search tokens from an NDC-11,
   * hyphenated NDC, or scanned barcode (GS1 DataMatrix / UPC-A / 10-digit unhyphenated NDC).
   */
  fun buildOpenFdaSearchQuery(rawNdc: String, includeProducts: Boolean = true): String {
    val parsed = parseDrugBarcode(rawNdc)
    val rawClean = parsed.extractedNdc.trim()
    val packageCandidates = linkedSetOf<String>()
    val productCandidates = linkedSetOf<String>()

    if (rawClean.contains("-")) {
      packageCandidates.add(rawClean)
      val rawSegs = rawClean.split("-")
      if (rawSegs.size >= 2) {
        productCandidates.add("${rawSegs[0]}-${rawSegs[1]}")
      }
    }

    for (canonical in candidateNdc11s(rawNdc)) {
      val parts = canonical.split("-")
      if (parts.size == 3 && parts[0].length == 5 && parts[1].length == 4 && parts[2].length == 2) {
        val (s1, s2, s3) = parts
        packageCandidates.add("$s1-$s2-$s3")
        productCandidates.add("$s1-$s2")
        if (s1.startsWith("0")) {
          val u1 = s1.substring(1)
          packageCandidates.add("$u1-$s2-$s3")
          productCandidates.add("$u1-$s2")
        }
        if (s2.startsWith("0")) {
          val u2 = s2.substring(1)
          packageCandidates.add("$s1-$u2-$s3")
          productCandidates.add("$s1-$u2")
        }
        if (s3.startsWith("0")) {
          val u3 = s3.substring(1)
          packageCandidates.add("$s1-$s2-$u3")
        }
      }
    }

    val clauses = mutableListOf<String>()
    packageCandidates.forEach { clauses.add("packaging.package_ndc:\"$it\"") }
    if (includeProducts) productCandidates.forEach { clauses.add("product_ndc:\"$it\"") }
    return clauses.joinToString("+OR+")
  }

  /**
   * Queries OpenFDA `/drug/ndc.json` for the given NDC-11, 10-digit NDC, or scanned barcode.
   */
  suspend fun lookupByNdc(rawNdc: String): Result<FdaDrugLookupResult> = withContext(Dispatchers.IO) {
    runCatching {
      val parsedBarcode = parseDrugBarcode(rawNdc)
      val candidates = candidateNdc11s(rawNdc)
      val primaryNdc11 = candidates.first()
      val digitsOnly = primaryNdc11.filter { it.isDigit() }
      if (digitsOnly.length < 10) {
        throw IllegalArgumentException(
          parsedBarcode.nonNdcHint
            ?: "Please enter a valid 10- or 11-digit NDC (or tap 'Scan Label' to read printed bottle text)."
        )
      }

      // 1. Exact package: a bare 10-digit NDC only has three layouts (4-4-2, 5-3-2, 5-4-1), and the
      //    FDA keeps the 10 digits unique across layouts, so at most one of them is a real package.
      // 2. Only if no package matches (e.g. a size the FDA no longer lists), widen to the product
      //    and keep the entered package code.
      for (includeProducts in listOf(false, true)) {
        val responseBody = runCatching {
          fetchHttpUrl(ndcSearchUrl(buildOpenFdaSearchQuery(rawNdc, includeProducts)))
        }.getOrNull() ?: continue
        val parsed = parseOpenFdaJson(responseBody, candidates) ?: continue
        return@runCatching parsed.copy(
          expirationDateIso = parsedBarcode.expirationDateIso,
          lotNumber = parsedBarcode.lotNumber,
        )
      }

      throw IllegalStateException(
        parsedBarcode.nonNdcHint
          ?: "No matching FDA NDC record found for $primaryNdc11. Tap 'Scan Label' to read the printed drug name & strength."
      )
    }
  }

  private fun ndcSearchUrl(searchClause: String): String {
    val encodedSearch = URLEncoder.encode(searchClause, "UTF-8")
      .replace("%2B", "+")
      .replace("%3A", ":")
      .replace("%22", "\"")
    return "https://api.fda.gov/drug/ndc.json?search=$encodedSearch&limit=10"
  }

  /**
   * Cross-references all available medication data ([DrugCrossReferenceCriteria]) against OpenFDA
   * without making blind assumptions when data is limited or multiple matches exist.
   *
   * Priority 1: If an NDC-11 (or 10-digit NDC) was detected via OCR or entered, query it first.
   *   - If it resolves cleanly in OpenFDA (and does not conflict with another explicitly parsed drug name),
   *     it is sufficient on its own for an immediate [CrossReferenceOutcome.UnambiguousMatch].
   * Priority 2: If no NDC was detected or if the NDC lookup was inconclusive, cross-reference
   *   [DrugCrossReferenceCriteria.formulaName], [DrugCrossReferenceCriteria.brandName],
   *   [DrugCrossReferenceCriteria.strength], [DrugCrossReferenceCriteria.form], and
   *   [DrugCrossReferenceCriteria.manufacturer].
   */
  suspend fun searchAndCrossReference(
    criteria: DrugCrossReferenceCriteria,
  ): CrossReferenceOutcome = withContext(Dispatchers.IO) {
    // Priority 1: Check explicit NDC first if available
    if (!criteria.ndc.isNullOrBlank()) {
      val ndcResult = lookupByNdc(criteria.ndc).getOrNull()
      if (ndcResult != null) {
        val scored = scoreCandidateAgainstCriteria(ndcResult, criteria)
        if (ndcMatchIsConclusive(scored, criteria)) {
          val withNdcSignal = scored.copy(
            score = scored.score + 100,
            matchedSignals = listOf("NDC (${ndcResult.ndc11})") + scored.matchedSignals,
          )
          return@withContext CrossReferenceOutcome.UnambiguousMatch(withNdcSignal)
        }
      }
    }

    val nameQuery = criteria.formulaName?.takeIf { it.isNotBlank() }
      ?: criteria.brandName?.takeIf { it.isNotBlank() }
      ?: return@withContext CrossReferenceOutcome.NoMatch(
        if (!criteria.ndc.isNullOrBlank()) {
          "No matching FDA record found for NDC '${criteria.ndc}', and no drug name was provided to cross-reference."
        } else {
          "Scan a medication label or enter a Brand / Formula Name (or NDC-11) to cross-reference against OpenFDA."
        }
      )

    // Build OpenFDA search query across generic_name, brand_name, and active_ingredients.name
    val rawPrimaryToken = nameQuery
      .split("/", ",", "(", " ")
      .map { it.trim() }
      .firstOrNull { it.length >= 3 && !it.equals("HCl", ignoreCase = true) }
      ?: nameQuery.trim()
    val deobfuscatedPrimary = DrugLabelParser.deobfuscateOcrWord(rawPrimaryToken)

    val searchClauses = mutableListOf<String>()
    val cleanTokens = listOf(deobfuscatedPrimary, rawPrimaryToken)
      .map { it.replace("\"", "").trim() }
      .filter { it.isNotEmpty() }
      .distinctBy { it.lowercase() }

    for (token in cleanTokens) {
      searchClauses.add("generic_name:\"$token\"")
      searchClauses.add("brand_name:\"$token\"")
      searchClauses.add("active_ingredients.name:\"$token\"")
    }

    // Add a 5-character stem wildcard clause so 1-2 character OCR/typing errors in the middle/end
    // of a drug name still retrieve the candidate family from OpenFDA for strict cross-referencing
    val alphaStem = deobfuscatedPrimary.filter { it.isLetter() }.lowercase()
    if (alphaStem.length >= 5) {
      val stem5 = alphaStem.take(5)
      searchClauses.add("generic_name:${stem5}*")
      searchClauses.add("brand_name:${stem5}*")
      searchClauses.add("active_ingredients.name:${stem5}*")
    }

    if (!criteria.brandName.isNullOrBlank() && !criteria.brandName.equals(rawPrimaryToken, ignoreCase = true)) {
      val bToken = DrugLabelParser.deobfuscateOcrWord(criteria.brandName.trim()).replace("\"", "")
      searchClauses.add("brand_name:\"$bToken\"")
    }

    val rawSearch = searchClauses.distinct().joinToString("+OR+")
    val encodedSearch = URLEncoder.encode(rawSearch, "UTF-8")
      .replace("%2B", "+")
      .replace("%3A", ":")
      .replace("%22", "\"")
      .replace("%2A", "*")
    val urlString = "https://api.fda.gov/drug/ndc.json?search=$encodedSearch&limit=40"

    val apiResults = mutableListOf<FdaDrugLookupResult>()
    val responseBody = runCatching { fetchHttpUrl(urlString) }.getOrNull()
    if (responseBody != null) {
      apiResults.addAll(parseAllOpenFdaItems(responseBody))
    }

    val scoredCandidates = scoreAndFilterCandidates(apiResults, criteria)
    if (scoredCandidates.isEmpty()) {
      return@withContext CrossReferenceOutcome.NoMatch(
        "No OpenFDA records matched '$nameQuery'${criteria.strength?.let { " ($it)" } ?: ""}."
      )
    }

    // Decide whether we have 1 unambiguous match across all known criteria or if we MUST ask the user
    return@withContext evaluateDisambiguation(scoredCandidates, criteria)
  }

  /**
   * An NDC record settles the lookup only if the name and strength also entered agree with it. A
   * misread NDC digit commonly lands on a sibling strength of the same product, or another drug.
   */
  internal fun ndcMatchIsConclusive(
    scored: CrossReferencedFdaCandidate,
    criteria: DrugCrossReferenceCriteria,
  ): Boolean {
    val nameEntered = !criteria.formulaName.isNullOrBlank() || !criteria.brandName.isNullOrBlank()
    val strengthConflict = !criteria.strength.isNullOrBlank() && !scored.matchesStrength
    return (!nameEntered || scored.exactNameMatch) && !strengthConflict
  }

  internal fun scoreAndFilterCandidates(
    rawResults: List<FdaDrugLookupResult>,
    criteria: DrugCrossReferenceCriteria,
  ): List<CrossReferencedFdaCandidate> {
    val hasStrengthCriterion = !criteria.strength.isNullOrBlank()
    val scored = rawResults
      .map { scoreCandidateAgainstCriteria(it, criteria) }
      .filter { it.matchesName }
      .filter { candidate ->
        // Never include a candidate that explicitly contradicts a provided strength
        // unless zero candidates matched the strength.
        !hasStrengthCriterion || candidate.matchesStrength
      }
      .sortedByDescending { it.score }
      // An exact name rules out look-alikes (prednisone vs. prednisolone).
      .let { matches -> if (matches.any { it.exactNameMatch }) matches.filter { it.exactNameMatch } else matches }

    // Deduplicate identical (formulaName, brandName, strength, form, manufacturer) combinations
    return scored.distinctBy {
      listOf(
        it.result.formulaName.lowercase(),
        it.result.brandName?.lowercase().orEmpty(),
        normalizeStrengthNumbers(it.result.strength),
        it.result.form.name,
        it.result.manufacturer?.lowercase().orEmpty(),
      )
    }
  }

  internal fun evaluateDisambiguation(
    candidates: List<CrossReferencedFdaCandidate>,
    criteria: DrugCrossReferenceCriteria,
  ): CrossReferenceOutcome {
    if (candidates.isEmpty()) {
      return CrossReferenceOutcome.NoMatch("No matching FDA records found.")
    }

    val hasStrength = !criteria.strength.isNullOrBlank()
    val hasMfg = !criteria.manufacturer.isNullOrBlank()

    // Check if multiple distinct strengths exist among candidates
    val distinctStrengths = candidates.map { normalizeStrengthNumbers(it.result.strength) }.distinct()
    // Check if multiple distinct manufacturers/labelers exist among top candidates
    val mfgMatchedSubset = if (hasMfg) {
      candidates.filter { it.matchesManufacturer }
    } else {
      emptyList()
    }

    val effectivePool = if (mfgMatchedSubset.isNotEmpty()) mfgMatchedSubset else candidates

    // Only auto-select a single match when:
    // 1. Effective pool has 1 unique product AND
    // 2. If OpenFDA has multiple strengths, the user/label provided the strength AND it matched!
    if (effectivePool.size == 1 && (hasStrength || distinctStrengths.size == 1)) {
      val single = effectivePool.first()
      // A close name may be a different drug, so it's never filled in without asking.
      if (!single.exactNameMatch) {
        return CrossReferenceOutcome.NeedsUserSelection(
          reason = "This is the closest FDA record, but its name differs from what you entered. Check it's the same medication:",
          candidates = effectivePool,
        )
      }
      // If manufacturer was NOT provided and there were other manufacturers in `candidates`, do not guess the manufacturer!
      if (!hasMfg && candidates.size > 1) {
        return CrossReferenceOutcome.NeedsUserSelection(
          reason = "Multiple manufacturers/packages match ${single.result.formulaName} (${single.result.strength}). Select your exact package or apply without a specific manufacturer NDC:",
          candidates = candidates.take(10),
        )
      }
      return CrossReferenceOutcome.UnambiguousMatch(single)
    }

    val reason = when {
      !hasStrength && distinctStrengths.size > 1 ->
        "Multiple strengths exist for this medication (${distinctStrengths.take(4).joinToString(", ")}). Select the exact strength & package:"
      hasMfg && mfgMatchedSubset.size > 1 ->
        "Multiple packages from ${criteria.manufacturer} matched. Select the exact match:"
      else ->
        "Found ${candidates.size} matching FDA records across different manufacturers/strengths. Select the exact match:"
    }

    return CrossReferenceOutcome.NeedsUserSelection(
      reason = reason,
      candidates = effectivePool.ifEmpty { candidates }.take(10),
    )
  }

  internal fun scoreCandidateAgainstCriteria(
    candidate: FdaDrugLookupResult,
    criteria: DrugCrossReferenceCriteria,
  ): CrossReferencedFdaCandidate {
    var score = 0
    val matched = mutableListOf<String>()
    val missingOrDiff = mutableListOf<String>()

    // 1. Name cross-reference (Formula / Generic & Brand) with OCR-tolerant fuzzy matching
    val qFormula = criteria.formulaName?.trim()?.lowercase().orEmpty()
    val qBrand = criteria.brandName?.trim()?.lowercase().orEmpty()
    val cFormula = candidate.formulaName.lowercase()
    val cBrand = candidate.brandName?.lowercase().orEmpty()

    var matchesName = false
    var exactNameMatch = false
    if (qFormula.isNotEmpty()) {
      val qTokens = extractAlphaTokens(qFormula)
      val cTokens = extractAlphaTokens(cFormula) + extractAlphaTokens(cBrand)
      if (qTokens.isNotEmpty() && qTokens.all { qt -> cTokens.any { ct -> tokensMatchFuzzy(qt, ct) } }) {
        matchesName = true
        val exactMatch = qTokens.all { qt -> qt in cTokens }
        exactNameMatch = exactMatch
        score += if (exactMatch) 40 else 34
        matched.add(
          if (exactMatch) {
            "Formula (${candidate.formulaName})"
          } else {
            "Formula (${candidate.formulaName}, fuzzy match)"
          }
        )
      }
    }
    if (qBrand.isNotEmpty()) {
      val qBrandTokens = extractAlphaTokens(qBrand)
      val cBrandTokens = extractAlphaTokens(cBrand) + extractAlphaTokens(cFormula)
      val exactBrand = cBrand.contains(qBrand) ||
        qBrand.contains(cBrand.takeIf { it.isNotEmpty() } ?: "___") ||
        cFormula.contains(qBrand)
      val fuzzyBrand = qBrandTokens.isNotEmpty() &&
        qBrandTokens.all { qt -> cBrandTokens.any { ct -> tokensMatchFuzzy(qt, ct) } }
      if (exactBrand || fuzzyBrand) {
        matchesName = true
        if (qBrandTokens.isNotEmpty() && qBrandTokens.all { qt -> qt in cBrandTokens }) exactNameMatch = true
        score += if (exactBrand) 35 else 30
        matched.add("Brand (${candidate.brandName ?: candidate.formulaName})")
      } else {
        missingOrDiff.add("Brand differs (${candidate.brandName ?: "Generic"})")
      }
    }

    if (matchesName && !exactNameMatch) {
      missingOrDiff.add("Name differs from what you entered (${candidate.formulaName})")
    }

    // 2. Strength cross-reference
    val qStrength = criteria.strength?.trim().orEmpty()
    var matchesStrength = false
    if (qStrength.isNotEmpty()) {
      // Numeric comparison only — substring matching would equate 5 mg with 0.5 / 2.5 / 25 mg.
      if (DrugStrength.equivalent(qStrength, candidate.strength)) {
        matchesStrength = true
        score += 45
        matched.add("Strength (${candidate.strength})")
      } else {
        score -= 40
        missingOrDiff.add("Strength is ${candidate.strength} (wanted $qStrength)")
      }
    } else {
      missingOrDiff.add("Strength not specified (${candidate.strength})")
    }

    // 3. Dosage Form cross-reference
    var matchesForm = false
    if (criteria.form != null) {
      if (candidate.form == criteria.form) {
        matchesForm = true
        score += 20
        matched.add("Form (${candidate.form.displayName})")
      } else {
        score -= 15
        missingOrDiff.add("Form is ${candidate.form.displayName}")
      }
    }

    // 4. Manufacturer / Labeler cross-reference (with OCR-tolerant fuzzy matching)
    val qMfg = criteria.manufacturer?.trim().orEmpty()
    var matchesMfg = false
    if (qMfg.isNotEmpty()) {
      val qMfgTokens = extractAlphaTokens(qMfg.lowercase()).filter {
        it !in setOf("inc", "llc", "ltd", "corp", "company", "pharmaceuticals", "pharma", "usa", "mfg")
      }
      val cMfgTokens = extractAlphaTokens(candidate.manufacturer?.lowercase().orEmpty()).filter {
        it !in setOf("inc", "llc", "ltd", "corp", "company", "pharmaceuticals", "pharma", "usa", "mfg")
      }
      if (qMfgTokens.isNotEmpty() && qMfgTokens.any { qt -> cMfgTokens.any { ct -> tokensMatchFuzzy(qt, ct) } }) {
        matchesMfg = true
        score += 45
        matched.add("Mfg (${candidate.manufacturer})")
      } else {
        missingOrDiff.add("Mfg: ${candidate.manufacturer ?: "Unknown"}")
      }
    }

    return CrossReferencedFdaCandidate(
      result = candidate,
      score = score,
      matchedSignals = matched,
      missingOrDifferentSignals = missingOrDiff,
      matchesName = matchesName,
      exactNameMatch = exactNameMatch,
      matchesStrength = matchesStrength,
      matchesForm = matchesForm,
      matchesManufacturer = matchesMfg,
    )
  }

  private fun extractAlphaTokens(input: String): List<String> {
    return Regex("""[a-z0-9]{3,}""")
      .findAll(input.lowercase())
      .map { DrugLabelParser.deobfuscateOcrWord(it.value).lowercase() }
      .filter { it !in setOf("hcl", "hydrochloride", "sulfate", "sodium", "tablet", "tablets", "capsule", "capsules") }
      .toList()
  }

  /**
   * OCR-tolerant token matcher:
   * - Normalizes OCR lookalike digits (`0`->`o`, `1`->`i`, `5`->`s`, `8`->`b`, `2`->`z`) and `l`/`i` equivalence.
   * - Accepts exact or substring matches (for tokens >= 4 chars).
   * - Accepts Levenshtein edit distance <= 1 for 5..7 char tokens, and <= 2 for 8+ char tokens
   *   (e.g., "amoxicill1n" or "amoxicilin" -> "amoxicillin").
   */
  internal fun tokensMatchFuzzy(queryToken: String, candidateToken: String): Boolean {
    val q = DrugLabelParser.deobfuscateOcrWord(queryToken).lowercase()
    val c = DrugLabelParser.deobfuscateOcrWord(candidateToken).lowercase()
    if (q == c) return true
    if (q.length >= 4 && c.length >= 4 && (c.contains(q) || q.contains(c))) return true

    // Normalize l/i confusions for edit distance comparison
    val qNorm = q.replace('l', 'i')
    val cNorm = c.replace('l', 'i')
    if (qNorm == cNorm) return true

    val minLen = minOf(qNorm.length, cNorm.length)
    val maxAllowedDist = when {
      minLen >= 8 -> 2
      minLen >= 5 -> 1
      else -> 0
    }
    if (maxAllowedDist == 0) return false
    if (kotlin.math.abs(qNorm.length - cNorm.length) > maxAllowedDist) return false
    return levenshteinDistance(qNorm, cNorm) <= maxAllowedDist
  }

  internal fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)

    for (i in a.indices) {
      curr[0] = i + 1
      for (j in b.indices) {
        val cost = if (a[i] == b[j]) 0 else 1
        curr[j + 1] = minOf(
          curr[j] + 1,
          prev[j + 1] + 1,
          prev[j] + cost,
        )
      }
      val tmp = prev
      prev = curr
      curr = tmp
    }
    return prev[b.length]
  }

  private fun normalizeStrengthNumbers(raw: String): String {
    return raw.lowercase()
      .replace(" ", "")
      .replace("/1ml", "/ml")
      .trim()
  }

  internal fun parseAllOpenFdaItems(rawJson: String): List<FdaDrugLookupResult> {
    val response = runCatching { json.decodeFromString<OpenFdaNdcResponse>(rawJson) }.getOrNull()
      ?: return emptyList()
    return response.results.mapNotNull { item ->
      val pkgNdc = item.packaging.firstOrNull()?.packageNdc
      val fallbackNdc = pkgNdc?.let { toCanonicalNdc11(it) }
        ?: item.productNdc?.let { "${it}-01" }
        ?: "00000-0000-00"
      mapOpenFdaItemToResult(item, fallbackNdc)
    }
  }

  /**
   * Maps an NDC search response to the entered package. [requestedNdc11s] are the canonical
   * NDC-11 readings of the input (one for an 11-digit/hyphenated NDC, up to three for a bare
   * 10-digit one). A product usually has several packages (bottle sizes); the result must be the
   * package that was entered, not whichever the FDA lists first.
   */
  internal fun parseOpenFdaJson(rawJson: String, requestedNdc11s: List<String>): FdaDrugLookupResult? {
    val response = runCatching { json.decodeFromString<OpenFdaNdcResponse>(rawJson) }.getOrNull()
      ?: return null
    val requested = requestedNdc11s.map { toCanonicalNdc11(it) }.toSet()
    val requestedProducts = requested.map { productPartOf(it) }.toSet()
    val items = response.results
    val item = items.firstOrNull { it -> it.packaging.any { pkg -> pkg.canonicalNdc11() in requested } }
      ?: items.firstOrNull { productPartOfItem(it) in requestedProducts }
      ?: items.firstOrNull()
      ?: return null
    return mapOpenFdaItemToResult(item, requestedNdc11s.firstOrNull() ?: "00000-0000-00", requested)
  }

  /** "45802-0112-22" -> "45802-0112" */
  private fun productPartOf(canonicalNdc11: String): String = canonicalNdc11.substringBeforeLast('-')

  private fun OpenFdaPackaging.canonicalNdc11(): String? = packageNdc?.let { toCanonicalNdc11(it) }

  /** The item's canonical 5-4 product code, from a package (always 3-segment) or the product NDC. */
  private fun productPartOfItem(item: OpenFdaDrugItem): String? {
    item.packaging.firstNotNullOfOrNull { it.canonicalNdc11() }
      ?.takeIf { it.length == 13 }
      ?.let { return productPartOf(it) }
    return item.productNdc?.let { toCanonicalNdc11("$it-00") }?.takeIf { it.length == 13 }?.let { productPartOf(it) }
  }

  private fun mapOpenFdaItemToResult(
    item: OpenFdaDrugItem,
    fallbackCanonicalNdc11: String,
    requestedNdc11s: Set<String> = emptySet(),
  ): FdaDrugLookupResult? {
    val rawGeneric = item.genericName?.takeIf { it.isNotBlank() }
      ?: item.activeIngredients.mapNotNull { it.name }.joinToString(" / ").takeIf { it.isNotBlank() }
      ?: item.brandName
      ?: return null

    // 1. The entered package itself. 2. The product matched but this package isn't listed (e.g.
    // discontinued size): keep the entered NDC in the layout that matches the product.
    // 3. No NDC was entered (name search): the first listed package.
    val matchedPackage = item.packaging.firstOrNull { it.canonicalNdc11() in requestedNdc11s }
    val itemProduct = productPartOfItem(item)
    val enteredForProduct = requestedNdc11s.firstOrNull { productPartOf(it) == itemProduct }
    val chosenPackage = matchedPackage ?: if (enteredForProduct == null) item.packaging.firstOrNull() else null
    val resolvedNdc11 = matchedPackage?.canonicalNdc11()
      ?: enteredForProduct
      ?: chosenPackage?.canonicalNdc11()
      ?: fallbackCanonicalNdc11

    val formulaName = formatTitleCase(rawGeneric)
    val brandName = item.brandName?.takeIf { it.isNotBlank() }?.let { formatTitleCase(it) }
    val manufacturer = item.labelerName?.takeIf { it.isNotBlank() }

    val rawStrength = item.activeIngredients
      .mapNotNull { it.strength?.trim() }
      .filter { it.isNotEmpty() }
      .joinToString(" / ")
      .let { normalizeStrengthText(it) }
      .ifBlank { "See package" }

    val packageDesc = chosenPackage?.description
    val form = mapDosageForm(item.dosageForm, packageDesc, item.route)
    val route = mapRoute(item.route, form)
    val defaultUnit = when {
      form.isInjectable -> DoseUnit.MG
      form == MedicationForm.TABLET -> DoseUnit.TABLET
      form == MedicationForm.CAPSULE -> DoseUnit.CAPSULE
      form == MedicationForm.TOPICAL -> DoseUnit.APPLICATION
      form == MedicationForm.LIQUID -> DoseUnit.ML
      form == MedicationForm.INHALER -> DoseUnit.PUFF
      form == MedicationForm.DROPS -> DoseUnit.DROP
      else -> DoseUnit.MG
    }

    val concentrationMgPerMl = parseConcentrationMgPerMl(rawStrength)
    val vialVolumeMl = parseVialVolumeMl(packageDesc)

    return FdaDrugLookupResult(
      ndc11 = resolvedNdc11,
      formulaName = formulaName,
      brandName = brandName,
      manufacturer = manufacturer,
      strength = rawStrength,
      form = form,
      defaultDoseUnit = defaultUnit,
      route = route,
      concentrationMgPerMl = concentrationMgPerMl,
      vialVolumeMl = vialVolumeMl,
      packageDescription = packageDesc,
    )
  }

  private fun fetchHttpUrl(urlString: String): String {
    val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 8_000
      readTimeout = 8_000
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", "RegimenAndroid/1.0")
    }
    try {
      val code = connection.responseCode
      if (code in 200..299) {
        return connection.inputStream.bufferedReader().use { it.readText() }
      } else {
        throw IllegalStateException("HTTP $code from OpenFDA API")
      }
    } finally {
      connection.disconnect()
    }
  }

  /**
   * FDA dosage forms describe the drug ("INJECTION, SOLUTION"), not the device. Pens,
   * auto-injectors and prefilled syringes only show up in the packaging container type
   * ("3 mL in 1 SYRINGE, PLASTIC"), while vials say "VIAL". Some devices are listed as a KIT.
   */
  internal fun mapDosageForm(
    rawDosageForm: String?,
    packageDescription: String? = null,
    routes: List<String> = emptyList(),
  ): MedicationForm {
    val upper = rawDosageForm?.uppercase().orEmpty()
    // Some injectables are listed as a plain "SOLUTION" (e.g. Enbrel); the route gives them away.
    val parenteral = routes.any { r -> PARENTERAL_ROUTES.any { r.uppercase().contains(it) } }
    val injectable = upper.contains("INJECTION") || upper.contains("INJECTABLE") || parenteral
    return when {
      injectable || upper == "KIT" -> when (injectionDevice(packageDescription)) {
        MedicationForm.INJECTABLE_PEN -> MedicationForm.INJECTABLE_PEN
        MedicationForm.INJECTABLE_VIAL -> MedicationForm.INJECTABLE_VIAL
        else -> if (injectable) MedicationForm.INJECTABLE_VIAL else MedicationForm.OTHER
      }
      upper.contains("TABLET") -> MedicationForm.TABLET
      upper.contains("CAPSULE") -> MedicationForm.CAPSULE
      upper.contains("GEL") || upper.contains("CREAM") || upper.contains("OINTMENT") ||
        upper.contains("PATCH") || upper.contains("LOTION") -> MedicationForm.TOPICAL
      upper.contains("AEROSOL") || upper.contains("INHAL") || upper.contains("SPRAY") -> MedicationForm.INHALER
      upper.contains("OPHTHALMIC") || upper.contains("OTIC") || upper.contains("DROP") -> MedicationForm.DROPS
      upper.contains("SOLUTION") || upper.contains("SUSPENSION") || upper.contains("SYRUP") ||
        upper.contains("ELIXIR") || upper.contains("LIQUID") -> MedicationForm.LIQUID
      else -> MedicationForm.OTHER
    }
  }

  internal fun mapRoute(routes: List<String>, form: MedicationForm): InjectionRoute? {
    if (!form.isInjectable) return null
    val joined = routes.joinToString(" ").uppercase()
    return when {
      joined.contains("SUBCUTANEOUS") -> InjectionRoute.SC
      joined.contains("INTRAMUSCULAR") -> InjectionRoute.IM
      joined.contains("INTRADERMAL") -> InjectionRoute.INTRADERMAL
      joined.contains("INTRAVENOUS") -> InjectionRoute.IV
      else -> InjectionRoute.IM
    }
  }

  internal fun normalizeStrengthText(raw: String): String {
    return raw
      .replace("/1mL", "/mL", ignoreCase = true)
      .replace("/1L", "/L", ignoreCase = true)
      // Only drop a bare "/1" (per dose unit); "/1.5mL" and "/10mL" are real denominators.
      .replace(BARE_PER_ONE_REGEX, "")
      .trim()
  }

  internal fun parseConcentrationMgPerMl(strength: String): Double? {
    val regex = Regex("""([0-9]+(?:\.[0-9]+)?)\s*mg\s*/\s*(?:1\s*)?mL""", RegexOption.IGNORE_CASE)
    val match = regex.find(strength) ?: return null
    return match.groupValues.getOrNull(1)?.toDoubleOrNull()
  }

  private val PARENTERAL_ROUTES = listOf("SUBCUTANEOUS", "INTRAMUSCULAR", "INTRAVENOUS", "INTRADERMAL")

  /** The innermost container in a package description: a vial, or a prefilled device. */
  internal fun injectionDevice(packageDescription: String?): MedicationForm? {
    val upper = packageDescription?.uppercase() ?: return null
    val vial = upper.lastIndexOf("VIAL")
    val device = listOf("SYRINGE", "CARTRIDGE", "INJECTOR", " PEN").maxOf { upper.lastIndexOf(it) }
    return when {
      vial < 0 && device < 0 -> null
      device > vial -> MedicationForm.INJECTABLE_PEN
      else -> MedicationForm.INJECTABLE_VIAL
    }
  }

  /** Volume of one vial or prefilled device, e.g. "10 mL in 1 VIAL" or ".3 mL in 1 SYRINGE". */
  internal fun parseVialVolumeMl(packageDescription: String?): Double? {
    if (packageDescription.isNullOrBlank()) return null
    val regex = Regex(
      """([0-9]*\.?[0-9]+)\s*mL\s+in\s+1\s+(?:VIAL|SYRINGE|CARTRIDGE|INJECTOR|PEN)""",
      RegexOption.IGNORE_CASE,
    )
    val match = regex.find(packageDescription) ?: return null
    return match.groupValues.getOrNull(1)?.toDoubleOrNull()
  }

  private fun formatTitleCase(input: String): String {
    return input.trim().split(Regex("\\s+")).joinToString(" ") { word ->
      if (word.length <= 3 && word.all { it.isUpperCase() } && word in setOf("HCL", "XR", "ER", "SR", "USP")) {
        word
      } else {
        word.lowercase().replaceFirstChar { it.uppercase() }
      }
    }
  }
}
