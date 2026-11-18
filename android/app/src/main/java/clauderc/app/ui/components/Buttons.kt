package clauderc.app.ui.components

import clauderc.app.ui.theme.styled
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.PushDepth
import clauderc.app.ui.theme.pressShrink

/** .btn.accent / .ink / .tonal / .outline */
enum class PillStyle { Accent, Ink, Tonal, Outline }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Accent,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val (bg, fg) = when {
        !enabled && ui == UiStyle.Sticker -> c.surface2 to c.fg3
        !enabled -> c.surface to c.fg3
        style == PillStyle.Accent -> c.accent to c.onAccent
        // 贴纸 has one accent and no black buttons.
        style == PillStyle.Ink && ui == UiStyle.Sticker -> c.accent to c.onAccent
        style == PillStyle.Ink -> c.fg to c.bg
        // On 贴纸's paper a tonal button is a white sticker.
        style == PillStyle.Tonal && ui == UiStyle.Sticker -> c.card to c.fg
        style == PillStyle.Tonal -> c.surface to c.fg
        else -> Color.Transparent to c.fg
    }
    val press = remember { MutableInteractionSource() }
    val surface = when (ui) {
        // An ink outline and a 3dp hard shadow that the press sinks onto; disabled ones lose the shadow.
        UiStyle.Sticker -> Modifier.sticker(bg, if (enabled) c.ink else c.fg3, 999.dp, drop = if (enabled) PushDepth else 0.dp, source = press)
        // Square corners.
        UiStyle.Grid -> Modifier.clip(RoundedCornerShape(2.dp)).background(bg).outline(style == PillStyle.Outline && enabled, c.surface2, RoundedCornerShape(2.dp))
        // A faint highlight along the top edge of the solid black button.
        UiStyle.Clear -> Modifier.clip(CircleShape).background(bg).outline(style == PillStyle.Outline && enabled, c.surface2, CircleShape)
            .then(if (style == PillStyle.Ink && enabled) Modifier.topHighlight(Color.White.copy(alpha = 0.22f)) else Modifier)
    }
    Row(
        modifier
            .pressShrink(press)
            .height(height)
            .then(surface)
            .clickable(press, PressIndication(fg), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (height < 48.dp) Clauderc.spacing.sp4 else Clauderc.spacing.sp6),
        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        if (ui == UiStyle.Grid) {
            Text(text, color = fg, fontSize = 13.sp, fontFamily = MonoFamily, fontWeight = FontWeight.Medium)
        } else {
            Text(text, color = fg, fontSize = Clauderc.fontSizes.label, fontWeight = if (ui == UiStyle.Sticker) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.01.em)
        }
    }
}

/** A 1dp ring inside the edge when [show], as the outline buttons' inset box-shadow. */
private fun Modifier.outline(show: Boolean, color: Color, shape: Shape): Modifier = if (show) border(1.dp, color, shape) else this

/** A 1dp line of [color] along the top inside edge (box-shadow: inset 0 1px 0); the clip before it rounds its ends. */
internal fun Modifier.topHighlight(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(color, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
}

/** .tbtn and its quiet and danger variants. */
enum class TextTone { Accent, Quiet, Danger, Ink }

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: TextTone = TextTone.Accent,
    enabled: Boolean = true,
) {
    val c = Clauderc.colors
    val color = when {
        !enabled -> c.fg3
        tone == TextTone.Accent -> c.accent
        tone == TextTone.Quiet -> c.fg2
        tone == TextTone.Danger -> c.danger
        else -> c.fg
    }
    val press = remember { MutableInteractionSource() }
    // 贴纸 draws a sheet's or dialog's actions as ink-outlined stickers: the main one in the accent, a
    // dangerous one in red, the rest white; quiet ones such as 「恢复默认」 stay text.
    if (LocalOverlayActions.current && Clauderc.style == UiStyle.Sticker && tone != TextTone.Quiet) {
        val (bg, fg) = when {
            !enabled -> c.surface2 to c.fg3
            tone == TextTone.Accent -> c.accent to c.onAccent
            tone == TextTone.Danger -> c.danger to Color.White
            else -> c.card to c.fg
        }
        Box(
            modifier
                .pressShrink(press)
                .height(38.dp)
                .sticker(bg, if (enabled) c.ink else c.fg3, 999.dp, drop = if (enabled) PushDepth else 0.dp, source = press)
                .clickable(press, PressIndication(fg), enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = Clauderc.spacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = fg, fontSize = Clauderc.fontSizes.label, fontWeight = FontWeight.ExtraBold)
        }
        return
    }
    Box(
        modifier
            .pressShrink(press)
            .height(40.dp)
            .clip(CircleShape)
            .clickable(press, PressIndication(color), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Clauderc.spacing.sp3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = Clauderc.fontSizes.label, fontWeight = FontWeight.Medium)
    }
}

/** .icon-btn (48dp) and .icon-btn.sm (40dp with a 20dp icon). */
@Composable
fun SymbolButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Clauderc.colors.fg,
) {
    val box = if (small) 40.dp else 48.dp
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressShrink(press)
            .size(box)
            .clip(CircleShape)
            .clickable(press, PressIndication(tint), enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon.styled,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Clauderc.colors.fg3,
            modifier = Modifier.size(if (small) 20.dp else 24.dp),
        )
    }
}
