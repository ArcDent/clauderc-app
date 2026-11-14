package clauderc.app.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import clauderc.app.R

val LocalColors = staticCompositionLocalOf { LightColors }
val LocalFontSizes = staticCompositionLocalOf { ClaudercFontSizes() }
val LocalSpacing = staticCompositionLocalOf { ClaudercSpacing() }
val LocalRadii = staticCompositionLocalOf { ClaudercRadii() }
val LocalMotion = staticCompositionLocalOf { ClaudercMotion() }

/** Short accessors for the design tokens inside composables. */
object Clauderc {
    val colors: ClaudercColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val fontSizes: ClaudercFontSizes @Composable @ReadOnlyComposable get() = LocalFontSizes.current
    val spacing: ClaudercSpacing @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val radii: ClaudercRadii @Composable @ReadOnlyComposable get() = LocalRadii.current
    val motion: ClaudercMotion @Composable @ReadOnlyComposable get() = LocalMotion.current
}

/** --font-mono. --font-ui is Roboto with Noto Sans SC, which is the system default. */
val MonoFamily = FontFamily(
    Font(R.font.roboto_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.roboto_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

@Composable
fun ClaudercTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkColors else LightColors
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
    CompositionLocalProvider(
        LocalColors provides c,
        LocalFontSizes provides sizes,
        LocalSpacing provides ClaudercSpacing(),
        LocalRadii provides ClaudercRadii(),
        LocalMotion provides ClaudercMotion(),
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
