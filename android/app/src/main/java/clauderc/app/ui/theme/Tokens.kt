package clauderc.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Values copied from design/ui-mockup/index.html (synced with design/ui-mockup/demo.html).
// Each property maps to one CSS variable: --c-surface-2 -> surface2 and so on.
// ThemeTokensTest parses index.html and fails when the two drift apart.

@Immutable
data class ClaudercColors(
    val bg: Color,
    val sheet: Color,
    val card: Color,
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
    // Style-specific --k- variables; styles that do not set a token leave it transparent.
    /** 印版: primary printing ink (--k-ink). */
    val ink: Color = Color.Transparent,
    /** Positive/connected status indicator (--k-ok). */
    val ok: Color = Color.Transparent,
    /** 素卡 / 浮墨: card surface fill (--k-glass). */
    val panel: Color = Color.Transparent,
    /** 素卡 / 浮墨: floating pill/orb surface fill (--k-float). */
    val float: Color = Color.Transparent,
    /** 清玻: the tint of glass bars and sheets over content (--k-pane). */
    val pane: Color = Color.Transparent,
    /** 清玻: bright rim along glass edges (--k-rimc). */
    val rim: Color = Color.Transparent,
    /** 清玻 / 浮墨: hairline around floating surfaces (--k-edgec). */
    val edge: Color = Color.Transparent,
    /** 清玻: lens over the picked tab (--k-lens). */
    val lens: Color = Color.Transparent,
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
    card = hex(0xFFFFFF),
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
    card = hex(0x000000),
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

// The four styles (:root[data-style="clear" | "plate" | "tile" | "ink"]).

/** 清玻: clean paper with frosted glass floating bars and sheets. */
val ClearLightColors = LightColors.copy(
    scrim = rgba(0, 0, 0, 0.18f),
    ok = hex(0x1E7A34),
    pane = rgba(255, 255, 255, 0.64f),
    rim = rgba(255, 255, 255, 0.95f),
    edge = rgba(0, 0, 0, 0.07f),
    lens = rgba(255, 255, 255, 0.72f),
)

val ClearDarkColors = DarkColors.copy(
    scrim = rgba(0, 0, 0, 0.45f),
    ok = hex(0x87D697),
    pane = rgba(22, 22, 22, 0.6f),
    rim = rgba(255, 255, 255, 0.16f),
    edge = rgba(255, 255, 255, 0.08f),
    lens = rgba(255, 255, 255, 0.13f),
)

/** 印版: high-contrast monochrome letterpress, defaulting to carbon black ink on cotton paper. */
val PlateLightColors = LightColors.copy(
    bg = hex(0xF9F8F3),
    sheet = hex(0xF9F8F3),
    card = hex(0xF9F8F3),
    surface = hex(0xE3E3DE),
    surface2 = hex(0xC5C4C0),
    line = rgba(11, 11, 13, 0.32f),
    fg = hex(0x0B0B0D),
    fg2 = hex(0x313132),
    fg3 = hex(0x61605F),
    accent = hex(0x0B0B0D),
    onAccent = hex(0xF9F8F3),
    danger = hex(0xCC111F),
    addFg = hex(0x0B6E3B),
    delFg = hex(0xCC111F),
    inverse = hex(0x0B0B0D),
    onInverse = hex(0xF9F8F3),
    scrim = rgba(11, 11, 13, 0.42f),
    lane1 = hex(0x0B0B0D),
    lane2 = hex(0x0B0B0D),
    lane3 = hex(0x61605F),
    ink = hex(0x0B0B0D),
    ok = hex(0x0B6E3B),
)

val PlateDarkColors = DarkColors.copy(
    bg = hex(0x0A0A0C),
    sheet = hex(0x0A0A0C),
    card = hex(0x0A0A0C),
    surface = hex(0x262627),
    surface2 = hex(0x3E3E40),
    line = rgba(245, 244, 238, 0.36f),
    fg = hex(0xF5F4EE),
    fg2 = hex(0xD0CFCB),
    fg3 = hex(0xA1A09D),
    accent = hex(0xF5F4EE),
    onAccent = hex(0x0A0A0C),
    danger = hex(0xFF6B6B),
    addFg = hex(0x5EE69D),
    delFg = hex(0xFF6B6B),
    inverse = hex(0xF5F4EE),
    onInverse = hex(0x0A0A0C),
    scrim = rgba(0, 0, 0, 0.68f),
    lane1 = hex(0xF5F4EE),
    lane2 = hex(0xF5F4EE),
    lane3 = hex(0xA1A09D),
    ink = hex(0xF5F4EE),
    ok = hex(0x5EE69D),
)

/** 素卡: warm grey canvas with borderless white bento cards and vibrant accent fills. */
val TileLightColors = LightColors.copy(
    bg = hex(0xEEEDF0),
    sheet = hex(0xFFFFFF),
    card = hex(0xFFFFFF),
    surface = hex(0xF4F4F6),
    surface2 = hex(0xE7E6EA),
    line = hex(0xE7E6EA),
    fg = hex(0x121214),
    fg2 = hex(0x67666D),
    fg3 = hex(0xA09FA6),
    accent = hex(0x007AFF),
    onAccent = hex(0xFFFFFF),
    danger = hex(0xD93A3F),
    addFg = hex(0x1F8A4C),
    delFg = hex(0xD93A3F),
    inverse = hex(0x121214),
    onInverse = hex(0xFFFFFF),
    scrim = rgba(20, 20, 24, 0.28f),
    lane1 = hex(0x121214),
    lane2 = hex(0x007AFF),
    lane3 = hex(0xA09FA6),
    ok = hex(0x1F9D57),
    panel = hex(0xFFFFFF),
    float = hex(0xFFFFFF),
)

val TileDarkColors = DarkColors.copy(
    bg = hex(0x0F0F11),
    sheet = hex(0x1B1B1F),
    card = hex(0x1B1B1F),
    surface = hex(0x25252A),
    surface2 = hex(0x2B2B30),
    line = hex(0x2B2B30),
    fg = hex(0xF3F3F5),
    fg2 = hex(0xA3A2AA),
    fg3 = hex(0x67666E),
    accent = hex(0x007AFF),
    onAccent = hex(0xFFFFFF),
    danger = hex(0xFF7A7E),
    addFg = hex(0x5BD08C),
    delFg = hex(0xFF7A7E),
    inverse = hex(0xF3F3F5),
    onInverse = hex(0x0F0F11),
    scrim = rgba(0, 0, 0, 0.55f),
    lane1 = hex(0xF3F3F5),
    lane2 = hex(0x007AFF),
    lane3 = hex(0x67666E),
    ok = hex(0x3FCB7E),
    panel = hex(0x1B1B1F),
    float = hex(0x1B1B1F),
)

/** 浮墨: near-black floating pills and orbs (with matching warm-stone light palette). */
val InkLightColors = LightColors.copy(
    bg = hex(0xF5F4F0),
    sheet = hex(0xFFFFFF),
    card = hex(0xFFFFFF),
    surface = hex(0xFFFFFF),
    surface2 = hex(0xECEAE4),
    line = rgba(0, 0, 0, 0.07f),
    fg = hex(0x161615),
    fg2 = hex(0x6D6B66),
    fg3 = hex(0xA6A39C),
    accent = hex(0xC9653F),
    onAccent = hex(0xFFFFFF),
    danger = hex(0xD2453B),
    addFg = hex(0x2C8A4E),
    delFg = hex(0xD2453B),
    inverse = hex(0x161615),
    onInverse = hex(0xF5F4F0),
    scrim = rgba(40, 30, 20, 0.22f),
    lane1 = hex(0x161615),
    lane2 = hex(0xC9653F),
    lane3 = hex(0xA6A39C),
    ok = hex(0x2E9E5B),
    panel = hex(0xFFFFFF),
    float = hex(0xFFFFFF),
    edge = rgba(0, 0, 0, 0.07f),
)

val InkDarkColors = DarkColors.copy(
    bg = hex(0x0B0B0C),
    sheet = hex(0x1F1F22),
    card = hex(0x161618),
    surface = hex(0x1F1F22),
    surface2 = hex(0x2B2B2F),
    line = rgba(255, 255, 255, 0.07f),
    fg = hex(0xF2F2F3),
    fg2 = hex(0xA1A1A6),
    fg3 = hex(0x636368),
    accent = hex(0xD97757),
    onAccent = hex(0xFFFFFF),
    danger = hex(0xFF8A80),
    addFg = hex(0x6FCF97),
    delFg = hex(0xFF8A80),
    inverse = hex(0xF2F2F4),
    onInverse = hex(0x121214),
    scrim = rgba(0, 0, 0, 0.55f),
    lane1 = hex(0xF2F2F3),
    lane2 = hex(0xD97757),
    lane3 = hex(0x636368),
    ok = hex(0x57C27F),
    panel = hex(0x161618),
    float = hex(0x1F1F22),
    edge = rgba(255, 255, 255, 0.07f),
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
    /** Cards of the card style. */
    val card: Dp = 20.dp,
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
    /** The theme circle of 设置: slow at both ends, so it can be seen growing from the tap. */
    val easeReveal: CubicBezierEasing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f),
    val durReveal: Int = 650,
)
