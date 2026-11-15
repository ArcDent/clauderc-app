package clauderc.app.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import clauderc.app.data.AccentChoice
import clauderc.app.data.Appearance
import clauderc.app.data.ThemeMode
import clauderc.app.data.UiStyle
import clauderc.app.machines.NavClearance
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.Hero
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.accentColor
import clauderc.app.ui.theme.palette
import clauderc.app.ui.theme.pressShrink

/** Names of the accents, in the order of the swatches. */
val AccentChoice.label: String
    get() = when (this) {
        AccentChoice.Wallpaper -> "跟随壁纸"
        AccentChoice.Blue -> "蓝色"
        AccentChoice.Purple -> "紫色"
        AccentChoice.Pink -> "粉色"
        AccentChoice.Red -> "红色"
        AccentChoice.Orange -> "橙色"
        AccentChoice.Yellow -> "黄色"
        AccentChoice.Green -> "绿色"
        AccentChoice.Gray -> "灰色"
    }

/**
 * The 设置 tab: 样式, 深浅色 and, in the card style, 主题色. Every option previews itself with a
 * small phone drawn in its own colours. Each callback also gets the centre of the option that was
 * tapped, in root coordinates, so the new theme can spread out from there.
 */
@Composable
fun SettingsScreen(
    look: Appearance,
    cardAccent: Color,
    wallpaperAvailable: Boolean,
    onStyle: (UiStyle, Offset) -> Unit,
    onMode: (ThemeMode, Offset) -> Unit,
    onAccent: (AccentChoice, Offset) -> Unit,
) {
    val c = Clauderc.colors
    val dark = c.isDark
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = NavClearance)) {
            Hero("设置")
            SettingsGroup(Symbols.Style, "样式") {
                OptionRow {
                    for (style in UiStyle.entries) {
                        Option(if (style == UiStyle.Minimal) "极简" else "卡片", look.style == style, { onStyle(style, it) }) {
                            MiniPhone(style, dark, cardAccent)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            SettingsGroup(Symbols.Contrast, "深浅色") {
                OptionRow {
                    Option("跟随系统", look.mode == ThemeMode.System, { onMode(ThemeMode.System, it) }) { MiniPhone(look.style, false, cardAccent, splitDark = true) }
                    Option("浅色", look.mode == ThemeMode.Light, { onMode(ThemeMode.Light, it) }) { MiniPhone(look.style, false, cardAccent) }
                    Option("深色", look.mode == ThemeMode.Dark, { onMode(ThemeMode.Dark, it) }) { MiniPhone(look.style, true, cardAccent) }
                }
            }
            if (look.style == UiStyle.Card) {
                SettingsGroup(Symbols.Palette, "主题色") {
                    val choices = AccentChoice.entries.filter { it != AccentChoice.Wallpaper || wallpaperAvailable }
                    Swatches(choices, look.accent, dark, onAccent)
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    val sp = Clauderc.spacing
    val c = Clauderc.colors
    val card = Clauderc.style == UiStyle.Card
    // Minimal: a heading in the page gutter; card: the heading sits inside its own card.
    val outer = if (card) {
        Modifier
            .padding(start = sp.sp4, end = sp.sp4, bottom = sp.sp3)
            .clip(RoundedCornerShape(Clauderc.radii.card))
            .background(c.card)
            .padding(start = sp.sp2, end = sp.sp2, top = sp.sp4, bottom = sp.sp5)
    } else {
        Modifier.padding(horizontal = sp.page - sp.sp1)
    }
    Column(outer.fillMaxWidth()) {
        Row(
            Modifier.padding(start = sp.sp2, top = if (card) 0.dp else sp.sp6, bottom = sp.sp4),
            horizontalArrangement = Arrangement.spacedBy(sp.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.fg2, modifier = Modifier.size(20.dp))
            Text(title, fontSize = Clauderc.fontSizes.label, fontWeight = FontWeight.Medium, color = c.fg)
        }
        content()
    }
}

/** Three equal columns, like the mockup's .opts grid; two options leave the third empty. */
@Composable
private fun OptionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun RowScope.Option(
    label: String,
    selected: Boolean,
    onClick: (Offset) -> Unit,
    preview: @Composable () -> Unit,
) {
    val c = Clauderc.colors
    val card = Clauderc.style == UiStyle.Card
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .onGloballyPositioned { center = it.boundsInRoot().center }
                .pressShrink(press)
                .clip(RoundedCornerShape(Clauderc.radii.md))
                .selectable(selected, press, PressIndication(c.fg), role = Role.RadioButton) { onClick(center) }
                .padding(Clauderc.spacing.sp1),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2),
        ) {
            Box(Modifier.size(84.dp, 116.dp).border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(14.dp))) {
                preview()
            }
            Text(
                label,
                fontSize = Clauderc.fontSizes.label,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = when {
                    selected && card -> c.accent
                    selected -> c.fg
                    else -> c.fg2
                },
            )
        }
    }
}

/**
 * A tiny phone in [style]'s colours: a title, three rows and the nav bar, with the accent on the
 * first row and in the nav indicator. [splitDark] covers the lower right half with the dark version.
 * [cardAccent] only shows in the card style; the minimal one keeps its cobalt.
 */
@Composable
private fun MiniPhone(style: UiStyle, dark: Boolean, cardAccent: Color, splitDark: Boolean = false) {
    val light = palette(style, dark, cardAccent)
    val night = palette(style, true, cardAccent)
    Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).testTag("mini")) {
        drawMini(style, light)
        if (splitDark) {
            val half = Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            clipPath(half) { drawMini(style, night) }
        }
    }
}

