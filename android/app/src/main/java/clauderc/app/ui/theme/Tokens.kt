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
    // The --k- variables of the three styles; a style that has no such variable leaves it transparent.
    /** 贴纸: the ink of outlines (--k-ink). */
    val ink: Color = Color.Transparent,
    /** 贴纸: the sticker colours after the accent, one per row of icons (--k-p2 .. --k-p5). */
    val hue2: Color = Color.Transparent,
    val hue3: Color = Color.Transparent,
    val hue4: Color = Color.Transparent,
    val hue5: Color = Color.Transparent,
    /** 贴纸: the fill of cards (--k-glass). */
    val panel: Color = Color.Transparent,
    /** 贴纸: the fill of the nav bar and the composer (--k-float). */
    val float: Color = Color.Transparent,
    /** 贴纸: the dots of the paper, one every [StickerDotGap] (--k-dot). */
    val dot: Color = Color.Transparent,
    /** 贴纸: how much of the accent glows from the paper's top right corner (--k-glow-1, a share of --c-accent). */
    val glow1: Float = 0f,
    /** 贴纸: the glow from the paper's bottom left corner, yellow in light and none in dark (--k-glow-2). */
    val glow2: Color = Color.Transparent,
    /** 清玻: the tint of the glass bars and sheets over the content (--k-pane). */
    val pane: Color = Color.Transparent,
    /** 清玻: the bright rim along the glass edges (--k-rimc). */
    val rim: Color = Color.Transparent,
    /** 清玻: the hairline around the glass (--k-edgec). */
    val edge: Color = Color.Transparent,
    /** 清玻: the lens that marks the picked tab (--k-lens). */
    val lens: Color = Color.Transparent,
) {
    /** 贴纸: the colour of the [index]th icon down a list: the accent, then the four sticker colours. */
    fun hue(index: Int): Color = when (index.mod(5)) {
        0 -> accent
        1 -> hue2
        2 -> hue3
        3 -> hue4
        else -> hue5
    }

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

// The three styles (:root[data-style="clear" | "grid" | "sticker"]). Variables a style's blocks leave
// out keep the minimal value of the same brightness, so scrim, camera and the nav bar's glass are shared.

/** 清玻: the minimal colours, plus the glass of the bars and sheets that float over the content. */
val ClearLightColors = LightColors.copy(
    pane = rgba(255, 255, 255, 0.64f),
    rim = rgba(255, 255, 255, 0.95f),
    edge = rgba(0, 0, 0, 0.07f),
    lens = rgba(255, 255, 255, 0.72f),
)

val ClearDarkColors = DarkColors.copy(
    pane = rgba(22, 22, 22, 0.6f),
    rim = rgba(255, 255, 255, 0.16f),
    edge = rgba(255, 255, 255, 0.08f),
    lens = rgba(255, 255, 255, 0.13f),
)

/** 格栅: harder black, white and grey for the Swiss grid. */
val GridLightColors = LightColors.copy(
    bg = hex(0xFFFFFF),
    sheet = hex(0xFFFFFF),
    card = hex(0xFFFFFF),
    surface = hex(0xF1F1F1),
    surface2 = hex(0xE0E0E0),
    line = hex(0xE6E6E6),
    fg = hex(0x0A0A0A),
    fg2 = hex(0x555555),
    fg3 = hex(0x9A9A9A),
    lane1 = hex(0x0A0A0A),
    lane3 = hex(0xA0A0A0),
)

val GridDarkColors = DarkColors.copy(
    bg = hex(0x0A0A0A),
    sheet = hex(0x141414),
    card = hex(0x0A0A0A),
    surface = hex(0x171717),
    surface2 = hex(0x282828),
    line = hex(0x1E1E1E),
    fg = hex(0xF2F2F2),
    fg2 = hex(0x9A9A9A),
    fg3 = hex(0x5E5E5E),
    lane1 = hex(0xF2F2F2),
    lane3 = hex(0x5E5E5E),
)

/** 贴纸: the paper's dots sit one every [StickerDotGap], each [StickerDotRadius] round (--k-dot-gap, --k-dot-r). */
val StickerDotGap = 24.dp
val StickerDotRadius = 1.dp

/**
 * 贴纸: dotted cream paper, ink outlines without shadows and round stickers for icons.
 * accent and lane2 are the default blue; the theme swaps in the colour picked in 设置.
 */
val StickerLightColors = LightColors.copy(
    bg = hex(0xFFF6E9),
    sheet = hex(0xFFFDF8),
    card = hex(0xFFFFFF),
    surface = hex(0xFFFFFF),
    surface2 = rgba(30, 27, 46, 0.14f),
    line = rgba(30, 27, 46, 0.12f),
    fg = hex(0x1E1B2E),
    fg2 = hex(0x5E5870),
    fg3 = hex(0xA49DB0),
    accent = hex(0x007AFF),
    onAccent = hex(0xFFFFFF),
    danger = hex(0xE5352B),
    addBg = hex(0xE3F8E8),
    addFg = hex(0x17732F),
    delBg = hex(0xFFE7E5),
    delFg = hex(0xC4271E),
    inverse = hex(0x1E1B2E),
    onInverse = hex(0xFFF6E9),
    lane1 = hex(0x1E1B2E),
    lane2 = hex(0x007AFF),
    lane3 = hex(0xB9B2C4),
    ink = hex(0x1E1B2E),
    hue2 = hex(0xFFD84D),
    hue3 = hex(0xFF9EC4),
    hue4 = hex(0x8EE3C8),
    hue5 = hex(0xC7B2FF),
    panel = rgba(255, 255, 255, 0.9f),
    float = rgba(255, 255, 255, 0.82f),
    dot = rgba(30, 27, 46, 0.075f),
    glow1 = 0.12f,
    glow2 = rgba(255, 216, 77, 0.16f),
)

val StickerDarkColors = DarkColors.copy(
    bg = hex(0x1C1A27),
    sheet = hex(0x2E2A3E),
    card = hex(0x353048),
    surface = hex(0x353048),
    surface2 = rgba(255, 255, 255, 0.14f),
    line = rgba(255, 255, 255, 0.1f),
    fg = hex(0xF6F1FF),
    fg2 = hex(0xB4ACC6),
    fg3 = hex(0x6E6782),
    accent = hex(0x007AFF),
    onAccent = hex(0xFFFFFF),
    danger = hex(0xFF6B61),
    addBg = hex(0x12301C),
    addFg = hex(0x86DE9A),
    delBg = hex(0x3A1614),
    delFg = hex(0xFF8A80),
    inverse = hex(0xF6F1FF),
    onInverse = hex(0x15131D),
    lane1 = hex(0xF6F1FF),
    lane2 = hex(0x007AFF),
    lane3 = hex(0x6E6782),
    ink = hex(0x000000),
    hue2 = hex(0xFFD84D),
    hue3 = hex(0xFF8DBA),
    hue4 = hex(0x74D9B8),
    hue5 = hex(0xB9A0FF),
    panel = rgba(53, 48, 72, 0.96f),
    float = rgba(53, 48, 72, 0.9f),
    dot = rgba(255, 255, 255, 0.05f),
    glow1 = 0.14f,
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
