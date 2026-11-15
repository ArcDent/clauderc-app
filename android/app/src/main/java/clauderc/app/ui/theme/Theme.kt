package clauderc.app.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import clauderc.app.R
import clauderc.app.data.AccentChoice
import clauderc.app.data.Appearance
import clauderc.app.data.UiStyle

val LocalColors = staticCompositionLocalOf { LightColors }
val LocalFontSizes = staticCompositionLocalOf { ClaudercFontSizes() }
val LocalSpacing = staticCompositionLocalOf { ClaudercSpacing() }
val LocalRadii = staticCompositionLocalOf { ClaudercRadii() }
val LocalMotion = staticCompositionLocalOf { ClaudercMotion() }
val LocalUiStyle = staticCompositionLocalOf { UiStyle.Minimal }

/** What is picked in 设置 right now, for the page that changes it. */
val LocalAppearance = staticCompositionLocalOf { Appearance() }

/** Short accessors for the design tokens inside composables. */
object Clauderc {
    val colors: ClaudercColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val fontSizes: ClaudercFontSizes @Composable @ReadOnlyComposable get() = LocalFontSizes.current
    val spacing: ClaudercSpacing @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val radii: ClaudercRadii @Composable @ReadOnlyComposable get() = LocalRadii.current
    val motion: ClaudercMotion @Composable @ReadOnlyComposable get() = LocalMotion.current
    val style: UiStyle @Composable @ReadOnlyComposable get() = LocalUiStyle.current
}

/**
 * The colours of [style] in light or [dark]. The card style takes [accent] for its accent and the
 * second git lane, with dark text on light accents such as yellow; the minimal style keeps its cobalt.
 */
fun palette(style: UiStyle, dark: Boolean, accent: Color? = null): ClaudercColors = when (style) {
    UiStyle.Minimal -> if (dark) DarkColors else LightColors
    UiStyle.Card -> {
        val base = if (dark) CardDarkColors else CardLightColors
        if (accent == null) base else base.copy(accent = accent, onAccent = onAccent(accent), lane2 = accent)
    }
}

private fun onAccent(accent: Color) = if (accent.luminance() > 0.5f) Color(0xFF111111) else Color.White

/** The colour of [choice]; the wallpaper's comes from the system's dynamic colours, which start at Android 12. */
@Composable
fun accentColor(choice: AccentChoice, dark: Boolean): Color {
    choice.rgb?.let { return Color(0xFF000000 or it) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Color(0xFF000000 or AccentChoice.Blue.rgb!!)
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
}

/** --font-mono. --font-ui is Roboto with Noto Sans SC, which is the system default. */
val MonoFamily = FontFamily(
    Font(R.font.roboto_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.roboto_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

@Composable
fun ClaudercTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    style: UiStyle = UiStyle.Minimal,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val c = palette(style, darkTheme, accent)
    val sizes = ClaudercFontSizes()
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.fg,
            surface = c.bg, onSurface = c.fg, onSurfaceVariant = c.fg2, surfaceContainerLow = c.sheet,
            surfaceContainer = c.surface, surfaceContainerHigh = c.sheet, outline = c.surface2,
            outlineVariant = c.line, error = c.danger, scrim = c.scrim,
            inverseSurface = c.inverse, inverseOnSurface = c.onInverse,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.fg,
            surface = c.bg, onSurface = c.fg, onSurfaceVariant = c.fg2, surfaceContainerLow = c.sheet,
            surfaceContainer = c.surface, surfaceContainerHigh = c.sheet, outline = c.surface2,
            outlineVariant = c.line, error = c.danger, scrim = c.scrim,
            inverseSurface = c.inverse, inverseOnSurface = c.onInverse,
        )
    }
    val loops = remember { LoopClock() }
    LaunchedEffect(loops) { loops.run() }
    CompositionLocalProvider(
        LocalColors provides c,
        LocalFontSizes provides sizes,
        LocalSpacing provides ClaudercSpacing(),
        LocalRadii provides ClaudercRadii(),
        LocalMotion provides ClaudercMotion(),
        LocalUiStyle provides style,
        LocalLoopClock provides loops,
    ) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(
                LocalIndication provides PressIndication(),
                LocalContentColor provides c.fg,
                LocalTextStyle provides TextStyle(fontSize = sizes.body, color = c.fg),
                content = content,
            )
        }
    }
}
