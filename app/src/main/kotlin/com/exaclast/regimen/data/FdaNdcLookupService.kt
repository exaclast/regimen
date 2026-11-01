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
   * Normalizes a 10-digit hyphenated NDC (4-4-2, 5-3-2, 5-4-1) or raw 11-digit string
   * into canonical HIPAA 11-digit format (XXXXX-XXXX-XX).
   */
  fun toCanonicalNdc11(raw: String): String {
    val trimmed = raw.trim()
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
    return if (digits.length == 11) {
      "${digits.substring(0, 5)}-${digits.substring(5, 9)}-${digits.substring(9, 11)}"
    } else {
      trimmed
    }
  }

  /**
   * Generates candidate OpenFDA `package_ndc` and `product_ndc` search tokens from an NDC-11
   * or hyphenated NDC input. OpenFDA stores unpadded 10-digit hyphenated NDCs (4-4-2, 5-3-2, or 5-4-1).
   */
  fun buildOpenFdaSearchQuery(rawNdc: String): String {
    val canonical = toCanonicalNdc11(rawNdc)
    val parts = canonical.split("-")
    val packageCandidates = linkedSetOf<String>()
    val productCandidates = linkedSetOf<String>()

    val rawClean = rawNdc.trim()
    if (rawClean.contains("-")) {
      packageCandidates.add(rawClean)
      val rawSegs = rawClean.split("-")
      if (rawSegs.size >= 2) {
        productCandidates.add("${rawSegs[0]}-${rawSegs[1]}")
      }
    }

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

    val clauses = mutableListOf<String>()
    packageCandidates.forEach { clauses.add("packaging.package_ndc:\"$it\"") }
    productCandidates.forEach { clauses.add("product_ndc:\"$it\"") }
    return clauses.joinToString("+OR+")
  }

  /**
   * Queries OpenFDA `/drug/ndc.json` for the given NDC-11 (or 10-digit NDC) and parses
   * available medication attributes.
   */
  suspend fun lookupByNdc(rawNdc: String): Result<FdaDrugLookupResult> = withContext(Dispatchers.IO) {
    runCatching {
      val canonicalNdc11 = toCanonicalNdc11(rawNdc)
      val digitsOnly = canonicalNdc11.filter { it.isDigit() }
      if (digitsOnly.length < 8) {
        throw IllegalArgumentException("Please enter a valid 10- or 11-digit NDC (e.g. 00009-0085-10).")
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
        val parsed = parseOpenFdaJson(responseBody, canonicalNdc11)
        if (parsed != null) {
          return@runCatching parsed
        }
      }

      // Check local fallback catalog if offline or if NDC is one of the common/sample entries
      offlineCatalog[canonicalNdc11]?.let { return@runCatching it }

      throw IllegalStateException("No matching FDA NDC record found for $canonicalNdc11.")
    }
  }

  internal fun parseOpenFdaJson(rawJson: String, canonicalNdc11: String): FdaDrugLookupResult? {
    val response = runCatching { json.decodeFromString<OpenFdaNdcResponse>(rawJson) }.getOrNull()
      ?: return null
    val item = response.results.firstOrNull() ?: return null

    val rawGeneric = item.genericName?.takeIf { it.isNotBlank() }
      ?: item.activeIngredients.mapNotNull { it.name }.joinToString(" / ").takeIf { it.isNotBlank() }
      ?: item.brandName
      ?: return null

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
      ndc11 = canonicalNdc11,
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

