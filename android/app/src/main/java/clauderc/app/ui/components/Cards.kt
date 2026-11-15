package clauderc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc

/** True inside a card, where tonal fills use the surface colour instead of the card's own. */
val LocalOnCard = staticCompositionLocalOf { false }

/** True in the card style. */
val isCardStyle: Boolean @Composable @ReadOnlyComposable get() = Clauderc.style == UiStyle.Card

/** Where a row's content starts: the page gutter, or the card's inner edge in the card style. */
val rowStart: Dp @Composable @ReadOnlyComposable get() = if (isCardStyle) Clauderc.spacing.sp4 else Clauderc.spacing.page

/**
 * One item of a list that the card style groups into a card (.list): 16dp in from the page edges,
 * the card's colour, rounded at the top of the [first] item and the bottom of the [last], and a
 * hairline between items unless [divider] is off. The minimal style leaves the item as it is.
 */
@Composable
fun Modifier.cardItem(first: Boolean, last: Boolean, divider: Boolean = true): Modifier {
    if (!isCardStyle) return this
    val c = Clauderc.colors
    val r = Clauderc.radii.card
    val shape = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp, bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    return this
        .padding(horizontal = Clauderc.spacing.sp4)
        .clip(shape)
        .background(c.card)
        .then(if (first || !divider) Modifier else Modifier.drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) })
}

/** A hairline above a row that follows another inside a card; nothing in the minimal style. */
@Composable
fun Modifier.cardDivider(show: Boolean = true): Modifier {
    if (!isCardStyle || !show) return this
    val line = Clauderc.colors.line
    return drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
}

/**
 * A block the card style puts on a card, 16dp in from the page edges, with [padding] inside;
 * the minimal style draws [content] straight on the page.
 */
@Composable
fun CardBlock(modifier: Modifier = Modifier, padding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    if (!isCardStyle) {
        Column(modifier.fillMaxWidth(), content = content)
        return
    }
    Column(
        modifier
            .padding(horizontal = Clauderc.spacing.sp4)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Clauderc.radii.card))
            .background(Clauderc.colors.card)
            .padding(padding),
    ) {
        CompositionLocalProvider(LocalOnCard provides true) { content() }
    }
}
