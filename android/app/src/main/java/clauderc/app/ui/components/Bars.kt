package clauderc.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.PressRow
import clauderc.app.ui.theme.Symbols

/** .appbar: 56dp row; top-level pages indent their title to the page gutter. */
@Composable
fun AppBar(
    modifier: Modifier = Modifier,
    topLevel: Boolean = false,
    navigation: (@Composable () -> Unit)? = null,
    title: @Composable RowScope.() -> Unit = { Spacer(Modifier.weight(1f)) },
    actions: @Composable RowScope.() -> Unit = {},
) {
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val c = Clauderc.colors
    val startPad = when {
        !topLevel -> sp.sp1
        ui == UiStyle.Clear -> sp.page - sp.sp1
        else -> sp.sp4 - sp.sp1
    }
    val bottomRule = if (ui == UiStyle.Plate && !topLevel) {
        Modifier.drawBehind {
            val h = 2.dp.toPx()
            drawRect(c.fg, Offset(0f, size.height - h), Size(size.width, h))
        }
    } else {
        Modifier
    }
    Row(
        modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .then(bottomRule)
            .height(56.dp)
            .padding(start = startPad, end = sp.sp2),
        horizontalArrangement = Arrangement.spacedBy(sp.sp1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation?.invoke()
        title()
        actions()
    }
}

/** .bar-title with an optional .bar-sub line of small switches. */
@Composable
fun RowScope.BarTitle(
    title: String,
    mono: Boolean = false,
    subtitle: (@Composable RowScope.() -> Unit)? = null,
) {
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    Column(Modifier.weight(1f).padding(start = sp.sp1), verticalArrangement = Arrangement.Center) {
        Text(
            title,
            fontSize = if (mono) 15.sp else 17.sp,
            fontFamily = if (mono) MonoFamily else null,
            fontWeight = when (ui) {
                UiStyle.Plate -> FontWeight.ExtraBold
                UiStyle.Tile, UiStyle.Ink -> FontWeight.SemiBold
                UiStyle.Clear -> FontWeight.Medium
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(sp.sp3), verticalAlignment = Alignment.CenterVertically, content = subtitle)
        }
    }
}

/** A .bar-sub button: small grey text with a chevron, e.g. the model switch. */
@Composable
fun BarSubButton(text: String, onClick: () -> Unit, enabled: Boolean = true, mono: Boolean = false, modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .clickable(press, PressIndication(row = PressRow.Title), enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = if (mono) 11.sp else Clauderc.fontSizes.caption,
            fontFamily = if (mono) MonoFamily else null,
            color = if (enabled) c.fg2 else c.fg2.copy(alpha = 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Symbols.ExpandMore, contentDescription = null, tint = if (enabled) c.fg3 else c.fg3.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
    }
}

/** .hero: the large title at the top of top-level pages, shaped per look. */
@Composable
fun Hero(
    title: String,
    modifier: Modifier = Modifier,
    bottom: Dp = Clauderc.spacing.sp6,
    moreLines: List<@Composable RowScope.() -> Unit> = emptyList(),
    subtitle: (@Composable RowScope.() -> Unit)? = null,
) {
    val sp = Clauderc.spacing
    val c = Clauderc.colors
    val ui = Clauderc.style
    val lines = listOfNotNull(subtitle) + moreLines
    val heading: @Composable (Modifier) -> Unit = { m ->
        when (ui) {
            UiStyle.Clear -> Text(title, m.semantics { heading() }, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em, lineHeight = 1.2.em)
            UiStyle.Plate -> Text(title, m.semantics { heading() }, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em, lineHeight = 1.15.em)
            UiStyle.Tile -> Text(title, m.semantics { heading() }, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, lineHeight = 1.2.em)
            UiStyle.Ink -> Text(title, m.semantics { heading() }, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em, lineHeight = 1.2.em)
        }
    }
    val line: @Composable (@Composable RowScope.() -> Unit, Modifier, Boolean) -> Unit = { content, m, mono ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(sp.sp2), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(
                LocalTextStyle provides if (mono) {
                    TextStyle(fontSize = 11.sp, fontFamily = MonoFamily, color = c.fg3)
                } else {
                    TextStyle(fontSize = Clauderc.fontSizes.label, color = c.fg2)
                },
            ) { content() }
        }
    }
    if (ui == UiStyle.Plate) {
        Column(
            modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = sp.sp2, bottom = minOf(bottom, sp.sp4)),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(sp.sp2)) {
                heading(Modifier.weight(1f))
                lines.firstOrNull()?.let { line(it, Modifier.padding(bottom = 4.dp), true) }
            }
            for (more in lines.drop(1)) line(more, Modifier.padding(top = sp.sp1), true)
        }
        return
    }
    val horizontal = if (ui == UiStyle.Clear) sp.page else sp.sp4
    Column(modifier.fillMaxWidth().padding(start = horizontal, end = horizontal, top = sp.sp2, bottom = minOf(bottom, sp.sp4))) {
        heading(Modifier)
        for (more in lines) line(more, Modifier.padding(top = sp.sp2), false)
    }
}

/** .sec-h: a small section heading in lists. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val c = Clauderc.colors
    val horizontal = when (ui) {
        UiStyle.Clear -> sp.page
        UiStyle.Plate -> 18.dp
        else -> sp.sp4
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = horizontal, end = horizontal, top = sp.sp5, bottom = if (Clauderc.look.cards) sp.sp2 else sp.sp1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = Clauderc.fontSizes.caption,
            fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.SemiBold,
            fontFamily = if (ui == UiStyle.Plate) MonoFamily else null,
            letterSpacing = if (ui == UiStyle.Plate) 0.04.em else 0.em,
            color = if (ui == UiStyle.Plate) c.fg2 else c.fg3,
        )
        trailing?.invoke()
    }
}
