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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink

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
    Row(
        modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = if (topLevel) sp.page - sp.sp1 else sp.sp1, end = sp.sp1),
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
    Column(Modifier.weight(1f).padding(start = sp.sp1), verticalArrangement = Arrangement.Center) {
        Text(
            title,
            fontSize = if (mono) 15.sp else 18.sp,
            fontFamily = if (mono) MonoFamily else null,
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
fun BarSubButton(text: String, onClick: () -> Unit, enabled: Boolean = true, mono: Boolean = false) {
    val c = Clauderc.colors
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressShrink(press)
            .clickable(press, PressIndication(c.fg2), enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier else Modifier),
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

/** .hero: the large light title at the top of top-level pages, with optional grey lines under it. */
@Composable
fun Hero(
    title: String,
    modifier: Modifier = Modifier,
    bottom: Dp = Clauderc.spacing.sp6,
    moreLines: List<@Composable RowScope.() -> Unit> = emptyList(),
    subtitle: (@Composable RowScope.() -> Unit)? = null,
) {
    val sp = Clauderc.spacing
    Column(modifier.fillMaxWidth().padding(start = sp.page, end = sp.page, top = sp.sp2, bottom = bottom)) {
        Text(
            title,
            fontSize = Clauderc.fontSizes.display,
            fontWeight = FontWeight.Light,
            letterSpacing = (-0.02).em,
            lineHeight = 1.2.em,
            modifier = Modifier.semantics { heading() },
        )
        for (line in listOfNotNull(subtitle) + moreLines) {
            Row(
                Modifier.padding(top = sp.sp2),
                horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(
                        fontSize = Clauderc.fontSizes.label,
                        color = Clauderc.colors.fg2,
                    ),
                ) { line() }
            }
        }
    }
}

/** .sec-h: a small grey section heading in lists. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val sp = Clauderc.spacing
    Row(
        modifier.fillMaxWidth().padding(start = sp.page, end = sp.page, top = sp.sp5, bottom = sp.sp1),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, fontSize = Clauderc.fontSizes.caption, fontWeight = FontWeight.Medium, color = Clauderc.colors.fg3)
        trailing?.invoke()
    }
}
