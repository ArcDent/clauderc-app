package clauderc.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc

/** True inside a group's card, where tonal fills use the surface colour instead of the card's own. */
val LocalInGroup = staticCompositionLocalOf { false }

/**
 * One item of a grouped list (.list):
 * - 素卡: borderless white rounded card 16dp in from the page edges, hairline between rows.
 * - 浮墨: dark/light floating rounded card with 1dp hairline edge 16dp in.
 * - 印版: 18dp in under a 2dp heavy ink rule, hairlines between rows.
 * - 清玻: flat on the page.
 */
@Composable
fun Modifier.groupItem(first: Boolean, last: Boolean, divider: Boolean = true): Modifier {
    val ui = Clauderc.style
    val c = Clauderc.colors
    return when (ui) {
        UiStyle.Tile -> this
            .padding(horizontal = Clauderc.spacing.sp4)
            .tileSurface(c.card, CardCorner, top = first, bottom = last)
            .then(if (first || !divider) Modifier else Modifier.topLine(c.line, 1.dp))
        UiStyle.Ink -> this
        UiStyle.Plate -> this
            .padding(horizontal = 18.dp)
            .then(if (first) Modifier.topLine(c.fg, PlateRule) else if (divider) Modifier.topLine(c.line, 1.dp) else Modifier)
        UiStyle.Clear -> this
    }
}

/** A hairline above a row that follows another inside a group; nothing on 清玻. */
@Composable
fun Modifier.groupDivider(show: Boolean = true): Modifier {
    val look = Clauderc.look
    if (!(look.cards || look.ruled) || !show) return this
    return topLine(Clauderc.colors.line, 1.dp)
}

/**
 * A block the style sets apart:
 * - 素卡: white rounded card 16dp in.
 * - 浮墨: hairline-edged card 16dp in.
 * - 印版: 18dp in under a heavy rule when [ruled].
 * - 清玻: drawn straight on the page.
 */
@Composable
fun GroupBlock(modifier: Modifier = Modifier, padding: Dp = 0.dp, ruled: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val ui = Clauderc.style
    val c = Clauderc.colors
    when (ui) {
        UiStyle.Tile -> Column(
            modifier
                .padding(horizontal = Clauderc.spacing.sp4)
                .fillMaxWidth()
                .tileSurface(c.card, CardCorner)
                .padding(padding),
        ) {
            CompositionLocalProvider(LocalInGroup provides true) { content() }
        }
        UiStyle.Ink, UiStyle.Clear -> Column(modifier.fillMaxWidth(), content = content)
        UiStyle.Plate -> Column(
            modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .then(if (ruled) Modifier.topLine(c.fg, PlateRule) else Modifier),
            content = content,
        )
    }
}

/** A line [width] thick along the top edge. */
fun Modifier.topLine(color: Color, width: Dp): Modifier = drawBehind {
    drawRect(color, Offset.Zero, Size(size.width, width.toPx()))
}
