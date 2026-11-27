package clauderc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
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
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.PressTone
import clauderc.app.ui.theme.controlPress
import clauderc.app.ui.theme.pressProgress
import clauderc.app.ui.theme.pressShrink
import clauderc.app.ui.theme.styled

/** .btn.accent / .ink / .tonal / .outline / .danger */
enum class PillStyle { Accent, Ink, Tonal, Outline, Danger }

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
        !enabled && ui == UiStyle.Plate -> c.surface to c.fg3
        !enabled -> c.surface to c.fg3
        style == PillStyle.Danger -> when (ui) {
            UiStyle.Plate -> c.bg to c.danger
            else -> c.danger to Color.White
        }
        style == PillStyle.Accent -> when (ui) {
            // 浮墨: primary confirm buttons are monochrome inverse (demo.html --u-pri: --c-inv).
            UiStyle.Ink -> c.inverse to c.onInverse
            UiStyle.Plate -> c.fg to c.bg
            else -> c.accent to c.onAccent
        }
        style == PillStyle.Ink -> when (ui) {
            UiStyle.Ink -> c.inverse to c.onInverse
            else -> c.fg to c.bg
        }
        style == PillStyle.Tonal -> when (ui) {
            UiStyle.Ink -> c.float to c.fg
            UiStyle.Plate -> c.bg to c.fg
            else -> c.surface to c.fg
        }
        else -> Color.Transparent to c.fg
    }
    val press = remember { MutableInteractionSource() }
    val primary = style == PillStyle.Accent || style == PillStyle.Ink
    val kind = if (primary) PressKind.Primary else PressKind.Button
    val sweep = if (ui == UiStyle.Plate && !primary) pressProgress(press, sweep = true).value else 0f
    val tone = when { primary -> PressTone.Primary; style == PillStyle.Danger -> PressTone.Danger; else -> PressTone.Normal }
    val contentFg = if (ui == UiStyle.Plate && !primary && enabled) {
        val target = if (style == PillStyle.Danger) Color.White else c.bg
        lerp(fg, target, sweep)
    } else {
        fg
    }
    val surface = when (ui) {
        UiStyle.Plate -> when {
            primary -> Modifier.plateStamp(bg, c.bg, c.fg, source = press, enabled = enabled)
            style == PillStyle.Danger -> Modifier
                .background(bg, RectangleShape)
                .plateSweepFill(sweep, c.danger, PlateSweep.RightToLeft)
                .border(1.5.dp, if (enabled) c.danger else c.fg3, RectangleShape)
            else -> Modifier
                .background(bg, RectangleShape)
                .plateSweepFill(sweep, c.fg, PlateSweep.LeftToRight)
                .border(1.5.dp, if (enabled) c.fg else c.fg3, RectangleShape)
        }
        UiStyle.Ink -> Modifier
            .clip(CircleShape)
            .background(bg)
            .outline((style == PillStyle.Outline || style == PillStyle.Tonal) && enabled, c.edge, CircleShape)
        UiStyle.Tile -> Modifier
            .clip(CircleShape)
            .background(bg)
            .outline(style == PillStyle.Outline && enabled, c.line, CircleShape)
        UiStyle.Clear -> Modifier
            .clip(CircleShape)
            .background(bg)
            .outline(style == PillStyle.Outline && enabled, c.surface2, CircleShape)
            .then(if (style == PillStyle.Ink && enabled) Modifier.topHighlight(Color.White.copy(alpha = 0.22f)) else Modifier)
    }
    Row(
        modifier
            .pressShrink(press, kind)
            .height(height)
            .then(surface)
            .clickable(press, if (ui == UiStyle.Plate) null else controlPress(kind, tone, corner = 100.dp), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (height < 48.dp) Clauderc.spacing.sp4 else Clauderc.spacing.sp6),
        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = contentFg, modifier = Modifier.size(20.dp))
        if (ui == UiStyle.Plate) {
            Text(text, color = contentFg, fontSize = 13.5.sp, fontFamily = MonoFamily, fontWeight = FontWeight.ExtraBold)
        } else {
            Text(
                text,
                color = contentFg,
                fontSize = Clauderc.fontSizes.label,
                fontWeight = if (ui == UiStyle.Tile || ui == UiStyle.Ink) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.01.em,
            )
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
    busy: Boolean = false,
    pressKind: PressKind = PressKind.Button,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val color = when {
        !enabled || busy -> c.fg3
        tone == TextTone.Accent -> if (ui == UiStyle.Plate) c.fg else c.accent
        tone == TextTone.Quiet -> c.fg2
        tone == TextTone.Danger -> c.danger
        else -> c.fg
    }
    val press = remember { MutableInteractionSource() }
    if (LocalOverlayActions.current && ui == UiStyle.Plate && tone != TextTone.Quiet) {
        val (bg, fg, borderCol) = when {
            !enabled -> Triple(c.surface, c.fg3, c.fg3)
            tone == TextTone.Danger -> Triple(c.bg, c.danger, c.danger)
            tone == TextTone.Accent -> Triple(c.fg, c.bg, c.fg)
            else -> Triple(c.bg, c.fg, c.fg)
        }
        val sweep = if (tone != TextTone.Accent) pressProgress(press, sweep = true).value else 0f
        val labelColor = when {
            !enabled || busy || tone == TextTone.Accent -> fg
            tone == TextTone.Danger -> lerp(fg, Color.White, sweep)
            else -> lerp(fg, c.bg, sweep)
        }
        Box(
            modifier
                .pressShrink(press, if (tone == TextTone.Accent) PressKind.Primary else PressKind.Button)
                .height(38.dp)
                .then(
                    when (tone) {
                        TextTone.Accent -> Modifier.plateStamp(bg, c.bg, c.fg, source = press, enabled = enabled)
                        TextTone.Danger -> Modifier
                            .background(bg, RectangleShape)
                            .plateSweepFill(sweep, c.danger, PlateSweep.RightToLeft)
                            .border(1.5.dp, borderCol, RectangleShape)
                        else -> Modifier
                            .background(bg, RectangleShape)
                            .plateSweepFill(sweep, c.fg, PlateSweep.LeftToRight)
                            .border(1.5.dp, borderCol, RectangleShape)
                    },
                )
                .clickable(press, null, enabled = enabled && !busy, role = Role.Button, onClick = onClick)
                .padding(horizontal = Clauderc.spacing.sp4),
            contentAlignment = Alignment.Center,
        ) {
            BusyLabel(text, labelColor, FontWeight.ExtraBold, busy)
        }
        return
    }
    val shape = if (ui == UiStyle.Plate) RectangleShape else CircleShape
    val danger = tone == TextTone.Danger
    val hit = if (ui == UiStyle.Plate) (if (danger) c.danger else c.fg) else Color.Unspecified
    val labelColor = if (ui == UiStyle.Plate) lerp(color, if (danger) Color.White else c.bg, pressProgress(press).value) else color
    Box(
        modifier
            .pressShrink(press, pressKind)
            .height(40.dp)
            .clip(shape)
            .clickable(press, controlPress(pressKind, if (danger) PressTone.Danger else PressTone.Normal, fill = hit, corner = if (ui == UiStyle.Plate) 0.dp else 100.dp), enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = Clauderc.spacing.sp3),
        contentAlignment = Alignment.Center,
    ) {
        BusyLabel(text, labelColor, if (ui == UiStyle.Plate) FontWeight.Bold else FontWeight.Medium, busy)
    }
}

