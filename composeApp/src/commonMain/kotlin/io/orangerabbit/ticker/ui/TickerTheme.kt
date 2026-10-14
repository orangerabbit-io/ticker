package io.orangerabbit.ticker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import ticker.composeapp.generated.resources.Res
import ticker.composeapp.generated.resources.jetbrains_mono_regular
import ticker.composeapp.generated.resources.jetbrains_mono_semibold

// Bitcoin branding accent used by rows whose symbol starts with "BTC"; identical in
// both palettes.
val BtcOrange = Color(0xFFF7931A)

// Pinned dark-first terminal palette. Every slot the design spec pins is set verbatim;
// the remaining Material slots keep their defaults (no Material You / dynamic color).
private val DarkColors = darkColorScheme(
    background = Color(0xFF0B0E14),
    surface = Color(0xFF12161F),
    surfaceVariant = Color(0xFF1A1F2B),
    onSurface = Color(0xFFE6E9EF),
    onSurfaceVariant = Color(0xFF8B93A1),
    outline = Color(0xFF232936),
    primary = BtcOrange,
    onPrimary = Color(0xFF0B0E14),
    secondaryContainer = Color(0xFF1A1F2B),
    onSecondaryContainer = Color(0xFFE6E9EF),
)

private val LightColors = lightColorScheme(
    background = Color(0xFFFAFBFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurface = Color(0xFF171C26),
    onSurfaceVariant = Color(0xFF5C6470),
    outline = Color(0xFFE3E6EB),
    primary = BtcOrange,
    onPrimary = Color(0xFF171C26),
    secondaryContainer = Color(0xFFF0F2F5),
    onSecondaryContainer = Color(0xFF171C26),
)

// TickerColors is the theme-provided movement pair: up is positive, down is negative.
// Call sites read it through LocalTickerColors so forced light/dark never disagrees
// with the system-dark detection.
data class TickerColors(val positive: Color, val negative: Color)

private val DarkTickerColors = TickerColors(
    positive = Color(0xFF34D399),
    negative = Color(0xFFF87171),
)

private val LightTickerColors = TickerColors(
    positive = Color(0xFF059669),
    negative = Color(0xFFDC2626),
)

val LocalTickerColors = staticCompositionLocalOf { DarkTickerColors }

// Warning tint for degraded-but-usable states (unavailable footer, error banner).
private val DarkWarning = Color(0xFFFBBF24)
private val LightWarning = Color(0xFFB45309)

internal val LocalTickerWarning = staticCompositionLocalOf { DarkWarning }

// TickerMono is the bundled JetBrains Mono family (Regular + SemiBold) used for
// symbols and every numeric column. Compose resources load fonts asynchronously in
// composition, so the family is built and remembered there instead of at class-load.
val TickerMono: FontFamily
    @Composable get() {
        val regular = Font(Res.font.jetbrains_mono_regular)
        val semibold = Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold)
        return remember(regular, semibold) { FontFamily(regular, semibold) }
    }

// TickerTheme is the app's single Material3 theme: pinned dark-first terminal palette,
// system light/dark by default. Callers that resolve darkTheme from the persisted
// ThemeMode pass the resolved flag in.
@Composable
fun TickerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTickerColors provides if (darkTheme) DarkTickerColors else LightTickerColors,
        LocalTickerWarning provides if (darkTheme) DarkWarning else LightWarning,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
