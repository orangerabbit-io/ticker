package io.orangerabbit.ticker.asset

// MinorUnit represents the scaling between a major currency and its minor form:
// minor value = major value * 10^unit (e.g. GBP 1.00 = 100 GBp).
data class MinorUnit(
    val code: String,
    val unit: Int,
)

// minorUnitForCurrencyCode reports whether the given major (ISO 4217) currency code has a
// tradable minor form (e.g. GBP -> GBp) and, when it does, returns the minor currency code
// and its minor unit (the power of ten separating the major and minor units). Only
// currencies traded on major exchanges are considered. Ported from the CLI's
// MinorUnitForCurrencyCode.
fun minorUnitForCurrencyCode(majorCurrency: String): MinorUnit? = minorUnitsByMajorCurrencyCode[majorCurrency]

private val minorUnitsByMajorCurrencyCode = mapOf(
    "AUD" to MinorUnit(code = "AUd", unit = 2),
    "CAD" to MinorUnit(code = "CAd", unit = 2),
    "CHF" to MinorUnit(code = "CHf", unit = 2),
    "CNY" to MinorUnit(code = "CNy", unit = 2),
    "EUR" to MinorUnit(code = "EUr", unit = 2),
    "GBP" to MinorUnit(code = "GBp", unit = 2),
    "HKD" to MinorUnit(code = "HKd", unit = 2),
    "INR" to MinorUnit(code = "INr", unit = 2),
    "TWD" to MinorUnit(code = "TWd", unit = 2),
    "USD" to MinorUnit(code = "USd", unit = 2),
    "ZAR" to MinorUnit(code = "ZAr", unit = 2),
)