/** A text button's label, after a 12dp spinner while [busy] (.tbtn:disabled with .spin). */
@Composable
private fun BusyLabel(text: String, color: Color, weight: FontWeight, busy: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2), verticalAlignment = Alignment.CenterVertically) {
        if (busy) Spinner(size = 12.dp, color = color)
        Text(text, color = color, fontSize = Clauderc.fontSizes.label, fontWeight = weight)
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
    darkPressAlpha: Float? = null,
) {
    SymbolButtonFrame(onClick, modifier, small, enabled, darkPressAlpha) { press ->
        val c = Clauderc.colors
        Icon(
            icon.styled,
            contentDescription = contentDescription,
            tint = if (!enabled) c.fg3 else if (Clauderc.style == UiStyle.Plate) lerp(tint, if (darkPressAlpha != null) Color.Black else c.bg, press.value) else tint,
            modifier = Modifier.size(if (small) 20.dp else 24.dp),
        )
    }
}

/** [SymbolButton]'s press target, styled per look (square ink-framed box on 印版, floating orb on 浮墨, clean circle on 素卡/清玻). */
@Composable
fun SymbolButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    enabled: Boolean = true,
    darkPressAlpha: Float? = null,
    content: @Composable BoxScope.(androidx.compose.runtime.State<Float>) -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val ui = Clauderc.style
    val c = Clauderc.colors
    val kind = if (darkPressAlpha != null) PressKind.DarkIcon else PressKind.Icon
    val progress = pressProgress(press, kind)
    val hit = when {
        darkPressAlpha != null -> Color.White.copy(alpha = if (ui == UiStyle.Plate) 1f else darkPressAlpha)
        ui == UiStyle.Plate -> c.fg
        else -> Color.Unspecified
    }
    val shape = if (ui == UiStyle.Plate) RectangleShape else CircleShape
    Box(
        modifier
            .pressShrink(press, kind)
            .size(if (small) 40.dp else 48.dp)
            .clip(shape)
            .clickable(press, controlPress(kind, fill = hit, corner = if (ui == UiStyle.Plate) 0.dp else 100.dp), enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(progress) }
}

@Composable
internal fun smallButtonTint(source: MutableInteractionSource, color: Color): Color =
    if (Clauderc.style == UiStyle.Plate) lerp(color, Clauderc.colors.bg, pressProgress(source, PressKind.SmallButton).value) else color

@Composable
internal fun Modifier.smallPressButton(source: MutableInteractionSource, shrink: Boolean = true, onClick: () -> Unit): Modifier {
    val c = Clauderc.colors
    val plate = Clauderc.style == UiStyle.Plate
    val sweep = if (plate) pressProgress(source, PressKind.SmallButton, sweep = true).value else 0f
    return (if (shrink) pressShrink(source, PressKind.SmallButton) else this)
        .then(if (plate) Modifier.plateSweepFill(sweep, c.fg, PlateSweep.LeftToRight) else Modifier)
        .clickable(source, if (plate) null else controlPress(PressKind.SmallButton), role = Role.Button, onClick = onClick)
}
