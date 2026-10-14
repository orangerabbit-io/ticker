package io.orangerabbit.ticker.ui

// Display formatting pinned by the Task 10 brief: money grouped with thousands
// separators (Locale.US), percents with one decimal, prices with six decimals for
// variable-precision (crypto) assets and two decimals otherwise, and negative
// quantities shown as-is (short positions).
internal expect fun formatDecimal(value: Double, decimals: Int): String

internal fun formatSignedDecimal(value: Double, decimals: Int): String =
    if (value > 0) "+" + formatDecimal(value, decimals) else formatDecimal(value, decimals)

internal fun formatPrice(value: Double, isVariablePrecision: Boolean): String =
    formatDecimal(value, if (isVariablePrecision) 6 else 2)

internal fun formatSignedPrice(value: Double, isVariablePrecision: Boolean): String =
    formatSignedDecimal(value, if (isVariablePrecision) 6 else 2)

internal fun formatAmount(value: Double, isVariablePrecision: Boolean): String =
    formatDecimal(value, if (isVariablePrecision) 6 else 2)

internal fun formatMoney(value: Double): String = formatDecimal(value, 2)

internal fun formatPercent(value: Double): String = formatDecimal(value, 1) + "%"

internal fun formatSignedPercent(value: Double): String = formatSignedDecimal(value, 1) + "%"

// changeText renders a day or total change as "+12.34 (+0.6%)", "-12.34 (-0.6%)", or
// the neutral "0.00 (0.0%)".
internal fun changeText(change: Double, changePercent: Double, isVariablePrecision: Boolean): String =
    formatSignedPrice(change, isVariablePrecision) + " (" + formatSignedPercent(changePercent) + ")"
