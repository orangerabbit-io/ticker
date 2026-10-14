package io.orangerabbit.ticker.ui

import java.util.Locale

internal actual fun formatDecimal(value: Double, decimals: Int): String =
    "%,.${decimals}f".format(Locale.US, value)
