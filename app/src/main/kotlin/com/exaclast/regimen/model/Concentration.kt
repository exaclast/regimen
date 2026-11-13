package com.exaclast.regimen.model

/**
 * How much drug is in one mL, read from a medication's strength ("200 mg/mL", "100 units/mL (U-100)",
 * "200 mg/10 mL", "400 mg per 5 mL"). Never stored: the strength is the one thing typed, so the two
 * can't disagree.
 */
data class Concentration(val amountPerMl: Double, val unit: AmountUnit) {

  enum class AmountUnit(val symbol: String) {
    MG("mg"),
    MCG("mcg"),
    UNITS("units"),
  }

  fun formatted(): String = "${formatNum(amountPerMl)} ${unit.symbol}/mL"

  /**
   * mL holding [dose], or null when the dose's unit can't be converted: mass and units don't mix, and
   * counted units (tablets, puffs) have no volume.
   */
  fun volumeMl(dose: Double, doseUnit: DoseUnit): Double? {
    if (amountPerMl <= 0.0) return null
    return when (doseUnit) {
      DoseUnit.ML -> dose
      DoseUnit.MG -> massMg(dose, fromMcg = false)
      DoseUnit.MCG -> massMg(dose, fromMcg = true)
      DoseUnit.UNITS, DoseUnit.IU -> if (unit == AmountUnit.UNITS) dose / amountPerMl else null
      else -> null
    }
  }

  private fun massMg(dose: Double, fromMcg: Boolean): Double? {
    val doseMg = if (fromMcg) dose / 1000.0 else dose
    return when (unit) {
      AmountUnit.MG -> doseMg / amountPerMl
      AmountUnit.MCG -> doseMg * 1000.0 / amountPerMl
      AmountUnit.UNITS -> null
    }
  }

  companion object {
    private const val NUMBER = """(\d*\.?\d+)"""
    // "units", "IU", FDA's "[iU]" and "[USP'U]", and a bare "U".
    private const val AMOUNT_UNIT = """(g|mg|mcg|µg|ug|units?|\[?iu\]?|\[usp'?u\]|u)"""
    private val PER_ML = Regex(
      """$NUMBER\s*$AMOUNT_UNIT\s*(?:/|per)\s*$NUMBER?\s*ml\b""",
      RegexOption.IGNORE_CASE,
    )
    private val THOUSANDS_COMMA = Regex("""(?<=\d),(?=\d{3}\b)""")
    // Any amount with a mass or units unit; mL and percentages don't count.
    private val AMOUNT = Regex("""\d\s*$AMOUNT_UNIT(?![a-z])""", RegexOption.IGNORE_CASE)

    /**
     * Reads the amount per mL from [strength], or null when there's none or it's ambiguous. A
     * combination ("400 mg / 57 mg per 5 mL") is ambiguous: there's no single amount to divide by.
     */
    fun parse(strength: String?): Concentration? {
      if (strength.isNullOrBlank()) return null
      val text = strength.replace(THOUSANDS_COMMA, "")
      if (AMOUNT.findAll(text).count() != 1) return null
      val match = PER_ML.find(text) ?: return null
      val amount = match.groupValues[1].toDoubleOrNull() ?: return null
      val perMl = match.groupValues[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 1.0
      if (amount <= 0.0 || perMl <= 0.0) return null
      val raw = match.groupValues[2].lowercase().trim('[', ']')
      val (unit, factor) = when (raw) {
        "g" -> AmountUnit.MG to 1000.0
        "mg" -> AmountUnit.MG to 1.0
        "mcg", "µg", "ug" -> AmountUnit.MCG to 1.0
        else -> AmountUnit.UNITS to 1.0
      }
      return Concentration(amount * factor / perMl, unit)
    }
  }
}
