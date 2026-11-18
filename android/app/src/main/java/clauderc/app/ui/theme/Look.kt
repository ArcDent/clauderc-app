package clauderc.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle

/**
 * How the current style shapes the pages beyond colours and sizes. Pages and components read these
 * instead of asking which style is on, so a style is described here in one place.
 */
@Immutable
data class ClaudercLook(
    /** Lists and blocks sit on rounded cards 16dp in from the page edges (.list, .usage and so on). */
    val cards: Boolean,
    /** Lists sit 16dp in from the page edges under a heavy rule, with hairlines between rows (格栅). */
    val ruled: Boolean,
    /** Where a row's content starts inside its group: the page gutter, the card's inner edge, or the rule's start. */
    val rowStart: Dp,
    /** How far a block's own content keeps from the sides of a group block. */
    val blockGutter: Dp,
)

fun look(style: UiStyle, spacing: ClaudercSpacing = ClaudercSpacing()): ClaudercLook = when (style) {
    UiStyle.Clear -> ClaudercLook(cards = false, ruled = false, rowStart = spacing.page, blockGutter = spacing.page)
    UiStyle.Grid -> ClaudercLook(cards = false, ruled = true, rowStart = 0.dp, blockGutter = 0.dp)
    UiStyle.Sticker -> ClaudercLook(cards = true, ruled = false, rowStart = spacing.sp4, blockGutter = 0.dp)
}

val LocalLook = staticCompositionLocalOf { look(UiStyle.Clear) }
