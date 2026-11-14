package clauderc.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Values copied from the approved mockup design/ui-mockup/index.html.
// Each property maps to one CSS variable: --c-surface-2 -> surface2 and so on.
// ThemeTokensTest parses the mockup and fails when the two drift apart.

@Immutable
data class ClaudercColors(
    val bg: Color,
    val sheet: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val addBg: Color,
    val addFg: Color,
    val delBg: Color,
    val delFg: Color,
    val inverse: Color,
    val onInverse: Color,
    val scrim: Color,
    val camera: Color,
    val onCamera: Color,
    val lane1: Color,
    val lane2: Color,
    val lane3: Color,
    val glass: Color,
    val glassInd: Color,
    val glassHi: Color,
    val glassLo: Color,
    val glassEdge: Color,
    val glassSheen: Color,
    val glassShadow: Color,
    val isDark: Boolean,
) {
    /** Lane colors of the git timeline cycle through --c-lane-1..3. */
    fun lane(index: Int): Color = when (index.mod(3)) {
        0 -> lane1
        1 -> lane2
        else -> lane3
    }
}

private fun hex(rgb: Long) = Color(0xFF000000 or rgb)
private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255).roundToInt())

val LightColors = ClaudercColors(
    bg = hex(0xFFFFFF),
    sheet = hex(0xFFFFFF),
    surface = hex(0xF3F3F3),
    surface2 = hex(0xE7E7E7),
    line = hex(0xECECEC),
    fg = hex(0x111111),
    fg2 = hex(0x5E5E5E),
    fg3 = hex(0x9F9F9F),
    accent = hex(0x2A45E0),
    onAccent = hex(0xFFFFFF),
    danger = hex(0xC5362B),
    addBg = hex(0xEAF6EC),
    addFg = hex(0x1E7A34),
    delBg = hex(0xFCECEA),
    delFg = hex(0xB3261E),
    inverse = hex(0x2A2A2A),
    onInverse = hex(0xF2F2F2),
    scrim = rgba(0, 0, 0, 0.32f),
    camera = hex(0x000000),
    onCamera = hex(0xFFFFFF),
    lane1 = hex(0x111111),
    lane2 = hex(0x2A45E0),
    lane3 = hex(0xA8A8A8),
    glass = rgba(255, 255, 255, 0.58f),
    glassInd = rgba(17, 17, 17, 0.06f),
    glassHi = rgba(255, 255, 255, 0.95f),
    glassLo = rgba(255, 255, 255, 0.35f),
    glassEdge = rgba(0, 0, 0, 0.07f),
    glassSheen = rgba(255, 255, 255, 0.6f),
    glassShadow = rgba(0, 0, 0, 0.22f),
    isDark = false,
)

val DarkColors = ClaudercColors(
    bg = hex(0x000000),
    sheet = hex(0x161616),
    surface = hex(0x171717),
    surface2 = hex(0x262626),
    line = hex(0x1F1F1F),
    fg = hex(0xF1F1F1),
    fg2 = hex(0x9C9C9C),
    fg3 = hex(0x626262),
    accent = hex(0x8395FF),
    onAccent = hex(0x060B30),
    danger = hex(0xFF7B6F),
    addBg = hex(0x0B1C10),
    addFg = hex(0x87D697),
    delBg = hex(0x24100E),
    delFg = hex(0xFF8B81),
    inverse = hex(0xE6E6E6),
    onInverse = hex(0x111111),
    scrim = rgba(0, 0, 0, 0.6f),
    camera = hex(0x000000),
    onCamera = hex(0xFFFFFF),
    lane1 = hex(0xF1F1F1),
    lane2 = hex(0x8395FF),
    lane3 = hex(0x6C6C6C),
    glass = rgba(28, 28, 28, 0.52f),
    glassInd = rgba(255, 255, 255, 0.1f),
    glassHi = rgba(255, 255, 255, 0.2f),
    glassLo = rgba(255, 255, 255, 0.04f),
    glassEdge = rgba(255, 255, 255, 0.09f),
    glassSheen = rgba(255, 255, 255, 0.07f),
    glassShadow = rgba(0, 0, 0, 0.7f),
    isDark = true,
)

/** --fs-* */
@Immutable
data class ClaudercFontSizes(
    val display: TextUnit = 32.sp,
    val headline: TextUnit = 24.sp,
    val title: TextUnit = 20.sp,
    val body: TextUnit = 16.sp,
    val label: TextUnit = 14.sp,
    val caption: TextUnit = 12.sp,
    val mono: TextUnit = 13.sp,
    val monoSm: TextUnit = 12.sp,
)

/** --sp-* */
@Immutable
data class ClaudercSpacing(
    val sp1: Dp = 4.dp,
    val sp2: Dp = 8.dp,
    val sp3: Dp = 12.dp,
    val sp4: Dp = 16.dp,
    val sp5: Dp = 20.dp,
    val sp6: Dp = 24.dp,
    val sp8: Dp = 32.dp,
    val page: Dp = 24.dp,
)

/** --r-*; --r-pill is 999px, i.e. fully rounded. */
@Immutable
data class ClaudercRadii(
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 28.dp,
    val pill: Dp = 999.dp,
)

/** --ease-* and --dur-* */
@Immutable
data class ClaudercMotion(
    val easeEmph: CubicBezierEasing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f),
    val easeEmphIn: CubicBezierEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f),
    val easeSpring: CubicBezierEasing = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f),
    val durS: Int = 150,
    val durM: Int = 280,
    val durL: Int = 350,
)
