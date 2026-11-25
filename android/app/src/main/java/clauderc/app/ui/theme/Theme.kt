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
import androidx.compose.ui.graphics.lerp
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
import clauderc.app.data.PlateInk
import clauderc.app.data.PlateNavStyle
import clauderc.app.data.UiStyle

val LocalColors = staticCompositionLocalOf { InkDarkColors }
val LocalFontSizes = staticCompositionLocalOf { ClaudercFontSizes() }
val LocalSpacing = staticCompositionLocalOf { ClaudercSpacing() }
val LocalRadii = staticCompositionLocalOf { ClaudercRadii() }
val LocalMotion = staticCompositionLocalOf { ClaudercMotion() }
val LocalUiStyle = staticCompositionLocalOf { UiStyle.Ink }
val LocalPlateNav = staticCompositionLocalOf { PlateNavStyle.Float }

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
    val look: ClaudercLook @Composable @ReadOnlyComposable get() = LocalLook.current
    val plateNav: PlateNavStyle @Composable @ReadOnlyComposable get() = LocalPlateNav.current
}

/**
 * The colours of [style] in light or [dark].
 * - 印版 derives its entire monochrome print palette from [plateInk] on cotton paper.
 * - 浮墨 / 素卡 / 清玻 take an optional custom [accent] override (or keep their default accent when null).
 */
fun palette(
    style: UiStyle,
    dark: Boolean,
    accent: Color? = null,
    plateInk: PlateInk = PlateInk.Black,
): ClaudercColors = when (style) {
    UiStyle.Plate -> platePalette(plateInk, dark)
    UiStyle.Clear -> {
        val base = if (dark) ClearDarkColors else ClearLightColors
        if (accent == null) base else base.copy(accent = accent, onAccent = onAccent(accent), lane2 = accent)
    }
    UiStyle.Tile -> {
        val base = if (dark) TileDarkColors else TileLightColors
        if (accent == null) base else base.copy(accent = accent, onAccent = onAccent(accent), lane2 = accent)
    }
    UiStyle.Ink -> {
        val base = if (dark) InkDarkColors else InkLightColors
        if (accent == null) base else base.copy(accent = accent, onAccent = onAccent(accent), lane2 = accent)
    }
}

private fun platePalette(ink: PlateInk, dark: Boolean): ClaudercColors {
    val base = if (dark) PlateDarkColors else PlateLightColors
    if (ink == PlateInk.Black) return base
    val paper = if (dark) Color(0xFF000000 or ink.darkPaper) else base.bg
    val fg = Color(0xFF000000 or if (dark) ink.darkInk else ink.lightInk)
    val fg2 = lerp(paper, fg, 0.84f)
    val fg3 = lerp(paper, fg, 0.64f)
    val surface = lerp(paper, fg, if (dark) 0.12f else 0.09f)
    val surface2 = lerp(paper, fg, if (dark) 0.22f else 0.20f)
    val line = fg.copy(alpha = if (dark) 0.36f else 0.32f)
    return base.copy(
        bg = paper,
        sheet = paper,
        card = paper,
        surface = surface,
        surface2 = surface2,
        line = line,
        fg = fg,
        fg2 = fg2,
        fg3 = fg3,
        accent = fg,
        onAccent = paper,
        inverse = fg,
        onInverse = paper,
        lane1 = fg,
        lane2 = fg,
        lane3 = fg3,
        ink = fg,
    )
}

private fun onAccent(accent: Color) = if (accent.luminance() > 0.5f) Color(0xFF111111) else Color.White

/**
 * The custom accent colour for [choice], or null when [AccentChoice.Default] lets each style keep
 * its own default accent (陶土 for 浮墨, 蓝色 for 素卡, 钴蓝 for 清玻).
 */
@Composable
fun accentColor(choice: AccentChoice, dark: Boolean): Color? {
    if (choice == AccentChoice.Default) return null
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
    style: UiStyle = UiStyle.Ink,
    accent: Color? = null,
    plateInk: PlateInk = PlateInk.Black,
    plateNav: PlateNavStyle = PlateNavStyle.Float,
    content: @Composable () -> Unit,
) {
    val c = palette(style, darkTheme, accent, plateInk)
    val sizes = ClaudercFontSizes()
    val spacing = ClaudercSpacing()
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
        LocalSpacing provides spacing,
        LocalRadii provides ClaudercRadii(),
        LocalMotion provides ClaudercMotion(),
        LocalUiStyle provides style,
        LocalLook provides look(style, spacing),
        LocalPlateNav provides plateNav,
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
