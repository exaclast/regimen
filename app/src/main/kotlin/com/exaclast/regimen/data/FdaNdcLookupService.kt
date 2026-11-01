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
    }

    return ParsedDrugBarcode(
      rawBarcode = rawInput,
      extractedNdc = cleaned,
    )
  }

  private fun extractNdc10FromGtin14(gtin14: String): String? {
    if (gtin14.length != 14) return null
    // US FDA NDC GTIN-14 has indicator digit at index 0, followed by "03" at indices 1..2,
    // or "003" at indices 0..2, then 10-digit NDC at indices 3..12, then check digit at index 13.
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
   * OpenFDA stores unpadded 10-digit hyphenated NDCs (4-4-2, 5-3-2, or 5-4-1).
   */
  fun buildOpenFdaSearchQuery(rawNdc: String): String {
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
        // 4-4-2 candidate if segment 1 starts with '0'
        if (s1.startsWith("0")) {
          val u1 = s1.substring(1)
          packageCandidates.add("$u1-$s2-$s3")
          productCandidates.add("$u1-$s2")
        }
        // 5-3-2 candidate if segment 2 starts with '0'
        if (s2.startsWith("0")) {
          val u2 = s2.substring(1)
          packageCandidates.add("$s1-$u2-$s3")
          productCandidates.add("$s1-$u2")
        }
        // 5-4-1 candidate if segment 3 starts with '0'
        if (s3.startsWith("0")) {
          val u3 = s3.substring(1)
          packageCandidates.add("$s1-$s2-$u3")
        }
      }
    }

    val clauses = mutableListOf<String>()
    packageCandidates.forEach { clauses.add("packaging.package_ndc:\"$it\"") }
    productCandidates.forEach { clauses.add("product_ndc:\"$it\"") }
    return clauses.joinToString("+OR+")
  }

  /**
   * Queries OpenFDA `/drug/ndc.json` for the given NDC-11, 10-digit NDC, or scanned barcode
   * and parses available medication attributes.
   */
  suspend fun lookupByNdc(rawNdc: String): Result<FdaDrugLookupResult> = withContext(Dispatchers.IO) {
    runCatching {
      val parsedBarcode = parseDrugBarcode(rawNdc)
      val candidates = candidateNdc11s(rawNdc)
      val primaryNdc11 = candidates.first()
      val digitsOnly = primaryNdc11.filter { it.isDigit() }
      if (digitsOnly.length < 8) {
        throw IllegalArgumentException("Please enter or scan a valid 10- or 11-digit NDC or drug barcode.")
      }

      val searchClause = buildOpenFdaSearchQuery(rawNdc)
      val encodedSearch = URLEncoder.encode(searchClause, "UTF-8")
        .replace("%2B", "+")
        .replace("%3A", ":")
        .replace("%22", "\"")
      val urlString = "https://api.fda.gov/drug/ndc.json?search=$encodedSearch&limit=1"

      val responseBody = runCatching {
        fetchHttpUrl(urlString)
      }.getOrNull()

      if (responseBody != null) {
        val parsed = parseOpenFdaJson(responseBody, primaryNdc11)
        if (parsed != null) {
          return@runCatching parsed.copy(
            expirationDateIso = parsedBarcode.expirationDateIso,
            lotNumber = parsedBarcode.lotNumber,
          )
        }
      }

      // Check local fallback catalog across all candidate NDC-11s
      for (candidate in candidates) {
        offlineCatalog[candidate]?.let {
          return@runCatching it.copy(
            expirationDateIso = parsedBarcode.expirationDateIso,
            lotNumber = parsedBarcode.lotNumber,
          )
        }
      }

      throw IllegalStateException("No matching FDA NDC record found for $primaryNdc11.")
    }
  }

  internal fun parseOpenFdaJson(rawJson: String, fallbackCanonicalNdc11: String): FdaDrugLookupResult? {
    val response = runCatching { json.decodeFromString<OpenFdaNdcResponse>(rawJson) }.getOrNull()
      ?: return null
    val item = response.results.firstOrNull() ?: return null

    val rawGeneric = item.genericName?.takeIf { it.isNotBlank() }
      ?: item.activeIngredients.mapNotNull { it.name }.joinToString(" / ").takeIf { it.isNotBlank() }
      ?: item.brandName
      ?: return null

    // Resolve exact canonical 11-digit NDC from packaging if available (crucial for 10-digit barcodes)
    val matchedPackageNdc = item.packaging.firstOrNull()?.packageNdc
    val resolvedNdc11 = if (!matchedPackageNdc.isNullOrBlank()) {
      toCanonicalNdc11(matchedPackageNdc)
    } else {
      fallbackCanonicalNdc11
    }

    val formulaName = formatTitleCase(rawGeneric)
    val brandName = item.brandName?.takeIf { it.isNotBlank() }?.let { formatTitleCase(it) }
    val manufacturer = item.labelerName?.takeIf { it.isNotBlank() }

    val rawStrength = item.activeIngredients
      .mapNotNull { it.strength?.trim() }
      .filter { it.isNotEmpty() }
      .joinToString(" / ")
      .let { normalizeStrengthText(it) }
      .ifBlank { "See package" }

    val form = mapDosageForm(item.dosageForm)
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
    val packageDesc = item.packaging.firstOrNull()?.description
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

  internal fun mapDosageForm(rawDosageForm: String?): MedicationForm {
    val upper = rawDosageForm?.uppercase().orEmpty()
    return when {
      upper.contains("INJECTION") || upper.contains("INJECTABLE") -> MedicationForm.INJECTABLE_VIAL
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
      .replace("/1", "")
      .trim()
  }

  internal fun parseConcentrationMgPerMl(strength: String): Double? {
    val regex = Regex("""([0-9]+(?:\.[0-9]+)?)\s*mg\s*/\s*(?:1\s*)?mL""", RegexOption.IGNORE_CASE)
    val match = regex.find(strength) ?: return null
    return match.groupValues.getOrNull(1)?.toDoubleOrNull()
  }

  internal fun parseVialVolumeMl(packageDescription: String?): Double? {
    if (packageDescription.isNullOrBlank()) return null
    val regex = Regex("""([0-9]+(?:\.[0-9]+)?)\s*mL\s+in\s+1\s+VIAL""", RegexOption.IGNORE_CASE)
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

  private val offlineCatalog = mapOf(
    "00009-0085-10" to FdaDrugLookupResult(
      ndc11 = "00009-0085-10",
      formulaName = "Testosterone Cypionate",
      brandName = "Depo-Testosterone",
      manufacturer = "Pharmacia & Upjohn / Pfizer",
      strength = "200 mg/mL",
      form = MedicationForm.INJECTABLE_VIAL,
      defaultDoseUnit = DoseUnit.MG,
      route = InjectionRoute.IM,
      concentrationMgPerMl = 200.0,
      vialVolumeMl = 10.0,
      packageDescription = "10 mL in 1 VIAL, MULTI-DOSE",
    ),
    "59417-0104-10" to FdaDrugLookupResult(
      ndc11 = "59417-0104-10",
      formulaName = "Lisdexamfetamine Dimesylate",
      brandName = "Vyvanse",
      manufacturer = "Takeda Pharmaceuticals America, Inc.",
      strength = "40 mg",
      form = MedicationForm.CAPSULE,
      defaultDoseUnit = DoseUnit.CAPSULE,
    ),
    "24338-0853-13" to FdaDrugLookupResult(
      ndc11 = "24338-0853-13",
      formulaName = "Dextroamphetamine Sulfate",
      brandName = "Zenzedi",
      manufacturer = "Azurity Pharmaceuticals, Inc.",
      strength = "5 mg",
      form = MedicationForm.TABLET,
      defaultDoseUnit = DoseUnit.TABLET,
    ),
    "00169-7501-11" to FdaDrugLookupResult(
      ndc11 = "00169-7501-11",
      formulaName = "Semaglutide",
      brandName = "Ozempic",
      manufacturer = "Novo Nordisk Inc.",
      strength = "1.34 mg/mL",
      form = MedicationForm.INJECTABLE_PEN,
      defaultDoseUnit = DoseUnit.MG,
      route = InjectionRoute.SC,
      concentrationMgPerMl = 1.34,
      vialVolumeMl = 3.0,
    ),
  )
}
