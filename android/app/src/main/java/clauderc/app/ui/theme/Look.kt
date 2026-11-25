package clauderc.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle

/**
 * How the current style shapes the pages beyond colours and sizes. Pages and components read these
 * instead of repeating style checks for structural traits.
 */
@Immutable
data class ClaudercLook(
    /** Lists and blocks sit on rounded cards 16dp in from the page edges (素卡 / 浮墨). */
    val cards: Boolean,
    /** Lists and controls use crisp editorial rules and square 0dp corners (印版). */
    val ruled: Boolean,
    /** Controls float as dark/light pills and orbs with 1dp hairline edges (浮墨). */
    val floating: Boolean,
    /** Bars and sheets use frosted glass blur over scrolled content (清玻). */
    val glass: Boolean,
    /** Where a row's content starts inside its group. */
    val rowStart: Dp,
    /** How far a block's own content keeps from the sides of a group block. */
    val blockGutter: Dp,
    /** Card corner radius of grouped surfaces. */
    val cardCorner: Dp,
)

fun look(style: UiStyle, spacing: ClaudercSpacing = ClaudercSpacing()): ClaudercLook = when (style) {
    UiStyle.Clear -> ClaudercLook(cards = false, ruled = false, floating = false, glass = true, rowStart = spacing.page, blockGutter = spacing.page, cardCorner = 16.dp)
    UiStyle.Plate -> ClaudercLook(cards = false, ruled = true, floating = false, glass = false, rowStart = 0.dp, blockGutter = 0.dp, cardCorner = 0.dp)
    UiStyle.Tile -> ClaudercLook(cards = true, ruled = false, floating = false, glass = false, rowStart = spacing.sp4, blockGutter = 0.dp, cardCorner = 24.dp)
    UiStyle.Ink -> ClaudercLook(cards = false, ruled = false, floating = true, glass = false, rowStart = spacing.page, blockGutter = spacing.page, cardCorner = 24.dp)
}

val LocalLook = staticCompositionLocalOf { look(UiStyle.Ink) }
