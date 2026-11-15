package clauderc.app.ui.components

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.PressIndication
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
    val card = Clauderc.style == UiStyle.Card
    val (bg, fg) = when {
        !enabled -> c.surface to c.fg3
        style == PillStyle.Accent -> c.accent to c.onAccent
        // The card style has one accent and no black buttons.
        style == PillStyle.Ink && card -> c.accent to c.onAccent
        style == PillStyle.Ink -> c.fg to c.bg
        // On the card style's grey page a tonal button is a card; inside a card it goes back to the surface.
        style == PillStyle.Tonal && card && !LocalOnCard.current -> c.card to c.fg
        style == PillStyle.Tonal -> c.surface to c.fg
        else -> Color.Transparent to c.fg
    }
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressShrink(press)
            .height(height)
            .clip(CircleShape)
            .background(bg)
            .then(if (style == PillStyle.Outline && enabled) Modifier.border(1.dp, c.surface2, CircleShape) else Modifier)
            .clickable(press, PressIndication(fg), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (height < 48.dp) Clauderc.spacing.sp4 else Clauderc.spacing.sp6),
        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(text, color = fg, fontSize = Clauderc.fontSizes.label, fontWeight = FontWeight.Medium, letterSpacing = 0.01.em)
    }
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
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Clauderc.colors.fg3,
            modifier = Modifier.size(if (small) 20.dp else 24.dp),
        )
    }
}