private fun DrawScope.drawMini(style: UiStyle, p: ClaudercColors) {
    val card = style == UiStyle.Card
    val u = 1.dp.toPx()
    drawRect(p.bg)
    val left = 9 * u
    val width = size.width - 18 * u
    var y = 13 * u
    // Title
    drawRoundRect(p.fg.copy(alpha = if (card) 1f else 0.8f), Offset(left, y), Size(width * if (card) 0.46f else 0.52f, (if (card) 8 else 7) * u), CornerRadius(4 * u))
    y += (if (card) 8 else 7) * u + 10 * u
    // Rows
    val rowH = (if (card) 17 else 15) * u
    repeat(3) { i ->
        if (card) {
            drawRoundRect(p.card, Offset(left, y), Size(width, rowH), CornerRadius(6 * u))
        } else {
            drawRect(p.line, Offset(left, y + rowH - u), Size(width, u))
        }
        if (i == 0) drawCircle(p.accent, 3 * u, Offset(left + (if (card) 9 else 6) * u, y + (if (card) 8.5f else 7f) * u))
        y += rowH + 5 * u
    }
    // Nav bar and its indicator
    val navLeft = size.width * 0.12f
    val navW = size.width * 0.76f
    val navTop = size.height - 21 * u
    val navColor = if (card) p.card else p.sheet
    drawRoundRect(navColor, Offset(navLeft, navTop), Size(navW, 14 * u), CornerRadius(7 * u))
    drawRoundRect(p.line, Offset(navLeft, navTop), Size(navW, 14 * u), CornerRadius(7 * u), style = Stroke(0.5f * u))
    drawRoundRect(
        if (card) p.accent.copy(alpha = 0.22f) else p.fg.copy(alpha = 0.1f),
        Offset(navLeft + 3 * u, navTop + 3 * u),
        Size(navW * 0.3f, 8 * u),
        CornerRadius(5 * u),
    )
}

/** The accent swatches, five to a row; the picked one gets a ring and a tick. */
@Composable
private fun Swatches(choices: List<AccentChoice>, picked: AccentChoice, dark: Boolean, onPick: (AccentChoice, Offset) -> Unit) {
    val sp = Clauderc.spacing
    Column(Modifier.fillMaxWidth()) {
        choices.chunked(5).forEachIndexed { r, line ->
            if (r > 0) Spacer(Modifier.height(sp.sp4))
            Row(Modifier.fillMaxWidth()) {
                line.forEach { choice -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Swatch(choice, choice == picked, dark, onPick) } }
                repeat(5 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(
            picked.label,
            modifier = Modifier.padding(start = sp.sp2, top = sp.sp4),
            fontSize = Clauderc.fontSizes.caption,
            color = Clauderc.colors.fg3,
        )
    }
}

@Composable
private fun Swatch(choice: AccentChoice, selected: Boolean, dark: Boolean, onPick: (AccentChoice, Offset) -> Unit) {
    val color = accentColor(choice, dark)
    val on = palette(UiStyle.Card, dark, color).onAccent
    var center by remember { mutableStateOf(Offset.Zero) }
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(54.dp)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .semantics { contentDescription = choice.label }
            .pressShrink(press)
            .selectable(selected, press, indication = null, role = Role.RadioButton) { onPick(choice, center) },
        contentAlignment = Alignment.Center,
    ) {
        // The ring sits 5dp outside the dot. A pick spreads the new theme out from here, so the ring needs no animation of its own.
        if (selected) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx())) }
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            val icon = when {
                selected -> Symbols.Check
                choice == AccentChoice.Wallpaper -> Symbols.Wallpaper
                else -> null
            }
            if (icon != null) Icon(icon, contentDescription = null, tint = on, modifier = Modifier.size(20.dp))
        }
    }
}
