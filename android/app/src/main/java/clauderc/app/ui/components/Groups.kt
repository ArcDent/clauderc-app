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
import clauderc.app.ui.theme.Clauderc

/** True inside a group's card, where tonal fills use the surface colour instead of the card's own. */
val LocalInGroup = staticCompositionLocalOf { false }

/** 贴纸's cards: the --k-r corner. */
val StickerCorner = 22.dp

/** 格栅's heavy rule over a list. */
val GridRule = 1.5.dp

/**
 * One item of a list the style groups together (.list). 贴纸: an ink-outlined card 16dp in from the
 * page edges, rounded and outlined at the top of the [first] item and the bottom of the [last].
 * 格栅: 16dp in, under a heavy rule. Both draw a hairline between items
 * unless [divider] is off. 清玻 leaves the item as it is.
 */
@Composable
fun Modifier.groupItem(first: Boolean, last: Boolean, divider: Boolean = true): Modifier {
    val look = Clauderc.look
    val c = Clauderc.colors
    return when {
        look.cards -> this
            .padding(horizontal = Clauderc.spacing.sp4)
            .sticker(c.panel, c.ink, StickerCorner, top = first, bottom = last)
            .then(if (first || !divider) Modifier else Modifier.topLine(c.line, 1.dp))
        look.ruled -> this
            .padding(horizontal = Clauderc.spacing.sp4)
            .then(if (first) Modifier.topLine(c.fg, GridRule) else if (divider) Modifier.topLine(c.line, 1.dp) else Modifier)
        else -> this
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
 * A block the style sets apart. 贴纸 puts it on an ink-outlined card 16dp in from the page edges,
 * with [padding] inside. 格栅 sets it 16dp in, under a heavy rule when it is a list ([ruled]). 清玻
 * draws [content] straight on the page. Content keeps [ClaudercLook.blockGutter] from the sides.
 */
@Composable
fun GroupBlock(modifier: Modifier = Modifier, padding: Dp = 0.dp, ruled: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val look = Clauderc.look
    when {
        look.cards -> Column(
            modifier
                .padding(horizontal = Clauderc.spacing.sp4)
                .fillMaxWidth()
                .sticker(Clauderc.colors.panel, Clauderc.colors.ink, StickerCorner)
                .padding(padding),
        ) {
            CompositionLocalProvider(LocalInGroup provides true) { content() }
        }
        look.ruled -> Column(
            modifier
                .padding(horizontal = Clauderc.spacing.sp4)
                .fillMaxWidth()
                .then(if (ruled) Modifier.topLine(Clauderc.colors.fg, GridRule) else Modifier),
            content = content,
        )
        else -> Column(modifier.fillMaxWidth(), content = content)
    }
}

/** A line [width] thick along the top edge. */
fun Modifier.topLine(color: Color, width: Dp): Modifier = drawBehind {
    drawRect(color, Offset.Zero, Size(size.width, width.toPx()))
}
